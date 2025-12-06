package plc.project.generator;

import plc.project.analyzer.Ir;
import plc.project.analyzer.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

public final class Generator implements Ir.Visitor<StringBuilder, RuntimeException> {

    private final StringBuilder builder = new StringBuilder();
    private int indent = 0;

    private void newline(int indent) {
        builder.append("\n");
        builder.append("    ".repeat(indent));
    }

    public StringBuilder visit(Ir ir) {
        return switch (ir) {
            case Ir.Source source -> visit(source);
            case Ir.Stmt.Let stmt -> visit(stmt);
            case Ir.Stmt.Def stmt -> visit(stmt);
            case Ir.Stmt.If stmt -> visit(stmt);
            case Ir.Stmt.For stmt -> visit(stmt);
            case Ir.Stmt.Return stmt -> visit(stmt);
            case Ir.Stmt.Expression stmt -> visit(stmt);
            case Ir.Stmt.Assignment.Variable stmt -> visit(stmt);
            case Ir.Stmt.Assignment.Property stmt -> visit(stmt);
            case Ir.Expr.Literal expr -> visit(expr);
            case Ir.Expr.Group expr -> visit(expr);
            case Ir.Expr.Binary expr -> visit(expr);
            case Ir.Expr.Variable expr -> visit(expr);
            case Ir.Expr.Property expr -> visit(expr);
            case Ir.Expr.Function expr -> visit(expr);
            case Ir.Expr.Method expr -> visit(expr);
            case Ir.Expr.ObjectExpr expr -> visit(expr);
        };
    }


    @Override
    public StringBuilder visit(Ir.Source ir) {
        builder.append(Environment.imports()).append("\n\n");
        builder.append("public final class Main {").append("\n\n");
        builder.append(Environment.definitions()).append("\n");
        //Java doesn't allow for nested functions, but we will pretend it does.
        //To support simple programs involving functions, we will "hoist" any
        //variable/function declaration at the start of the program to allow
        //these functions to be used as valid Java.
        indent = 1;
        boolean main = false;
        for (var statement : ir.statements()) {
            newline(indent);
            if (!main) {
                if (statement instanceof Ir.Stmt.Let || statement instanceof Ir.Stmt.Def) {
                    builder.append("static ");
                } else {
                    builder.append("public static void main(String[] args) {");
                    main = true;
                    indent = 2;
                    newline(indent);
                }
            }
            visit(statement);
        }
        if (main) {
            builder.append("\n").append("    }");
        }
        indent = 0;
        builder.append("\n\n").append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Let ir) {
        String type = ir.type().toString().equals("Object") ? "var" : getJavaType(ir.type());
        builder.append(type).append(" ").append(ir.name());
        if (ir.value().isPresent()) {
            builder.append(" = ");
            visit(ir.value().get());
        }
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Def ir) {
        builder.append(getJavaType(ir.returns())).append(" ").append(ir.name()).append("(");
        for (int i = 0; i < ir.parameters().size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            var param = ir.parameters().get(i);
            builder.append(getJavaType(param.type())).append(" ").append(param.name());
        }
        builder.append(") {");
        int prevIndent = indent;
        indent++;
        for (var statement : ir.body()) {
            newline(indent);
            visit(statement);
        }
        indent = prevIndent;
        newline(indent);
        builder.append("}");
        return builder;
    }

    private String getJavaType(Type type) {
    if (type instanceof Type.Primitive prim) {
        return switch (prim.name()) {
            case "Nil" -> "Void";
            case "Any" -> "Object";
            case "Dynamic" -> "Object";
            case "Boolean" -> "Boolean";
            case "Integer" -> "BigInteger";
            case "Decimal" -> "BigDecimal";
            case "Character" -> "Character";
            case "String" -> "String";
            case "Equatable" -> "Comparable";
            case "Comparable" -> "Comparable";
            case "Iterable" -> "Iterable<BigInteger>";
            default -> throw new AssertionError("Unknown type: " + prim.name());
        };
    } else if (type instanceof Type.Function) {
        throw new AssertionError("Function types should not appear in generated code");
    } else {
        // ObjectType
        return "Object";
    }
}

