package plc.project.analyzer;

import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;
import java.util.HashSet;

public final class Analyzer implements Ast.Visitor<Ir, AnalyzeException> {

    private Scope scope;
    private Type currentReturnType = null;
    private Type.ObjectType currentObject = null;

    public Analyzer(Scope scope) {
        this.scope = scope;
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Ir.Source visit(Ast.Source ast) throws AnalyzeException {
        var statements = new ArrayList<Ir.Stmt>();
        for (var statement : ast.statements()) {
            statements.add(visit(statement));
        }
        return new Ir.Source(statements);
    }

    private Ir.Stmt visit(Ast.Stmt ast) throws AnalyzeException {
        return (Ir.Stmt) visit((Ast) ast);
    }

    @Override
//    core
    public Ir.Stmt.Let visit(Ast.Stmt.Let ast) throws AnalyzeException {
        Type type;
        if (ast.type().isPresent()) {
            var typeName = ast.type().get();
            var finishedType = scope.resolve(typeName, false);
            if (finishedType.isEmpty()) {
                throw new AnalyzeException("Undefined: " + typeName, Optional.of(ast));
            }
            type = finishedType.get();
        } else if (ast.value().isPresent()) {
            var val = visit(ast.value().get());
            type = val.type();
        } else {
            type = Type.DYNAMIC;
        }

        if (ast.value().isPresent()) {
            var val = visit(ast.value().get());
            if (!val.type().isSubtypeOf(type)) {
                throw new  AnalyzeException("Type mismatch. Expected: " + type + ", got " + val.type(), Optional.of(ast));
            }
            try {
                scope.define(ast.name(),  type);
            } catch (IllegalStateException e) {
                throw new AnalyzeException("Variable " + ast.name() + " is already defined", Optional.of(ast));
            }
            return new Ir.Stmt.Let(ast.name(), type, Optional.of(val));

        } else {
            try {
                scope.define(ast.name(), type);
            } catch (IllegalStateException e) {
                throw new AnalyzeException("Variable " + ast.name() + " is already defined", Optional.of(ast));
            }
            return new Ir.Stmt.Let(ast.name(), type, Optional.empty());
        }
    }

    @Override
    public Ir.Stmt.Def visit(Ast.Stmt.Def ast) throws AnalyzeException {
        Type returnType = Type.DYNAMIC;
        if (ast.returnType().isPresent()) {
            var retTypeName = ast.returnType().get();
            var finishedRetType = scope.resolve(retTypeName, false);
            if (finishedRetType.isEmpty()) {
                throw new AnalyzeException("Undefined: " + retTypeName, Optional.of(ast));
            }
            returnType = finishedRetType.get();
        }

        var params = new ArrayList<Ir.Stmt.Def.Parameter>();
        var paramTypes = new ArrayList<Type>();
        var paramNames = new HashSet<String>();

        for (int i = 0; i < ast.parameters().size(); i++) {
            var paramName = ast.parameters().get(i);

            // Check for duplicate parameters
            if (!paramNames.add(paramName)) {
                throw new AnalyzeException("Duplicate parameter: " + paramName, Optional.of(ast));
            }

            Type paramType = Type.DYNAMIC;

            if (ast.parameterTypes().get(i).isPresent()) {
                var paramTypeName = ast.parameterTypes().get(i).get();
                var finishedParamType = scope.resolve(paramTypeName, false);
                if (finishedParamType.isEmpty()) {
                    throw new AnalyzeException("Undefined: " + paramTypeName, Optional.of(ast));
                }
                paramType = finishedParamType.get();
            }
            params.add(new Ir.Stmt.Def.Parameter(paramName, paramType));
            paramTypes.add(paramType);
        }

        var funcType = new Type.Function(paramTypes, returnType);
        try {
            scope.define(ast.name(), funcType);
        } catch (IllegalStateException e) {
            throw new AnalyzeException("Function " + ast.name() + " is already defined", Optional.of(ast));
        }

        var org = scope;
        scope = new Scope(scope);

        // Define 'this' if we're in an object context
        if (currentObject != null) {
            scope.define("this", currentObject);
        }

        for (var param : params) {
            try {
                scope.define(param.name(), param.type());
            } catch (IllegalStateException e) {
                throw new AnalyzeException("Duplicate parameter: " + param.name(), Optional.of(ast));
            }
        }

        var prevRetType = currentReturnType;
        currentReturnType = returnType;

        var body = new ArrayList<Ir.Stmt>();
        for (var stmt : ast.body()) {
            body.add(visit(stmt));
        }

        currentReturnType = prevRetType;
        scope = org;
        return new Ir.Stmt.Def(ast.name(), params, returnType, body);
    }

    @Override
//    core
    public Ir.Stmt.If visit(Ast.Stmt.If ast) throws AnalyzeException {
        var condition = visit(ast.condition());
        if (!condition.type().equals(Type.BOOLEAN) && !condition.type().equals(Type.DYNAMIC)){
            throw new  AnalyzeException("Condition mismatch. expected boolean, got: " + condition.type(), Optional.of(ast));
        }

        var org = scope;
        scope = new Scope(scope);
        var arr_then = new ArrayList<Ir.Stmt>();
        for (var stmt : ast.thenBody()) {
            arr_then.add(visit(stmt));
        }

        scope = org;
        scope = new Scope(scope);
        var arr_else = new ArrayList<Ir.Stmt>();
        for (var stmt : ast.elseBody()) {
            arr_else.add(visit(stmt));
        }

        scope = org;
        return new Ir.Stmt.If(condition, arr_then, arr_else);
    }

    @Override
    public Ir.Stmt.For visit(Ast.Stmt.For ast) throws AnalyzeException {
        var iter = visit(ast.expression());
        if (!iter.type().isSubtypeOf(Type.ITERABLE) && !iter.type().equals(Type.DYNAMIC)) {
            throw new AnalyzeException("Type mismatch. Expected: Iterable, got: " + iter.type(), Optional.of(ast));
        }

        var org = scope;
        scope = new Scope(scope);

        Type elType = Type.INTEGER;
        // For Iterable<Integer> types, extract Integer as the element type
        if (iter.type() instanceof Type.Primitive prim && prim.name().equals("Iterable")) {
            elType = Type.INTEGER;
        } else if (iter.type().equals(Type.DYNAMIC)) {
            elType = Type.DYNAMIC;
        }

        scope.define(ast.name(), elType);

        var body = new ArrayList<Ir.Stmt>();
        for (var stmt : ast.body()) {
            body.add(visit(stmt));
        }

        scope = org;
        return new Ir.Stmt.For(ast.name(), elType, iter, body);
    }

    @Override
    public Ir.Stmt.Return visit(Ast.Stmt.Return ast) throws AnalyzeException {
        if (currentReturnType == null) {
            throw new AnalyzeException("return statement not in a function", Optional.of(ast));
        }

        if (ast.value().isPresent()) {
            var val = visit(ast.value().get());
            if (!val.type().isSubtypeOf(currentReturnType)) {
                throw new AnalyzeException("Type mismatch. Expected: " + currentReturnType + ", got: " + val.type(), Optional.of(ast));
            }
            return new Ir.Stmt.Return(Optional.of(val));
        } else {
            if (!Type.NIL.isSubtypeOf(currentReturnType)) {
                throw new AnalyzeException("Type mismatch. Expected: " + currentReturnType + ", got: nil", Optional.of(ast));
            }
            return new Ir.Stmt.Return(Optional.empty());
        }
    }

    @Override
    public Ir.Stmt.Expression visit(Ast.Stmt.Expression ast) throws AnalyzeException {
        var expression = visit(ast.expression());
        return new Ir.Stmt.Expression(expression);
    }

    @Override
//    core
    public Ir.Stmt.Assignment visit(Ast.Stmt.Assignment ast) throws AnalyzeException {
        if  (ast.expression() instanceof Ast.Expr.Variable variable) {
            var finType = scope.resolve(variable.name(),  false);
            if (finType.isEmpty()) {
                throw new  AnalyzeException("Undefined variable: " + variable.name(), Optional.of(ast));
            }

            var val = visit(ast.value());
            if (!val.type().isSubtypeOf(finType.get())) {
                throw new AnalyzeException("Type mismatch", Optional.of(ast));
            }

            var varExpr = new Ir.Expr.Variable(variable.name(), finType.get());
            return new Ir.Stmt.Assignment.Variable(varExpr, val);
        } else if (ast.expression() instanceof Ast.Expr.Property property) {
            var reciever = visit(property.receiver());
            var propType = resolveProperty(reciever, property.name());
            var val = visit(ast.value());
            if (!val.type().isSubtypeOf(propType)) {
                throw new AnalyzeException("Type mismatch", Optional.of(ast));
            }

            var propExpr = new Ir.Expr.Property(reciever, property.name(), propType);
            return new Ir.Stmt.Assignment.Property(propExpr, val);
        } else {
            throw new AnalyzeException("invalid assignment target", Optional.of(ast));
        }
    }

    private Ir.Expr visit(Ast.Expr ast) throws AnalyzeException {
        return (Ir.Expr) visit((Ast) ast); //helper to cast visit(Ast.Expr) to Ir.Expr
    }

    @Override
    public Ir.Expr.Literal visit(Ast.Expr.Literal ast) throws AnalyzeException {
        var type = switch (ast.value()) {
            case null -> Type.NIL;
            case Boolean _ -> Type.BOOLEAN;
            case BigInteger _ -> Type.INTEGER;
            case BigDecimal _ -> Type.DECIMAL;
            case Character _ -> Type.CHARACTER;
            case String _ -> Type.STRING;
            default -> throw new AssertionError(ast.value().getClass());
        };
        return new Ir.Expr.Literal(ast.value(), type);
    }

    @Override
//  core
    public Ir.Expr.Group visit(Ast.Expr.Group ast) throws AnalyzeException {
        var Expr = visit(ast.expression());
        return new Ir.Expr.Group(Expr);
    }

    @Override
//    core
    public Ir.Expr.Binary visit(Ast.Expr.Binary ast) throws AnalyzeException {
        var left = visit(ast.left());
        var right = visit(ast.right());
        var op = ast.operator();
        Type resultType;

        if (op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/")) {
            // Both DYNAMIC
            if (left.type().equals(Type.DYNAMIC) && right.type().equals(Type.DYNAMIC)) {
                resultType = Type.DYNAMIC;
            }
            // Both INTEGER
            else if (left.type().equals(Type.INTEGER) && right.type().equals(Type.INTEGER)) {
                resultType = Type.INTEGER;
            }
            // Both DECIMAL
            else if (left.type().equals(Type.DECIMAL) && right.type().equals(Type.DECIMAL)) {
                resultType = Type.DECIMAL;
            }
            // STRING concatenation: left is STRING
            else if (op.equals("+") && left.type().equals(Type.STRING) &&
                     (right.type().equals(Type.STRING) || right.type().equals(Type.DYNAMIC))) {
                resultType = Type.STRING;
            }
            // STRING concatenation: right is STRING
            else if (op.equals("+") && right.type().equals(Type.STRING) &&
                     (left.type().equals(Type.STRING) || left.type().equals(Type.DYNAMIC))) {
                resultType = Type.STRING;
            }
            else if (op.equals("+") && left.type().equals(Type.INTEGER) && right.type().equals(Type.STRING)) {
                resultType = Type.STRING;
            }
            // DECIMAL + STRING => STRING (FIX #1)
            else if (op.equals("+") && left.type().equals(Type.DECIMAL) && right.type().equals(Type.STRING)) {
                resultType = Type.STRING;
            }
            // STRING + INTEGER => STRING (FIX #1)
            else if (op.equals("+") && left.type().equals(Type.STRING) && right.type().equals(Type.INTEGER)) {
                resultType = Type.STRING;
            }
            // STRING + DECIMAL => STRING (FIX #1)
            else if (op.equals("+") && left.type().equals(Type.STRING) && right.type().equals(Type.DECIMAL)) {
                resultType = Type.STRING;
            }
            // One side is DYNAMIC - infer from the other
            else if (left.type().equals(Type.DYNAMIC)) {
                if (right.type().equals(Type.INTEGER)) {
                    resultType = Type.INTEGER;
                } else if (right.type().equals(Type.DECIMAL)) {
                    resultType = Type.DECIMAL;
                } else if (op.equals("+") && right.type().equals(Type.STRING)) {
                    resultType = Type.STRING;
                } else {
                    resultType = Type.DYNAMIC;
                }
            }
            else if (right.type().equals(Type.DYNAMIC)) {
                if (left.type().equals(Type.INTEGER)) {
                    resultType = Type.INTEGER;
                } else if (left.type().equals(Type.DECIMAL)) {
                    resultType = Type.DECIMAL;
                } else if (op.equals("+") && left.type().equals(Type.STRING)) {
                    resultType = Type.STRING;
                } else {
                    resultType = Type.DYNAMIC;
                }
            }
            else {
                throw new AnalyzeException("invalid operand types", Optional.of(ast));
            }
        } else if (op.equals("&&") || op.equals("||") || op.equals("AND") || op.equals("OR")) {
            if (!left.type().equals(Type.BOOLEAN) && !left.type().equals(Type.DYNAMIC)) {
                throw new AnalyzeException("left operand must be boolean", Optional.of(ast));
            }
            if (!right.type().equals(Type.BOOLEAN) && !right.type().equals(Type.DYNAMIC)) {
                throw new AnalyzeException("right operand must be boolean", Optional.of(ast));
            }
            resultType = Type.BOOLEAN;
        } else if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
            if (!left.type().isSubtypeOf(Type.COMPARABLE) && !left.type().equals(Type.DYNAMIC)) {
                throw new AnalyzeException("left operand must be comparable", Optional.of(ast));
            }
            if (!right.type().isSubtypeOf(Type.COMPARABLE) && !right.type().equals(Type.DYNAMIC)) {
                throw new AnalyzeException("right operand must be comparable", Optional.of(ast));
            }
            if (!left.type().equals(right.type()) &&
                !left.type().equals(Type.DYNAMIC) && !right.type().equals(Type.DYNAMIC)) {
                throw new AnalyzeException("operands must have same type for " + op, Optional.of(ast));
            }
            resultType = Type.BOOLEAN;
        } else if (op.equals("==") || op.equals("!=")) {
            // FIX #2: Allow comparison if either side is DYNAMIC
            if (left.type().equals(Type.DYNAMIC) || right.type().equals(Type.DYNAMIC)) {
                resultType = Type.BOOLEAN;
            } else if (!left.type().isSubtypeOf(right.type()) && !right.type().isSubtypeOf(left.type())) {
                throw new AnalyzeException("operands must have compatible types for " + op, Optional.of(ast));
            } else {
                resultType = Type.BOOLEAN;
            }
        }
        else {
            throw new AnalyzeException("unknown operator: " + op, Optional.of(ast));
        }

        return new Ir.Expr.Binary(op, left, right, resultType);
    }

