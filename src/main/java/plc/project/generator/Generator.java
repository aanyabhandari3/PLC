package plc.project.generator;

import plc.project.analyzer.Ir;

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
        String type = ir.type().toString().equals("Object") ? "var" : ir.type().toString();
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
        builder.append(ir.returnType()).append(" ").append(ir.name()).append("(");
        for (int i = 0; i < ir.parameters().size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            var param = ir.parameters().get(i);
            builder.append(param.type()).append(" ").append(param.name());
        }
        builder.append(") {");
        int prevIndent = indent;
        indent++;
        for (var statement : ir.statements()) {
            newline(indent);
            visit(statement);
        }
        indent = prevIndent;
        newline(indent);
        builder.append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.If ir) {
        builder.append("if (");
        visit(ir.condition());
        builder.append(") {");
        int prevIndent = indent;
        indent++;
        for (var statement : ir.then()) {
            newline(indent);
            visit(statement);
        }
        indent = prevIndent;
        newline(indent);
        builder.append("}");
        if (!ir.otherwise().isEmpty()) {
            builder.append(" else {");
            indent++;
            for (var statement : ir.otherwise()) {
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
        builder.append(ir.type()).append(" ").append(ir.name()).append(" : ");
        visit(ir.expression());
        builder.append(") {");
        int prevIndent = indent;
        indent++;
        for (var statement : ir.statements()) {
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
        builder.append(ir.variable()).append(" = ");
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
                if (ir.type().toString().equals("String")) {
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
