package aster.core.lexer;

/**
 * 词法单元（Token）
 * <p>
 * 表示词法分析阶段识别的最小语法单元，包含类型、值、位置信息和可选的 trivia 通道。
 *
 * @param kind    Token 类型
 * @param value   Token 的值（字符串、数字、布尔值、null 或 CommentValue）
 * @param start   起始位置
 * @param end     结束位置
 * @param channel 可选的 trivia 通道（"trivia" 表示注释 token）
 */
public record Token(
    TokenKind kind,
    Object value,
    Position start,
    Position end,
    String channel
) {
    /**
     * 创建不带 trivia 通道的 Token
     */
    public Token(TokenKind kind, Object value, Position start, Position end) {
        this(kind, value, start, end, null);
    }

    /**
     * 判断是否为注释 Token
     */
    public boolean isComment() {
        return kind == TokenKind.COMMENT;
    }

    /**
     * 判断是否为 trivia Token（注释）
     */
    public boolean isTrivia() {
        return "trivia".equals(channel);
    }

    /**
     * 获取 CommentValue（仅当 kind 为 COMMENT 时）
     */
    public CommentValue getCommentValue() {
        if (kind != TokenKind.COMMENT) {
            throw new IllegalStateException("Token is not a comment");
        }
        return (CommentValue) value;
    }
}
