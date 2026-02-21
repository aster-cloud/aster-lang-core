package aster.core.lexicon;

/**
 * 错误消息模板。
 * <p>
 * 使用 {@code {placeholder}} 语法定义占位符。
 *
 * @param unexpectedToken     意外的符号
 * @param expectedKeyword     期望的关键词
 * @param undefinedVariable   未定义的变量
 * @param typeMismatch        类型不匹配
 * @param unterminatedString  未终止的字符串
 * @param invalidIndentation  无效的缩进
 */
public record ErrorMessages(
    String unexpectedToken,
    String expectedKeyword,
    String undefinedVariable,
    String typeMismatch,
    String unterminatedString,
    String invalidIndentation
) {
    /**
     * 默认错误消息（英文）
     * <p>
     * 仅作为 {@link DynamicLexicon} 在 JSON 缺少 messages 节点时的 fallback。
     * 实际的语言特定消息由各语言包的 JSON 文件定义。
     */
    public static ErrorMessages defaults() {
        return new ErrorMessages(
            "Unexpected token: {token}",
            "Expected keyword: {keyword}",
            "Undefined variable: {name}",
            "Type mismatch: expected {expected}, got {actual}",
            "Unterminated string literal",
            "Invalid indentation: must be multiples of 2 spaces"
        );
    }

    /**
     * 格式化错误消息，替换占位符
     *
     * @param template 消息模板
     * @param args     占位符名称和值的交替数组
     * @return 格式化后的消息
     */
    public static String format(String template, String... args) {
        String result = template;
        for (int i = 0; i + 1 < args.length; i += 2) {
            String placeholder = "{" + args[i] + "}";
            result = result.replace(placeholder, args[i + 1]);
        }
        return result;
    }
}