    @Override
//    core
    public Ir.Expr.Variable visit(Ast.Expr.Variable ast) throws AnalyzeException {
        var finType =  scope.resolve(ast.name(),  false);
        if (finType.isEmpty()) {
            throw new AnalyzeException("Undefined variable: " + ast.name(), Optional.of(ast));
        }

        return new  Ir.Expr.Variable(ast.name(), finType.get());
    }

    @Override
    public Ir.Expr.Property visit(Ast.Expr.Property ast) throws AnalyzeException {
        var receiver = visit(ast.receiver());
        var propType = resolveProperty(receiver, ast.name());
        return new Ir.Expr.Property(receiver, ast.name(), propType);
    }

    private Type resolveProperty(Ir.Expr receiver, String propertyName) throws AnalyzeException {
        if (receiver.type() instanceof Type.ObjectType objectType) {
            var fin = objectType.scope().resolve(propertyName, false);
            if (fin.isEmpty()) {
                // Try prototype chain
                Type protoType = resolveFromPrototypeChain(objectType, propertyName);
                if (protoType != null) {
                    return protoType;
                }
                throw new AnalyzeException("Undefined property: " + propertyName + " for type: " + receiver.type(), Optional.empty());
            }
            return fin.get();
        } else if (receiver.type().equals(Type.DYNAMIC)){
            return Type.DYNAMIC;
        } else {
            throw new AnalyzeException("Type " + receiver.type() + " has no properties", Optional.empty());
        }
    }