    @Override
    public StringBuilder visit(Ir.Stmt.If ir) {
        builder.append("if (");
        visit(ir.condition());
        builder.append(") {");
        int prevIndent = indent;
        indent++;
        for (var statement : ir.thenBody()) {
            newline(indent);
            visit(statement);
        }
        indent = prevIndent;
        newline(indent);
        builder.append("}");
        if (!ir.elseBody().isEmpty()) {
            builder.append(" else {");
            indent++;
            for (var statement : ir.elseBody()) {
                newline(indent);
                visit(statement);
            }
            indent = prevIndent;
            newline(indent);
            builder.append("}");
        }
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.For ir) {
        builder.append("for (");
        builder.append(getJavaType(ir.type())).append(" ").append(ir.name()).append(" : ");
        visit(ir.expression());
        builder.append(") {");
        int prevIndent = indent;
        indent++;
        for (var statement : ir.body()) {
            newline(indent);
            visit(statement);
        }
        indent = prevIndent;
        newline(indent);
        builder.append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Return ir) {
        builder.append("return ");
        if (ir.value().isPresent()) {
            visit(ir.value().get());
        } else {
            builder.append("null");
        }
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Expression ir) {
        visit(ir.expression());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Variable ir) {
        visit(ir.variable());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Property ir) {
        visit(ir.property());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Literal ir) {
        var literal = switch (ir.value()) {
            case null -> "null";
            case Boolean b -> b.toString();
            case BigInteger i -> "new BigInteger(\"" + i + "\")";
            case BigDecimal d -> "new BigDecimal(\"" + d + "\")";
            case Character c -> "\'" + c + "\'";
            case String s -> "\"" + s + "\"";
            default -> throw new AssertionError(ir.value());
        };
        return builder.append(literal);
    }

    @Override
    public StringBuilder visit(Ir.Expr.Group ir) {
        builder.append("(");
        visit(ir.expression());
        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Binary ir) {
        switch (ir.operator()) {
            case "+":
                if (ir.type().equals(Type.STRING) ||
                    (ir.type() instanceof Type.Primitive prim && prim.name().equals("String"))) {
                    visit(ir.left());
                    builder.append(" + ");
                    visit(ir.right());
                } else {
                    builder.append("(");
                    visit(ir.left());
                    builder.append(").add(");
                    visit(ir.right());
                    builder.append(")");
                }
                break;
            case "-":
                builder.append("(");
                visit(ir.left());
                builder.append(").subtract(");
                visit(ir.right());
                builder.append(")");
                break;
            case "*":
                builder.append("(");
                visit(ir.left());
                builder.append(").multiply(");
                visit(ir.right());
                builder.append(")");
                break;
            case "/":
                builder.append("(");
                visit(ir.left());
                builder.append(").divide(");
                visit(ir.right());
                if (ir.type().toString().equals("Integer")) {
                    builder.append(")");
                } else {
                    builder.append(", RoundingMode.HALF_EVEN)");
                }
                break;
            case "<":
            case "<=":
            case ">":
            case ">=":
                builder.append("(");
                visit(ir.left());
                builder.append(").compareTo(");
                visit(ir.right());
                builder.append(") ").append(ir.operator()).append(" 0");
                break;
            case "==":
                builder.append("Objects.equals(");
                visit(ir.left());
                builder.append(", ");
                visit(ir.right());
                builder.append(")");
                break;
            case "!=":
                builder.append("!Objects.equals(");
                visit(ir.left());
                builder.append(", ");
                visit(ir.right());
                builder.append(")");
                break;
            case "AND":
                if (ir.left() instanceof Ir.Expr.Binary binary && binary.operator().equals("OR")) {
                    builder.append("(");
                    visit(ir.left());
                    builder.append(")");
                } else {
                    visit(ir.left());
                }
                builder.append(" && ");
                visit(ir.right());
                break;
            case "OR":
                visit(ir.left());
                builder.append(" || ");
                visit(ir.right());
                break;
        }
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Variable ir) {
        builder.append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Property ir) {
        visit(ir.receiver());
        builder.append(".").append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Function ir) {
        builder.append(ir.name()).append("(");
        for (int i = 0; i < ir.arguments().size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            visit(ir.arguments().get(i));
        }
        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Method ir) {
        visit(ir.receiver());
        builder.append(".").append(ir.name()).append("(");
        for (int i = 0; i < ir.arguments().size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            visit(ir.arguments().get(i));
        }
        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.ObjectExpr ir) {
        builder.append("new Object() {");
        int prevIndent = indent;
        indent++;
        for (var field : ir.fields()) {
            newline(indent);
            visit(field);
        }
        for (var method : ir.methods()) {
            newline(indent);
            visit(method);
        }
        indent = prevIndent;
        newline(indent);
        builder.append("}");
        return builder;
    }

}
