package aster.core.canonicalizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 字符串分段器 — 将源码文本分割为"字符串内"和"字符串外"片段。
 * <p>
 * 所有 SyntaxTransformer 实现通过此类保护字符串字面量内容不被修改。
 * <p>
 * 典型用法：
 * <pre>{@code
 * List<StringSegmenter.Segment> segments = segmenter.segment(source);
 * StringBuilder result = new StringBuilder();
 * for (var seg : segments) {
 *     result.append(seg.inString() ? seg.text() : transform(seg.text()));
 * }
 * return result.toString();
 * }</pre>
 */
public final class StringSegmenter {

    private final String quoteOpen;
    private final String quoteClose;

    /**
     * @param quoteOpen  字符串开始引号（如 {@code \"} 或 {@code 「}）
     * @param quoteClose 字符串结束引号（如 {@code \"} 或 {@code 」}）
     */
    public StringSegmenter(String quoteOpen, String quoteClose) {
        this.quoteOpen = quoteOpen;
        this.quoteClose = quoteClose;
    }

    /**
     * 将源码分段为字符串内和字符串外的片段。
     * <p>
     * 支持词法表配置的引号、ASCII 双引号和智能双引号。
     *
     * @param s 源码文本
     * @return 分段列表
     */
    public List<Segment> segment(String s) {
        List<Segment> segments = new ArrayList<>();
        boolean inString = false;
        String expectedClose = null;
        StringBuilder current = new StringBuilder();

        Map<String, String> quotePairs = new HashMap<>();
        quotePairs.put(quoteOpen, quoteClose);
        if (!quoteOpen.equals("\"")) {
            quotePairs.put("\"", "\"");
        }
        quotePairs.put("\u201C", "\u201D");

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            current.append(ch);

            if (!inString) {
                String chStr = String.valueOf(ch);
                if (quotePairs.containsKey(chStr) && !isEscaped(s, i)) {
                    String before = current.substring(0, current.length() - 1);
                    if (!before.isEmpty()) {
                        segments.add(new Segment(before, false));
                    }
                    current = new StringBuilder(chStr);
                    inString = true;
                    expectedClose = quotePairs.get(chStr);
                }
            } else if (expectedClose != null && expectedClose.equals(String.valueOf(ch)) && !isEscaped(s, i)) {
                segments.add(new Segment(current.toString(), true));
                current = new StringBuilder();
                inString = false;
                expectedClose = null;
            }
        }

        if (current.length() > 0) {
            segments.add(new Segment(current.toString(), inString));
        }

        return segments;
    }

    /**
     * 便捷方法：对源码的非字符串部分应用正则替换。
     *
     * @param source      源码文本
     * @param pattern     正则表达式
     * @param replacement 替换内容
     * @return 替换后的文本（字符串字面量内容不变）
     */
    public String replaceOutsideStrings(String source, Pattern pattern, String replacement) {
        List<Segment> segments = segment(source);
        StringBuilder result = new StringBuilder(source.length());
        for (Segment seg : segments) {
            if (seg.inString) {
                result.append(seg.text);
            } else {
                result.append(pattern.matcher(seg.text).replaceAll(replacement));
            }
        }
        return result.toString();
    }

    /**
     * 便捷方法：对源码的非字符串部分应用字符串替换。
     *
     * @param source      源码文本
     * @param target      要替换的字符串
     * @param replacement 替换内容
     * @return 替换后的文本（字符串字面量内容不变）
     */
    public String replaceOutsideStrings(String source, String target, String replacement) {
        List<Segment> segments = segment(source);
        StringBuilder result = new StringBuilder(source.length());
        for (Segment seg : segments) {
            if (seg.inString) {
                result.append(seg.text);
            } else {
                result.append(seg.text.replace(target, replacement));
            }
        }
        return result.toString();
    }

    /**
     * 便捷方法：对源码的非字符串部分应用自定义变换函数。
     *
     * @param source    源码文本
     * @param transform 应用于非字符串段的变换函数
     * @return 变换后的文本（字符串字面量内容不变）
     */
    public String transformOutsideStrings(String source, java.util.function.UnaryOperator<String> transform) {
        List<Segment> segments = segment(source);
        StringBuilder result = new StringBuilder(source.length());
        for (Segment seg : segments) {
            if (seg.inString) {
                result.append(seg.text);
            } else {
                result.append(transform.apply(seg.text));
            }
        }
        return result.toString();
    }

    private boolean isEscaped(String str, int index) {
        int slashCount = 0;
        for (int i = index - 1; i >= 0 && str.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    /**
     * 源码片段（字符串内或字符串外）
     */
    public record Segment(String text, boolean inString) {}
}
