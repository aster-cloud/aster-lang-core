package aster.core.lexer;

/**
 * Token 类型枚举
 * <p>
 * 定义 Aster CNL 语言的所有词法单元类型。
 */
public enum TokenKind {
    // 文件结束
    EOF,

    // 缩进控制
    NEWLINE,
    INDENT,
    DEDENT,

    // 标点符号
    DOT,          // .
    COLON,        // :
    COMMA,        // ,
    LPAREN,       // (
    RPAREN,       // )
    LBRACKET,     // [
    RBRACKET,     // ]

    // 运算符
    EQUALS,       // =
    PLUS,         // +
    STAR,         // *
    MINUS,        // -
    SLASH,        // /
    LT,           // <
    GT,           // >
    LTE,          // <=
    GTE,          // >=
    NEQ,          // !=
    QUESTION,     // ?
    AT,           // @

    // 标识符
    IDENT,        // 普通标识符 (lowercase 开头)
    TYPE_IDENT,   // 类型标识符 (Uppercase 开头)

    // 字面量
    STRING,       // 字符串字面量
    INT,          // 整数
    FLOAT,        // 浮点数
    LONG,         // 长整型 (带 L 后缀)
    BOOL,         // 布尔值 (true/false)
    NULL,         // null

    // 其他
    KEYWORD,      // 保留关键字（暂未使用）
    COMMENT       // 注释
}
