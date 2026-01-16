package aster.core.lexer;

/**
 * Lexer 异常
 * <p>
 * 当词法分析遇到错误时抛出，如非法字符、缩进错误、未闭合字符串等。
 */
public class LexerException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Position position;

    public LexerException(String message, Position position) {
        super(String.format("%s at line %d, col %d", message, position.line(), position.col()));
        this.position = position;
    }

    public Position getPosition() {
        return position;
    }

    // 静态工厂方法
    public static LexerException unexpectedCharacter(char ch, Position pos) {
        return new LexerException("Unexpected character: '" + ch + "'", pos);
    }

    public static LexerException unterminatedString(Position pos) {
        return new LexerException("Unterminated string literal", pos);
    }

    public static LexerException invalidIndentation(Position pos) {
        return new LexerException("Invalid indentation: must be even number of spaces", pos);
    }

    public static LexerException inconsistentDedent(Position pos) {
        return new LexerException("Inconsistent dedent: no matching indentation level", pos);
    }

    public static LexerException invalidEscape(String message, Position pos) {
        return new LexerException(message, pos);
    }
}