    private Type resolveFromPrototypeChain(Type.ObjectType objectType, String name) {
        var prototypeOpt = objectType.scope().resolve("prototype", false);
        if (prototypeOpt.isPresent()) {
            var protoType = prototypeOpt.get();
            if (protoType instanceof Type.ObjectType prototypeObj) {
                var result = prototypeObj.scope().resolve(name, false);
                if (result.isPresent()) {
                    return result.get();
                }
                // Recursively check prototype chain
                return resolveFromPrototypeChain(prototypeObj, name);
            } else if (protoType.equals(Type.DYNAMIC)) {
                return Type.DYNAMIC;
            }
        }
        return null;
    }

    @Override
//    core
    public Ir.Expr.Function visit(Ast.Expr.Function ast) throws AnalyzeException {
        var finType =  scope.resolve(ast.name(),  false);
        if (finType.isEmpty()) {
            throw new  AnalyzeException("Undefined function: " + ast.name(), Optional.of(ast));
        }

        if (!(finType.get() instanceof Type.Function functionType)) {
            throw new AnalyzeException("function does not exist", Optional.of(ast));
        }

        if (ast.arguments().size() != functionType.parameters().size()) {
            throw new AnalyzeException("function " + ast.name() + " does not have " + functionType.parameters().size() + " arguments", Optional.of(ast));
        }

        var args = new  ArrayList<Ir.Expr>();
        for (int i = 0; i < ast.arguments().size(); i++) {
            var arg = visit(ast.arguments().get(i));
            var paramType = functionType.parameters().get(i);
            if (!arg.type().isSubtypeOf(paramType)) {
                throw new AnalyzeException("Argument " + i + " is not type " + paramType, Optional.of(ast));
            }
            args.add(arg);
        }
        return new Ir.Expr.Function(ast.name(), args, functionType.returns());
    }

