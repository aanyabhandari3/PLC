package plc.project.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * The lexer works through a combination of {@link #lex()}, which repeatedly
 * calls {@link #lexToken()} and skips over whitespace/comments, and
 * {@link #lexToken()}, which determines the type of the next token and
 * delegates to the corresponding lex method.
 *
 * <p>Additionally, {@link CharStream} manages the lexer state and contains
 * {@link CharStream#peek} and {@link CharStream#match}. These are helpful
 * utilities for working with character state and building tokens.
 */
public final class Lexer {

    private final CharStream chars;

    public Lexer(String input) {
        chars = new CharStream(input);
    }

    public List<Token> lex() throws LexException {
        var tokens = new ArrayList<Token>();
        while (chars.has(0)) {
            if (chars.peek("[ \t\r\n\b]")) {
                lexWhitespace();
                continue;
            } else if (chars.peek("/", "/")) {
                lexComment();
                continue;
            }
            tokens.add(lexToken());
        }
        return tokens;
    }

    private void lexWhitespace() {
        while (chars.has(0) && chars.peek("[ \t\r\n\b]")) {
            chars.match("[ \t\r\n\b]");
        }
        chars.emit();
    }

    private void lexComment() {
        while (chars.has(0) && !chars.peek("\\r\n")) {
            chars.match(".");
        }
        chars.emit();
    }

    private Token lexToken() throws LexException {
        if (chars.peek("[A-Za-z_]")) {
            return lexIdentifier();
        } else if (chars.peek("[0-9]")) {
            return lexNumber();
        } else if (chars.peek("[+-]") && chars.has(1) && String.valueOf(chars.input.charAt(chars.index + 1)).matches("[0-9]")) {
            return lexNumber();
        } else if (chars.peek("'")) {
            return lexCharacter();
        } else if (chars.peek("\"")) {
            return lexString();
        } else {
            return lexOperator();
        }
    }

    private Token lexIdentifier() {
        while (chars.has(0) && chars.peek("[A-Za-z0-9_-]")) {
            chars.match("[A-Za-z0-9_-]");
        }
        return new Token(Token.Type.IDENTIFIER, chars.emit());
    }

    private Token lexNumber() throws LexException {
        boolean decimal = false;

        if (chars.peek("[+-]")) {
            chars.match("[+-]");
        }

        while (chars.has(0) && chars.peek("[0-9]")) {
            chars.match("[0-9]");
        }

        if (chars.peek("\\.")) {
            if (chars.has(1) && String.valueOf(chars.input.charAt(chars.index + 1)).matches("[0-9]")) {
                decimal = true;
                chars.match("\\.");
                while (chars.has(0) && chars.peek("[0-9]")) {
                    chars.match("[0-9]");
                }
            } else {
                return new Token(Token.Type.INTEGER, chars.emit());
            }
        }

        if (chars.peek("e") || chars.peek("E")) {
            int lookAhead = 1;
            if (chars.has(lookAhead) && String.valueOf(chars.input.charAt(chars.index + lookAhead)).matches("[+-]")) {
                lookAhead++;
            }
            if (chars.has(lookAhead) && String.valueOf(chars.input.charAt(chars.index + lookAhead)).matches("[0-9]")) {
                chars.match("[eE]");
                if (chars.peek("[+-]")) {
                    chars.match("[+-]");
                }
                while (chars.has(0) && chars.peek("[0-9]")) {
                    chars.match("[0-9]");
                }
            } else {
                return new Token(decimal ? Token.Type.DECIMAL : Token.Type.INTEGER, chars.emit());
            }
        }

        return new Token(decimal ? Token.Type.DECIMAL : Token.Type.INTEGER, chars.emit());
    }

    private Token lexCharacter() throws LexException {
        chars.match("'");
        if (!chars.has(0)) {
            throw new LexException("Unterminated character literal", chars.index);
        }

        if (chars.peek("'")) {
            throw new LexException("Empty character literal", chars.index);
        }

        if (chars.peek("\\\\")) {
            lexEscape();
        } else if (chars.peek("[\\r\\n]")) {
            throw new LexException("Unterminated character literal", chars.index);
        } else {
            chars.match(".");
        }

        if (!chars.match("'")) {
            throw new LexException("Unterminated character literal", chars.index);
        }

        return new Token(Token.Type.CHARACTER, chars.emit());
    }

    private Token lexString() throws LexException {
        chars.match("\""); // opening "
        while (chars.has(0) && !chars.peek("\"")) {
            if (chars.peek("\\\\")) {
                lexEscape();
            } else if (chars.peek("\\r\n")) {
                throw new LexException("Unterminated string literal", chars.index);
            } else {
                chars.match(".");
            }
        }

        if (!chars.match("\"")) {
            throw new LexException("Unterminated string literal", chars.index);
        }

        return new Token(Token.Type.STRING, chars.emit());
    }

    private void lexEscape() throws LexException {
        chars.match("\\\\"); // consume '\'
        if (!chars.has(0)) {
            throw new LexException("Unterminated escape sequence", chars.index);
        }
        if (!chars.match("[bnfrt'\"\\\\]")) {
            throw new LexException("Invalid escape sequence", chars.index);
        }
    }

    public Token lexOperator() {
        if (chars.peek("<", "=")) {
            chars.match("<", "=");
            return new Token(Token.Type.OPERATOR, chars.emit());
        } else if (chars.peek(">", "=")) {
            chars.match(">", "=");
            return new Token(Token.Type.OPERATOR, chars.emit());
        } else if (chars.peek("=", "=")) {
            chars.match("=", "=");
            return new Token(Token.Type.OPERATOR, chars.emit());
        } else if (chars.peek("!", "=")) {
            chars.match("!", "=");
            return new Token(Token.Type.OPERATOR, chars.emit());
        } else if (chars.peek("&", "&")) {
            chars.match("&", "&");
            return new Token(Token.Type.OPERATOR, chars.emit());
        } else if (chars.peek("|", "|")) {
            chars.match("|", "|");
            return new Token(Token.Type.OPERATOR, chars.emit());
        } else {
            chars.match(".");
            return new Token(Token.Type.OPERATOR, chars.emit());
        }
    }

    /**
     * A helper class for maintaining the state of the character stream (input)
     * and methods for building up token literals.
     */
    private static final class CharStream {

        private final String input;
        private int index = 0;
        private int length = 0;

        public CharStream(String input) {
            this.input = input;
        }

        public boolean has(int offset) {
            return index + offset < input.length();
        }

        /**
         * Returns true if the next character(s) match their corresponding
         * pattern(s). Each pattern is a regex matching ONE character, e.g.:
         *  - peek("/") is valid and will match the next character
         *  - peek("/", "/") is valid and will match the next two characters
         *  - peek("/+") is conceptually invalid, but will match one character
         *  - peek("//") is strictly invalid as it can never match one character
         */
        public boolean peek(String... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var character = input.charAt(index + offset);
                if (!String.valueOf(character).matches(patterns[offset])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the character stream.
         */
        public boolean match(String... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
                length += patterns.length;
            }
            return peek;
        }

        /**
         * Returns the literal built by all characters matched since the last
         * call to emit(); also resetting the length for subsequent tokens.
         */
        public String emit() {
            var literal = input.substring(index - length, index);
            length = 0;
            return literal;
        }

    }

}
