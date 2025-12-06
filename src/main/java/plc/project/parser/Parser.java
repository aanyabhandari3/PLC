package plc.project.parser;

import com.google.common.base.Preconditions;
import plc.project.lexer.Token;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This style of parser is called <em>recursive descent</em>. Each rule in our
 * grammar has dedicated function, and references to other rules correspond to
 * calling that function. Recursive rules are therefore supported by actual
 * recursive calls, while operator precedence is encoded via the grammar.
 *
 * <p>The parser has a similar architecture to the lexer, just with
 * {@link Token}s instead of characters. As before, {@link TokenStream#peek} and
 * {@link TokenStream#match} help with traversing the token stream. Instead of
 * emitting tokens, you will instead need to extract the literal value via
 * {@link TokenStream#get} to be added to the relevant AST.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public Ast parse(String rule) throws ParseException {
        var ast = switch (rule) {
            case "source" -> parseSource();
            case "stmt" -> parseStmt();
            case "expr" -> parseExpr();
            default -> throw new AssertionError(rule);
        };
        if (tokens.has(0)) {
            throw new ParseException("Expected end of input.", tokens.getNext());
        }
        return ast;
    }

    private Ast.Source parseSource() throws ParseException {
        var statements = new ArrayList<Ast.Stmt>();
        while (tokens.has(0)) {
            statements.add(parseStmt());
        }
        return new Ast.Source(statements);
    }

    private Ast.Stmt parseStmt() throws ParseException {
        if (tokens.peek("LET")) {
            return parseLetStmt();
        } else if (tokens.peek("DEF")) {
            return parseDefStmt();
        } else if (tokens.peek("IF")) {
            return parseIfStmt();
        } else if (tokens.peek("FOR")) {
            return parseForStmt();
        } else if  (tokens.peek("RETURN")) {
            if (tokens.peek("RETURN", "IF") && tokens.has(2) && !tokens.peek("RETURN", "IF", ";")) {
                tokens.match("RETURN", "IF");
                Ast.Expr cond = parseExpr();
                if (!tokens.match(";")) {
                    throw new ParseException("missing ;", tokens.getNext());
                }
                return new Ast.Stmt.If(cond,
                        List.of(new Ast.Stmt.Return(Optional.empty())),
                        List.of());
            }
            return parseReturnStmt();
        } else {
            return parseExpressionOrAssignmentStmt();
        }
    }

    private Ast.Stmt parseLetStmt() throws ParseException {
        tokens.match("LET");

        if(!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("no identifier", tokens.getNext());
        }

        String check = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        Optional<Ast.Expr> check2 = Optional.empty();

        if (tokens.match("=")) {
            check2 = Optional.of(parseExpr());
        }

        if (!tokens.match(";")) {
            throw new  ParseException("missing ;", tokens.getNext());
        }

        return new  Ast.Stmt.Let(check, check2);
    }

    private Ast.Stmt parseDefStmt() throws ParseException {
        tokens.match("DEF");

        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("no identifier", tokens.getNext());
        }

        String check = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        if (!tokens.match("(")) {
            throw new ParseException("missing '(", tokens.getNext());
        }

        List<String> params = new ArrayList<>();
        List<Optional<String>> paramTypes = new ArrayList<>();

        if (!tokens.peek(")")) {
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("missing identifier", tokens.getNext());
            }
            params.add(tokens.get(0).literal());
            tokens.match(Token.Type.IDENTIFIER);

            // Check for type annotation
            if (tokens.match(":")) {
                if (!tokens.peek(Token.Type.IDENTIFIER)) {
                    throw new ParseException("missing type", tokens.getNext());
                }
                paramTypes.add(Optional.of(tokens.get(0).literal()));
                tokens.match(Token.Type.IDENTIFIER);
            } else {
                paramTypes.add(Optional.empty());
            }

            while (tokens.match(",")) {
                if (!tokens.peek(Token.Type.IDENTIFIER)) {
                    throw new ParseException("missing identifier", tokens.getNext());
                }
                params.add(tokens.get(0).literal());
                tokens.match(Token.Type.IDENTIFIER);

                // Check for type annotation
                if (tokens.match(":")) {
                    if (!tokens.peek(Token.Type.IDENTIFIER)) {
                        throw new ParseException("missing type", tokens.getNext());
                    }
                    paramTypes.add(Optional.of(tokens.get(0).literal()));
                    tokens.match(Token.Type.IDENTIFIER);
                } else {
                    paramTypes.add(Optional.empty());
                }
            }
        }

        if (!tokens.match(")")) {
            throw new ParseException("missing )", tokens.getNext());
        }

        // Check for return type annotation
        Optional<String> returnType = Optional.empty();
        if (tokens.match(":")) {
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("missing return type", tokens.getNext());
            }
            returnType = Optional.of(tokens.get(0).literal());
            tokens.match(Token.Type.IDENTIFIER);
        }

        if (!tokens.match("DO")) {
            throw new ParseException("missing DO", tokens.getNext());
        }

        List<Ast.Stmt> stmts = new ArrayList<>();

        while (!tokens.peek("END")) {
            if (!tokens.has(0)) {
                throw new ParseException("missing END", tokens.getNext());
            }
            stmts.add(parseStmt());
        }

        if (!tokens.match("END")) {
            throw new ParseException("missing END", tokens.getNext());
        }

        return new Ast.Stmt.Def(check, params, paramTypes, returnType, stmts);
    }

    private Ast.Stmt parseIfStmt() throws ParseException {
        tokens.match("IF");

        Ast.Expr check = parseExpr();

        if (!tokens.match("DO")) {
            throw new ParseException("missing DO", tokens.getNext());
        }

        List<Ast.Stmt> stmts = new ArrayList<>();

        while (!tokens.peek("ELSE") && !tokens.peek("END")) {
            if (!tokens.has(0)) {
                throw new ParseException("missing ELSE", tokens.getNext());
            }
            stmts.add(parseStmt());
        }

        List<Ast.Stmt> elseStmts = new ArrayList<>();

        if (tokens.match("ELSE")) {
            while (!tokens.peek("END")) {
                if (!tokens.has(0)) {
                    throw new ParseException("missing ELSE", tokens.getNext());
                }
                elseStmts.add(parseStmt());
            }
        }

        if  (!tokens.match("END")) {
            throw new ParseException("missing END", tokens.getNext());
        }

        return new Ast.Stmt.If(check, stmts, elseStmts);
    }

    private Ast.Stmt parseForStmt() throws ParseException {
        tokens.match("FOR");

        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("missing identifier", tokens.getNext());
        }

        String check = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        if (!tokens.match("IN")) {
            throw new ParseException("missing IN", tokens.getNext());
        }

        Ast.Expr check2 = parseExpr();

        if (!tokens.match("DO")) {
            throw new ParseException("missing DO", tokens.getNext());
        }

        List<Ast.Stmt> stmts = new ArrayList<>();

        while (!tokens.peek("END")) {
            if (!tokens.has(0)) {
                throw new ParseException("missing END", tokens.getNext());
            }
            stmts.add(parseStmt());
        }

        if (!tokens.match("END")) {
            throw new ParseException("missing END", tokens.getNext());
        }

        return new Ast.Stmt.For(check, check2, stmts);
    }

    private Ast.Stmt parseReturnStmt() throws ParseException {
        tokens.match("RETURN");

        Optional<Ast.Expr> expr = Optional.empty();
        if (!tokens.peek(";")) {
            expr = Optional.of(parseExpr());
        }

        if (!tokens.match(";")) {
            throw new ParseException("missing ;", tokens.getNext());
        }

        return new Ast.Stmt.Return(expr);
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        Ast.Expr expr = parseExpr();

        if (tokens.match("=")) {
            Ast.Expr left = parseExpr();

            if (!tokens.match(";")) {
                throw new ParseException("missing ';'", tokens.getNext());
            }

            return new Ast.Stmt.Assignment(expr, left);
        } else {
            if (!tokens.match(";")) {
                throw  new ParseException("missing ';'", tokens.getNext());
            }

            return new Ast.Stmt.Expression(expr);
        }
    }

    private Ast.Expr parseExpr() throws ParseException {
        return parseLogicalExpr();
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        Ast.Expr left = parseComparisonExpr();
        while (tokens.peek("AND") || tokens.peek("OR")) {
            String op = tokens.get(0).literal();
            tokens.match(op);
            Ast.Expr right = parseComparisonExpr();
            left = new Ast.Expr.Binary(op, left, right);
        }
        return left;
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        Ast.Expr left = parseAdditiveExpr();
        while (tokens.peek("<") ||  tokens.peek(">") || tokens.peek("<=") || tokens.peek(">=") || tokens.peek("==") || tokens.peek("!=")) {
            String op = tokens.get(0).literal();
            tokens.match(op);
            Ast.Expr right = parseAdditiveExpr();
            left = new Ast.Expr.Binary(op, left, right);
        }
        return left;
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        Ast.Expr left = parseMultiplicativeExpr();
         while (tokens.peek("+") || tokens.peek("-")) {
             String op = tokens.get(0).literal();
             tokens.match(op);
             Ast.Expr right = parseMultiplicativeExpr();
             left = new Ast.Expr.Binary(op, left, right);
         }
         return left;
    }

    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        Ast.Expr left = parseSecondaryExpr();
        while (tokens.peek("*") || tokens.peek("/") || tokens.peek("^")) {
            String op = tokens.get(0).literal();
            tokens.match(op);
            Ast.Expr right = parseSecondaryExpr();
            left = new Ast.Expr.Binary(op, left, right);
        }
        return left;
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        Ast. Expr expr = parsePrimaryExpr();
        while (tokens.peek(".")) {
            expr = parsePropertyOrMethod(expr);
        }
        return expr;
    }

    private Ast.Expr parsePropertyOrMethod(Ast.Expr receiver) throws ParseException {
        tokens.match(".");

        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("expected identifier", tokens.getNext());
        }

        String id =  tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        if (tokens.peek("(")) {
            tokens.match("(");
            List<Ast.Expr> argus = new ArrayList<>();

            if (!tokens.peek(")")) {
                argus.add(parseExpr());
                while (tokens.match(",")) {
                    argus.add(parseExpr());
                }
            }

            if (!tokens.match(")")) {
            throw new ParseException("expected end of input.", tokens.getNext());
            }

            return new Ast.Expr.Method(receiver, id, argus);
        } else {
            return new Ast.Expr.Property(receiver, id);
        }
    }

    private Ast.Expr parsePrimaryExpr() throws ParseException {
        if (tokens.peek("NIL") || tokens.peek("TRUE")
            || tokens.peek("FALSE") || (tokens.peek(Token.Type.INTEGER))
            || (tokens.peek(Token.Type.DECIMAL)) || (tokens.peek(Token.Type.CHARACTER))
            || (tokens.peek(Token.Type.STRING))) {
            return parseLiteralExpr();
        } else if (tokens.peek("(")) {
            return parseGroupExpr();
        } else if (tokens.peek("OBJECT")) {
            return parseObjectExpr();
        } else if (tokens.peek(Token.Type.IDENTIFIER)) {
            return parseVariableOrFunctionExpr();
        } else {
            throw new ParseException("no expression found.", tokens.getNext());
        }


    }

    private Ast.Expr parseLiteralExpr() throws ParseException {
        if (tokens.match("NIL")) {
            return new Ast.Expr.Literal(null);
        }
        else if (tokens.match("TRUE")) {
            return new Ast.Expr.Literal(Boolean.TRUE);
        }
        else if (tokens.match("FALSE")) {
            return new Ast.Expr.Literal(Boolean.FALSE);
        }
        else if (tokens.peek(Token.Type.INTEGER)) {
            String literal_int = tokens.get(0).literal();
            tokens.match(Token.Type.INTEGER);
            return new Ast.Expr.Literal(new BigInteger(literal_int));
        }
        else if (tokens.peek(Token.Type.DECIMAL)) {
            String literal_dec = tokens.get(0).literal();
            tokens.match(Token.Type.DECIMAL);
            return new Ast.Expr.Literal(new BigDecimal(literal_dec));
        }
        else if (tokens.peek(Token.Type.CHARACTER)) {
            String literal_char = tokens.get(0).literal();
            tokens.match(Token.Type.CHARACTER);
            String char_noq = literal_char.substring(1, literal_char.length() - 1);
            char_noq = char_noq
                .replace("\\\\", "\0")
                .replace("\\b", "\b")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\'", "\'")
                .replace("\\\"", "\"")
                .replace("\0", "\\");
            return new Ast.Expr.Literal(char_noq.charAt(0));
        }
        else if (tokens.peek(Token.Type.STRING)) {
            String literal_str  = tokens.get(0).literal();
            tokens.match(Token.Type.STRING);
            String str_noq = literal_str.substring(1, literal_str.length() - 1);
            str_noq = str_noq
                    .replace("\\\\", "\0")
                    .replace("\\b", "\b")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\r", "\r")
                    .replace("\\\'", "\'")
                    .replace("\\\"", "\"")
                    .replace("\0", "\\");
            return new Ast.Expr.Literal(str_noq);
        } else {
            throw new ParseException("not a literal", tokens.getNext());
        }
    }

    private Ast.Expr parseGroupExpr() throws ParseException {
        tokens.match("(");

        if (tokens.peek(")")) {
            throw new ParseException("no expression given", tokens.getNext());
        }

        Ast.Expr expr = parseExpr();

        if (!tokens.match(")")) {
            throw new ParseException("expected ')'", tokens.getNext());
        }

        return new Ast.Expr.Group(expr);
    }

    private Ast.Expr parseObjectExpr() throws ParseException {
        tokens.match("OBJECT");

        Optional<String> check = Optional.empty();
        if (tokens.peek(Token.Type.IDENTIFIER) && !tokens.peek("DO")) {
            check = Optional.of(tokens.get(0).literal());
            tokens.match(Token.Type.IDENTIFIER);
        }

        if (!tokens.match("DO")) {
            throw new ParseException("missing DO", tokens.getNext());
        }

        List<Ast.Stmt.Let> fields = new ArrayList<>();
        List<Ast.Stmt.Def> methods = new ArrayList<>();
        boolean seenMethod = false;

        while (!tokens.peek("END")) {
            if (!tokens.has(0)) {
                throw new ParseException("missing END", tokens.getNext());
            }

            if (tokens.peek("LET")) {
                if (seenMethod) {
                    throw new ParseException("more than one LET", tokens.getNext());
                }
                Ast.Stmt stmt = parseLetStmt();
                if (stmt instanceof Ast.Stmt.Let letStmt) {
                    fields.add(letStmt);
                } else {
                    throw new ParseException("missing LET", tokens.getNext());
                }
            } else if (tokens.peek("DEF")) {
                seenMethod = true;
                Ast.Stmt stmt = parseDefStmt();
                if (stmt instanceof Ast.Stmt.Def defStmt) {
                    methods.add(defStmt);
                } else {
                    throw new ParseException("missing DEF", tokens.getNext());
                }
            } else {
                throw new ParseException("unexpected token", tokens.getNext());
            }
        }

        if (!tokens.match("END")) {
            throw new ParseException("missing END", tokens.getNext());
        }

        return new Ast.Expr.ObjectExpr(check, fields, methods);
    }

    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        String check = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        if (tokens.match("(")) {
            List<Ast.Expr> argus = new ArrayList<>();

            if (!tokens.peek(")")) {
                argus.add(parseExpr());
                while (tokens.match(",")) {
                    if (tokens.peek(")")) {
                        throw new ParseException("trailing comma", tokens.getNext());
                    }
                    argus.add(parseExpr());
                }
            }

            if (!tokens.match(")")) {
                throw new ParseException("expected ')'", tokens.getNext());
            }

            return new Ast.Expr.Function(check, argus);
        } else {
            return new Ast.Expr.Variable(check);
        }
    }

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at (index + offset).
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Returns the token at (index + offset).
         */
        public Token get(int offset) {
            Preconditions.checkState(has(offset));
            return tokens.get(index + offset);
        }

        /**
         * Returns the next token, if present.
         */
        public Optional<Token> getNext() {
            return index < tokens.size() ? Optional.of(tokens.get(index)) : Optional.empty();
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is either a {@link Token.Type}, matching tokens
         * of that type, or a {@link String}, matching tokens with that literal.
         * In effect, {@code new Token(Token.Type.IDENTIFIER, "literal")} is
         * matched by both {@code peek(Token.Type.IDENTIFIER)} and
         * {@code peek("literal")}.
         */
        public boolean peek(Object... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var token = tokens.get(index + offset);
                var pattern = patterns[offset];
                Preconditions.checkState(pattern instanceof Token.Type || pattern instanceof String, pattern);
                if (!token.type().equals(pattern) && !token.literal().equals(pattern)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the token stream.
         */
        public boolean match(Object... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
            }
            return peek;
        }

    }

}
