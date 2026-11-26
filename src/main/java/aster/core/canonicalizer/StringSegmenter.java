package aster.core.canonicalizer;

import aster.core.lexicon.RegexGuard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 字符串分段器 — 将源码文本分割为"字符串内"和"字符串外"片段。
 * <p>
 * 所有 SyntaxTransformer 实现通过此类保护字符串字面量内容不被修改。名字转义 {@code `x`}（ADR 0064）同样是不透明片段：
 * 它的 {@code inString()} 为 true，按 {@code inString()} 跳过字符串的变换器无需改动就跳过它。
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
            // 名字转义 `x` 是不透明片段（ADR 0064 N2）：变换器与字符串字面量一样不碰它
            int escapeClose = !inString && ch == '`' ? Canonicalizer.escapeClose(s, i) : -1;
            if (escapeClose > 0) {
                if (current.length() > 0) {
                    segments.add(new Segment(current.toString(), false));
                    current = new StringBuilder();
                }
                segments.add(Segment.escape(s.substring(i, escapeClose + 1)));
                i = escapeClose;
                continue;
            }
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
        return replaceOutsideStrings(source, pattern, replacement, false);
    }

    /**
     * 便捷方法：对源码的非字符串部分应用正则替换，可选 ReDoS 看门狗。
     * <p>
     * ★正则在<b>整个源码</b>上匹配，只把字符串字面量之外的区间交给
     * {@link RegexGuard#replaceRegions}：这样 {@code MULTILINE ^}、{@code $} 与 lookbehind
     * 看到的是真实上下文，不会把字符串字面量之后的行中文本当成"行首"改写。
     * <p>
     * 当 {@code guarded} 为 {@code true} 时（用于来自不可信 lexicon 配置的正则），
     * 替换在看门狗超时内执行，灾难性回溯会被中断并抛出 {@link RegexGuard.RegexTimeoutException}。
     *
     * @param source      源码文本
     * @param pattern     正则表达式
     * @param replacement 替换内容
     * @param guarded     是否启用看门狗超时
     * @return 替换后的文本（字符串字面量内容不变）
     */
    public String replaceOutsideStrings(String source, Pattern pattern, String replacement, boolean guarded) {
        List<Segment> segments = segment(source);
        List<RegexGuard.Region> regions = outsideStringRegions(segments);
        List<RegexGuard.Region> escapes = escapeRegions(segments);
        java.util.function.Predicate<java.util.regex.MatchResult> keepsEscapes = m -> keepsEscapesWhole(m, escapes);
        return guarded
            ? RegexGuard.replaceRegionsWithTimeout(pattern, source, regions, replacement, RegexGuard.DEFAULT_TIMEOUT_MS, keepsEscapes)
            : RegexGuard.replaceRegions(pattern, source, regions, replacement, keepsEscapes);
    }

    /**
     * 字符串字面量之外的区间（升序、互不重叠）；分段拼接即原文，故偏移可累加得到。名字转义算在区间之内、相邻的代码段
     * 合成一个区间：句式变换（zh {@code 令 `x` 定义为 …}）的匹配可以整个跨过转义名，见 {@link #keepsEscapesWhole}。
     */
    private static List<RegexGuard.Region> outsideStringRegions(List<Segment> segments) {
        List<RegexGuard.Region> regions = new ArrayList<>();
        int offset = 0;
        int open = -1;
        for (Segment seg : segments) {
            int end = offset + seg.text.length();
            boolean code = !seg.inString || seg.escape;
            if (code && open < 0) {
                open = offset;
            } else if (!code && open >= 0) {
                regions.add(new RegexGuard.Region(open, offset));
                open = -1;
            }
            offset = end;
        }
        if (open >= 0) {
            regions.add(new RegexGuard.Region(open, offset));
        }
        return regions;
    }

    private static List<RegexGuard.Region> escapeRegions(List<Segment> segments) {
        List<RegexGuard.Region> out = new ArrayList<>();
        int offset = 0;
        for (Segment seg : segments) {
            int end = offset + seg.text.length();
            if (seg.escape) {
                out.add(new RegexGuard.Region(offset, end));
            }
            offset = end;
        }
        return out;
    }

    /**
     * 名字转义不透明（ADR 0064 N2）：匹配的两端都不得落在转义内部。整个包含转义的匹配可以改写（变换器的替换串按捕获组
     * 原样带上它），只覆盖转义一部分的匹配不改写，转义内容因此永远不被改动。
     * <p>
     * 「与某个转义相交却不整个包含它」恰好等价于「匹配的起点或终点严格落在某个转义内部」，所以只需二分查两个端点：
     * 逐个转义比对会让每个匹配都扫一遍全部转义，转义与匹配都随输入线性增长时整体退化成平方。
     */
    private static boolean keepsEscapesWhole(java.util.regex.MatchResult m, List<RegexGuard.Region> escapes) {
        return !strictlyInside(m.start(), escapes) && !strictlyInside(m.end(), escapes);
    }

    /** pos 是否严格落在某个转义内部（start &lt; pos &lt; end）；escapes 升序且互不重叠。 */
    private static boolean strictlyInside(int pos, List<RegexGuard.Region> escapes) {
        int lo = 0;
        int hi = escapes.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            RegexGuard.Region e = escapes.get(mid);
            if (pos <= e.start()) {
                hi = mid - 1;
            } else if (pos >= e.end()) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
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
     * 源码片段：字符串外的代码，或不透明片段（字符串字面量、名字转义）。名字转义的 {@code inString} 也为 true，
     * {@code escape} 区分两者。
     */
    public record Segment(String text, boolean inString, boolean escape) {
        public Segment(String text, boolean inString) {
            this(text, inString, false);
        }

        static Segment escape(String text) {
            return new Segment(text, true, true);
        }
    }
}
