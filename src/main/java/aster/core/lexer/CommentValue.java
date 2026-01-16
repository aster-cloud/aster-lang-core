package aster.core.lexer;

/**
 * 注释 Token 的取值结构
 * <p>
 * 保存原始文本、整理后的主体文本以及注释分类（inline 或 standalone）。
 *
 * @param raw    原始文本（包含注释前缀 // 或 #）
 * @param text   整理后的主体文本（去除前缀和前导空格）
 * @param trivia 注释类型：inline（行内注释）或 standalone（独立注释）
 */
public record CommentValue(String raw, String text, String trivia) {}