    @Override
    public Ir.Expr.Method visit(Ast.Expr.Method ast) throws AnalyzeException {
        var receiver = visit(ast.receiver());
        Type methodType = Type.DYNAMIC;

        if (receiver.type() instanceof Type.ObjectType objectType) {
            var fin = objectType.scope().resolve(ast.name(), false);
            if (fin.isEmpty()) {
                // Try prototype chain
                Type protoType = resolveFromPrototypeChain(objectType, ast.name());
                if (protoType != null) {
                    methodType = protoType;
                } else {
                    throw new AnalyzeException("Undefined method: " + ast.name() + " for type: " + receiver.type(), Optional.of(ast));
                }
            } else {
                methodType = fin.get();
            }
        } else if (!receiver.type().equals(Type.DYNAMIC)) {
            throw new AnalyzeException("Type " + receiver.type() + " has no methods", Optional.of(ast));
        }

        if (methodType.equals(Type.DYNAMIC)){
            var args = new ArrayList<Ir.Expr>();
            for (var arg : ast.arguments()) {
                args.add(visit(arg));
            }
            return new Ir.Expr.Method(receiver, ast.name(), args, Type.DYNAMIC);
        }

        if (!(methodType instanceof Type.Function functionType)) {
            throw new AnalyzeException("method does not exist", Optional.of(ast));
        }

        if (ast.arguments().size() != functionType.parameters().size()) {
            throw new AnalyzeException("method " + ast.name() + " does not have " + functionType.parameters().size() + " arguments", Optional.of(ast));
        }

        var args = new ArrayList<Ir.Expr>();
        for (int i = 0; i < ast.arguments().size(); i++) {
            var arg = visit(ast.arguments().get(i));
            var paramType = functionType.parameters().get(i);
            if (!arg.type().isSubtypeOf(paramType)) {
                throw new AnalyzeException("Argument" + i + "is not type" + paramType, Optional.of(ast));
            }
            args.add(arg);
        }

        return new Ir.Expr.Method(receiver, ast.name(), args, functionType.returns());
    }

    @Override
    public Ir.Expr.ObjectExpr visit(Ast.Expr.ObjectExpr ast) throws AnalyzeException {
        var objScope = new Scope(null);

        var fields = new ArrayList<Ir.Stmt.Let>();
        for (var field : ast.fields()) {
            var scan = visit(field);
            fields.add(scan);
            objScope.define(field.name(), scan.type());
        }

        var org = scope;
        scope = objScope;

        var methods = new ArrayList<Ir.Stmt.Def>();
        for (var method : ast.methods()) {
            var scan = visit(method);
            methods.add(scan);
        }

        scope = org;

        Type obType = new Type.ObjectType(ast.name(), objScope);
        return new Ir.Expr.ObjectExpr(ast.name(), fields, methods, obType);

    }

}
