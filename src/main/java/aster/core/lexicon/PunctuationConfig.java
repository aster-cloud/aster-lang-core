package aster.core.lexicon;

import java.util.Optional;

/**
 * 标点符号配置。
 * <p>
 * 定义该语言使用的各种标点符号，用于 Canonicalizer 和 Lexer 处理。
 *
 * @param statementEnd   语句结束符 (英文 ".", 中文 "。")
 * @param listSeparator  列表分隔符 (英文 ",", 中文 "，")
 * @param enumSeparator  枚举分隔符 (英文 ",", 中文 "、")
 * @param blockStart     块引导符 (英文 ":", 中文 "：")
 * @param stringQuoteOpen  字符串开始引号 (英文 '"', 中文 '「')
 * @param stringQuoteClose 字符串结束引号 (英文 '"', 中文 '」')
 * @param markerOpen     标记符开始 (中文 '【', 可选)
 * @param markerClose    标记符结束 (中文 '】', 可选)
 */
public record PunctuationConfig(
    String statementEnd,
    String listSeparator,
    String enumSeparator,
    String blockStart,
    String stringQuoteOpen,
    String stringQuoteClose,
    String markerOpen,
    String markerClose
) {
    /**
     * 获取标记符开始字符（如果配置了）
     */
    public Optional<String> getMarkerOpen() {
        return Optional.ofNullable(markerOpen);
    }

    /**
     * 获取标记符结束字符（如果配置了）
     */
    public Optional<String> getMarkerClose() {
        return Optional.ofNullable(markerClose);
    }

    /**
     * 检查是否配置了标记符
     */
    public boolean hasMarkers() {
        return markerOpen != null && markerClose != null;
    }
}
