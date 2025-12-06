package plc.project.evaluator;

import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        RuntimeValue value = new RuntimeValue.Primitive(null);
        try {
            for (Ast.Stmt stmt : ast.statements()) {
                value = visit(stmt);
            }
        } catch (ReturnException returnException) {
            throw new EvaluateException("RETURN called outside of a function.", Optional.of(ast));
        }
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
        RuntimeValue value = ast.value().isPresent() ? visit(ast.value().get()) : new RuntimeValue.Primitive(null);
        try {
            scope.define(ast.name(), value);
        } catch (IllegalStateException e) {
            throw new EvaluateException("Variable " + ast.name() + " is already defined.", Optional.of(ast));
        }
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        for (int i = 0; i < ast.parameters().size(); i++) {
            for (int j = i + 1; j < ast.parameters().size(); j++) {
                if (ast.parameters().get(i).equals(ast.parameters().get(j))) {
                    throw new EvaluateException("Duplicate parameter name: " + ast.parameters().get(i), Optional.of(ast));
                }
            }
        }
        Scope definitionScope = scope;
        RuntimeValue.Function function = new RuntimeValue.Function(ast.name(), (arguments) -> {
            if (ast.parameters().size() != arguments.size()) {
                throw new EvaluateException("Function " + ast.name() + " expected " + ast.parameters().size() + " arguments but got " + arguments.size() + ".", Optional.of(ast));
            }
            Scope previousScope = scope;
            try {
                scope = new Scope(definitionScope);
                for (int i = 0; i < ast.parameters().size(); i++) {
                    scope.define(ast.parameters().get(i), arguments.get(i));
                }
                scope = new Scope(scope);
                try {
                    for (Ast.Stmt stmt : ast.body()) {
                        visit(stmt);
                    }
                    return new RuntimeValue.Primitive(null);
                } catch (ReturnException returnException) {
                    return returnException.value;
                }
            } finally {
                scope = previousScope;
            }
        });
        try {
            scope.define(ast.name(), function);
        } catch (IllegalStateException e) {
            throw new EvaluateException("Function " + ast.name() + " is already defined.", Optional.of(ast));
        }
        return function;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.If ast) throws EvaluateException {
        RuntimeValue conditionValue = visit(ast.condition());
        Optional<Boolean> condition = requireType(conditionValue, Boolean.class);
        if (condition.isEmpty()) {
            throw new EvaluateException("Condition must be a boolean.", Optional.of(ast.condition()));
        }
        Scope previousScope = scope;
        try {
            scope = new Scope(previousScope);
            RuntimeValue result = new RuntimeValue.Primitive(null);
            java.util.List<Ast.Stmt> body = condition.get() ? ast.thenBody() : ast.elseBody();
            for (Ast.Stmt stmt : body) {
                result = visit(stmt);
            }
            return result;
        } finally {
            scope = previousScope;
        }
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.For ast) throws EvaluateException {
        RuntimeValue iterableValue = visit(ast.expression());
        Optional<RuntimeValue.Primitive> primitive = requireType(iterableValue, RuntimeValue.Primitive.class);
        if (primitive.isEmpty() || !(primitive.get().value() instanceof Iterable)) {
            throw new EvaluateException("Expression must be an iterable.", Optional.of(ast.expression()));
        }
        Iterable<?> rawIterable = (Iterable<?>) primitive.get().value();
        // Validate all elements are RuntimeValues
        java.util.List<RuntimeValue> elements = new ArrayList<>();
        for (Object element : rawIterable) {
            if (!(element instanceof RuntimeValue)) {
                throw new EvaluateException("Iterable contains invalid element.", Optional.of(ast.expression()));
            }
            elements.add((RuntimeValue) element);
        }
        Scope previousScope = scope;
        try {
            for (RuntimeValue element : elements) {
                scope = new Scope(previousScope);
                scope.define(ast.name(), element);
                scope = new Scope(scope);
                for (Ast.Stmt stmt : ast.body()) {
                    visit(stmt);
                }
                scope = previousScope;
            }
        } finally {
            scope = previousScope;
        }
        return new RuntimeValue.Primitive(null);
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        RuntimeValue value = ast.value().isPresent() ? visit(ast.value().get()) : new RuntimeValue.Primitive(null);
        throw new ReturnException(value);
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {
        if (ast.expression() instanceof Ast.Expr.Variable varExpr) {
            RuntimeValue newValue = visit(ast.value());
            if (scope.resolve(varExpr.name(), false).isEmpty()) {
                throw new EvaluateException("Undefined variable: " + varExpr.name(), Optional.of(varExpr));
            }
            scope.assign(varExpr.name(), newValue);
            return newValue;
        }
        if (ast.expression() instanceof Ast.Expr.Property propExpr) {
            RuntimeValue newValue = visit(ast.value());
            RuntimeValue receiverValue = visit(propExpr.receiver());
            Optional<RuntimeValue.ObjectValue> object = requireType(receiverValue, RuntimeValue.ObjectValue.class);
            if (object.isEmpty()) {
                throw new EvaluateException("Receiver must be an object.", Optional.of(propExpr.receiver()));
            }
            if (object.get().scope().resolve(propExpr.name(), false).isEmpty()) {
            boolean propertyExists = false;
            Optional<RuntimeValue> prototypeValue = object.get().scope().resolve("prototype", false);
            if (prototypeValue.isPresent()) {
                Optional<RuntimeValue.ObjectValue> prototype = requireType(prototypeValue.get(), RuntimeValue.ObjectValue.class);
                if (prototype.isPresent() && prototype.get().scope().resolve(propExpr.name(), false).isPresent()) {
                    propertyExists = true;
                }
            }
            if (!propertyExists) {
                throw new EvaluateException("Undefined property: " + propExpr.name(), Optional.of(propExpr));
            }
        }
            object.get().scope().assign(propExpr.name(), newValue);
            return newValue;
        }
        throw new EvaluateException("Invalid assignment target.", Optional.of(ast.expression()));
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Literal ast) throws EvaluateException {
        return new RuntimeValue.Primitive(ast.value());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Group ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException {
        if (ast.operator().equals("AND")) {
            RuntimeValue leftValue = visit(ast.left());
            Optional<Boolean> left = requireType(leftValue, Boolean.class);
            if (left.isEmpty()) {
                throw new EvaluateException("Left operand must be boolean.", Optional.of(ast.left()));
            }
            if (!left.get()) {
                return new RuntimeValue.Primitive(false);
            }
            RuntimeValue rightValue = visit(ast.right());
            Optional<Boolean> right = requireType(rightValue, Boolean.class);
            if (right.isEmpty()) {
                throw new EvaluateException("Right operand must be boolean.", Optional.of(ast.right()));
            }
            return new RuntimeValue.Primitive(right.get());
        }
        if (ast.operator().equals("OR")) {
            RuntimeValue leftValue = visit(ast.left());
            Optional<Boolean> left = requireType(leftValue, Boolean.class);
            if (left.isEmpty()) {
                throw new EvaluateException("Left operand must be boolean.", Optional.of(ast.left()));
            }
            if (left.get()) {
                return new RuntimeValue.Primitive(true);
            }
            RuntimeValue rightValue = visit(ast.right());
            Optional<Boolean> right = requireType(rightValue, Boolean.class);
            if (right.isEmpty()) {
                throw new EvaluateException("Right operand must be boolean.", Optional.of(ast.right()));
            }
            return new RuntimeValue.Primitive(right.get());
        }
        if (ast.operator().equals("+")) {
            RuntimeValue leftValue = visit(ast.left());
            RuntimeValue rightValue = visit(ast.right());
            Optional<String> leftStr = requireType(leftValue, String.class);
            Optional<String> rightStr = requireType(rightValue, String.class);
            if (leftStr.isPresent() || rightStr.isPresent()) {
                return new RuntimeValue.Primitive(leftValue.print() + rightValue.print());
            }
            Optional<BigInteger> leftInt = requireType(leftValue, BigInteger.class);
            Optional<BigInteger> rightInt = requireType(rightValue, BigInteger.class);
            if (leftInt.isPresent() && rightInt.isPresent()) {
                return new RuntimeValue.Primitive(leftInt.get().add(rightInt.get()));
            }
            Optional<BigDecimal> leftDec = requireType(leftValue, BigDecimal.class);
            Optional<BigDecimal> rightDec = requireType(rightValue, BigDecimal.class);
            if (leftDec.isPresent() && rightDec.isPresent()) {
                return new RuntimeValue.Primitive(leftDec.get().add(rightDec.get()));
            }
            throw new EvaluateException("Invalid operands for +.", Optional.of(ast.left()));
        }
        RuntimeValue leftValue = visit(ast.left());
        switch (ast.operator()) {
            case "-":
            case "*":
            case "/":
                Optional<BigInteger> leftInt = requireType(leftValue, BigInteger.class);
                Optional<BigDecimal> leftDec = requireType(leftValue, BigDecimal.class);
                if (leftInt.isEmpty() && leftDec.isEmpty()) {
                    throw new EvaluateException("Left operand must be numeric.", Optional.of(ast.left()));
                }
                break;
            case "<":
            case "<=":
            case ">":
            case ">=":
                Optional<Comparable> leftComp = requireType(leftValue, RuntimeValue.Primitive.class)
                    .map(RuntimeValue.Primitive::value)
                    .filter(v -> v instanceof Comparable)
                    .map(v -> (Comparable) v);
                if (leftComp.isEmpty()) {
                    throw new EvaluateException("Left operand must be comparable.", Optional.of(ast.left()));
                }
                break;
        }
        RuntimeValue rightValue = visit(ast.right());
        switch (ast.operator()) {
            case "-":
            case "*":
            case "/":
                return evaluateArithmetic(leftValue, rightValue, ast);
            case "==":
                Object leftObj = requireType(leftValue, RuntimeValue.Primitive.class)
                    .map(RuntimeValue.Primitive::value).orElse(leftValue);
                Object rightObj = requireType(rightValue, RuntimeValue.Primitive.class)
                    .map(RuntimeValue.Primitive::value).orElse(rightValue);
                return new RuntimeValue.Primitive(Objects.equals(leftObj, rightObj));
            case "!=":
                Object leftObj2 = requireType(leftValue, RuntimeValue.Primitive.class)
                    .map(RuntimeValue.Primitive::value).orElse(leftValue);
                Object rightObj2 = requireType(rightValue, RuntimeValue.Primitive.class)
                    .map(RuntimeValue.Primitive::value).orElse(rightValue);
                return new RuntimeValue.Primitive(!Objects.equals(leftObj2, rightObj2));
            case "<":
            case "<=":
            case ">":
            case ">=":
                return evaluateComparison(leftValue, rightValue, ast);
            default:
                throw new EvaluateException("Unknown operator: " + ast.operator(), Optional.of(ast));
        }
    }

    private RuntimeValue evaluateArithmetic(RuntimeValue leftValue, RuntimeValue rightValue, Ast.Expr.Binary ast) throws EvaluateException {
        Optional<BigInteger> leftInt = requireType(leftValue, BigInteger.class);
        Optional<BigInteger> rightInt = requireType(rightValue, BigInteger.class);
        if (leftInt.isPresent() && rightInt.isPresent()) {
            return switch (ast.operator()) {
                case "-" -> new RuntimeValue.Primitive(leftInt.get().subtract(rightInt.get()));
                case "*" -> new RuntimeValue.Primitive(leftInt.get().multiply(rightInt.get()));
                case "/" -> {
                    if (rightInt.get().equals(BigInteger.ZERO)) {
                        throw new EvaluateException("Division by zero.", Optional.of(ast.right()));
                    }
                    yield new RuntimeValue.Primitive(leftInt.get().divide(rightInt.get()));
                }
                default -> throw new EvaluateException("Invalid operator.", Optional.of(ast));
            };
        }
        Optional<BigDecimal> leftDec = requireType(leftValue, BigDecimal.class);
        Optional<BigDecimal> rightDec = requireType(rightValue, BigDecimal.class);
        if (leftDec.isPresent() && rightDec.isPresent()) {
            return switch (ast.operator()) {
                case "-" -> new RuntimeValue.Primitive(leftDec.get().subtract(rightDec.get()));
                case "*" -> new RuntimeValue.Primitive(leftDec.get().multiply(rightDec.get()));
                case "/" -> {
                    if (rightDec.get().compareTo(BigDecimal.ZERO) == 0) {
                        throw new EvaluateException("Division by zero.", Optional.of(ast.right()));
                    }
                    yield new RuntimeValue.Primitive(leftDec.get().divide(rightDec.get(), RoundingMode.HALF_EVEN));
                }
                default -> throw new EvaluateException("Invalid operator.", Optional.of(ast));
            };
        }
        throw new EvaluateException("Operands must be numeric and same type.", Optional.of(ast.right()));
    }

    @SuppressWarnings("unchecked")
    private RuntimeValue evaluateComparison(RuntimeValue leftValue, RuntimeValue rightValue, Ast.Expr.Binary ast) throws EvaluateException {
        Optional<Comparable> left = requireType(leftValue, RuntimeValue.Primitive.class)
            .map(RuntimeValue.Primitive::value)
            .filter(v -> v instanceof Comparable)
            .map(v -> (Comparable) v);
        if (left.isEmpty()) {
            throw new EvaluateException("Left operand must be comparable.", Optional.of(ast.left()));
        }
        Optional<Object> right = requireType(rightValue, RuntimeValue.Primitive.class)
            .map(RuntimeValue.Primitive::value);
        if (right.isEmpty() || !left.get().getClass().equals(right.get().getClass())) {
            throw new EvaluateException("Right operand must be same type as left.", Optional.of(ast.right()));
        }
        int comparison = left.get().compareTo(right.get());
        return new RuntimeValue.Primitive(switch (ast.operator()) {
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> throw new EvaluateException("Invalid comparison operator.", Optional.of(ast));
        });
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Variable ast) throws EvaluateException {
        Optional<RuntimeValue> value = scope.resolve(ast.name(), false);
        if (value.isEmpty()) {
            throw new EvaluateException("Undefined variable: " + ast.name(), Optional.of(ast));
        }
        return value.get();
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        RuntimeValue receiverValue = visit(ast.receiver());
        Optional<RuntimeValue.ObjectValue> object = requireType(receiverValue, RuntimeValue.ObjectValue.class);
        if (object.isEmpty()) {
            throw new EvaluateException("Receiver must be an object.", Optional.of(ast.receiver()));
        }
        Optional<RuntimeValue> value = object.get().scope().resolve(ast.name(), false);
        if (value.isEmpty()) {
            // Check prototype chain for inherited properties
            Optional<RuntimeValue> prototypeValue = object.get().scope().resolve("prototype", false);
            if (prototypeValue.isPresent()) {
                Optional<RuntimeValue.ObjectValue> prototype = requireType(prototypeValue.get(), RuntimeValue.ObjectValue.class);
                if (prototype.isPresent()) {
                    value = prototype.get().scope().resolve(ast.name(), false);
                }
            }
            if (value.isEmpty()) {
                throw new EvaluateException("Undefined property: " + ast.name(), Optional.of(ast));
            }
        }
        return value.get();
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException {
        Optional<RuntimeValue> functionValue = scope.resolve(ast.name(), false);
        if (functionValue.isEmpty()) {
            throw new EvaluateException("Undefined function: " + ast.name(), Optional.of(ast));
        }
        Optional<RuntimeValue.Function> function = requireType(functionValue.get(), RuntimeValue.Function.class);
        if (function.isEmpty()) {
            throw new EvaluateException("Not a function: " + ast.name(), Optional.of(ast));
        }
        java.util.List<RuntimeValue> arguments = new ArrayList<>();
        for (Ast.Expr arg : ast.arguments()) {
            arguments.add(visit(arg));
        }
        return function.get().definition().invoke(arguments);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Method ast) throws EvaluateException {
        RuntimeValue receiverValue = visit(ast.receiver());
        Optional<RuntimeValue.ObjectValue> object = requireType(receiverValue, RuntimeValue.ObjectValue.class);
        if (object.isEmpty()) {
            throw new EvaluateException("Receiver must be an object.", Optional.of(ast.receiver()));
        }
        Optional<RuntimeValue> methodValue = object.get().scope().resolve(ast.name(), false);
        if (methodValue.isEmpty()) {
            // Check prototype chain for inherited methods
            Optional<RuntimeValue> prototypeValue = object.get().scope().resolve("prototype", false);
            if (prototypeValue.isPresent()) {
                Optional<RuntimeValue.ObjectValue> prototype = requireType(prototypeValue.get(), RuntimeValue.ObjectValue.class);
                if (prototype.isPresent()) {
                    methodValue = prototype.get().scope().resolve(ast.name(), false);
                }
            }
            if (methodValue.isEmpty()) {
                throw new EvaluateException("Undefined method: " + ast.name(), Optional.of(ast));
            }
        }
        Optional<RuntimeValue.Function> method = requireType(methodValue.get(), RuntimeValue.Function.class);
        if (method.isEmpty()) {
            throw new EvaluateException("Not a method: " + ast.name(), Optional.of(ast));
        }
        java.util.List<RuntimeValue> arguments = new ArrayList<>();
        arguments.add(receiverValue);
        for (Ast.Expr arg : ast.arguments()) {
            arguments.add(visit(arg));
        }
        return method.get().definition().invoke(arguments);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        Scope objectScope = new Scope(scope);
        Scope previousScope = scope;
        try {
            scope = objectScope;
            for (Ast.Stmt field : ast.fields()) {
                if (field instanceof Ast.Stmt.Let letStmt) {
                    RuntimeValue value = letStmt.value().isPresent() ? visit(letStmt.value().get()) : new RuntimeValue.Primitive(null);
                    try {
                        scope.define(letStmt.name(), value);
                    } catch (IllegalStateException e) {
                        throw new EvaluateException("Field " + letStmt.name() + " is already defined.", Optional.of(letStmt));
                    }
                }
            }
            for (Ast.Stmt method : ast.methods()) {
                if (method instanceof Ast.Stmt.Def defStmt) {
                    Scope methodDefinitionScope = scope;
                    RuntimeValue.Function methodFunc = new RuntimeValue.Function(defStmt.name(), (arguments) -> {
                        if (arguments.size() != defStmt.parameters().size() + 1) {
                            throw new EvaluateException("Method " + defStmt.name() + " expected " + defStmt.parameters().size() + " arguments but got " + (arguments.size() - 1) + ".", Optional.of(defStmt));
                        }
                        for (int i = 0; i < defStmt.parameters().size(); i++) {
                            if (defStmt.parameters().get(i).equals("this")) {
                                throw new EvaluateException("Parameter cannot be named 'this'.", Optional.of(defStmt));
                            }
                            for (int j = i + 1; j < defStmt.parameters().size(); j++) {
                                if (defStmt.parameters().get(i).equals(defStmt.parameters().get(j))) {
                                    throw new EvaluateException("Duplicate parameter name: " + defStmt.parameters().get(i), Optional.of(defStmt));
                                }
                            }
                        }
                        Scope methodPreviousScope = scope;
                        try {
                            scope = new Scope(methodDefinitionScope);
                            scope.define("this", arguments.get(0));
                            for (int i = 0; i < defStmt.parameters().size(); i++) {
                                scope.define(defStmt.parameters().get(i), arguments.get(i + 1));
                            }
                            scope = new Scope(scope);
                            try {
                                for (Ast.Stmt bodyStmt : defStmt.body()) {
                                    visit(bodyStmt);
                                }
                                return new RuntimeValue.Primitive(null);
                            } catch (ReturnException returnException) {
                                return returnException.value;
                            }
                        } finally {
                            scope = methodPreviousScope;
                        }
                    });
                    try {
                        scope.define(defStmt.name(), methodFunc);
                    } catch (IllegalStateException e) {
                        throw new EvaluateException("Method " + defStmt.name() + " is already defined.", Optional.of(defStmt));
                    }
                }
            }
            return new RuntimeValue.ObjectValue(ast.name(), objectScope);
        } finally {
            scope = previousScope;
        }
    }

    private static <T> Optional<T> requireType(RuntimeValue value, Class<T> type) {
        Optional<Object> unwrapped = RuntimeValue.class.isAssignableFrom(type)
            ? Optional.of(value)
            : requireType(value, RuntimeValue.Primitive.class).map(RuntimeValue.Primitive::value);
        return (Optional<T>) unwrapped.filter(type::isInstance);
    }

    private static class ReturnException extends RuntimeException {
        final RuntimeValue value;
        ReturnException(RuntimeValue value) {
            this.value = value;
        }
    }
}