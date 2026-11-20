package aster.core.layout;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 显示排版的规模上限（ADR 0058 §7.6 {@code LAYOUT_LIMITS}，数值不改）与 L2 检查。
 *
 * <p>字节一律按 UTF-8 计（孤立代理按替换字符 3 字节计，同 ts {@code utf8Length}）；单个结构 span 的 display 按码点计。
 * 按 ts {@code assertWithinLimits} 的顺序逐项检查，首个超出即失败。
 */
public final class LayoutLimits {

    public static final int SOURCE_BYTES = 12288;
    public static final int SOURCE_LINES = 200;
    public static final int SPANS = 400;
    public static final int SERIALIZED_BYTES = 49152;
    public static final int DISPLAY_BYTES = 24576;
    public static final int STRUCTURAL_DISPLAY_CHARS = 256;

    private LayoutLimits() {
    }

    private record Measure(String name, int limit, ToIntFunction<PolicyLayout> value) {
    }

    /** L2：{@code source} 为规范源码文本（派生的 C′，或已解码的 policy.aster）。 */
    public static void check(PolicyLayout layout, String source) {
        check(layout, utf8Length(source), lineCount(source));
    }

    /**
     * L2：{@code source} 为 policy.aster 的原始字节。字节数与行数直接在字节上数（\r、\n 在 UTF-8 中只作单字节出现），
     * 与解码无关：非法 UTF-8 的源码同样有确定结论（aster-lang-test 向量 {@code invalid-utf8-source-*}）。
     */
    public static void check(PolicyLayout layout, byte[] source) {
        int breaks = 0;
        for (int i = 0; i < source.length; i++) {
            if (source[i] == '\n') {
                breaks++;
            } else if (source[i] == '\r') {
                breaks++;
                if (i + 1 < source.length && source[i + 1] == '\n') {
                    i++;
                }
            }
        }
        int lines = source.length == 0 ? 0
            : source[source.length - 1] == '\n' || source[source.length - 1] == '\r' ? breaks : breaks + 1;
        check(layout, source.length, lines);
    }

    private static void check(PolicyLayout layout, int sourceBytes, int sourceLines) {
        List<Measure> measures = List.of(
            new Measure("sourceBytes", SOURCE_BYTES, l -> sourceBytes),
            new Measure("sourceLines", SOURCE_LINES, l -> sourceLines),
            new Measure("spans", SPANS, LayoutLimits::storedSpanCount),
            new Measure("serializedBytes", SERIALIZED_BYTES, l -> utf8Length(LayoutFormat.serialize(l))),
            new Measure("displayBytes", DISPLAY_BYTES, l -> utf8Length(l.toDisplay())),
            new Measure("structuralDisplayChars", STRUCTURAL_DISPLAY_CHARS, LayoutLimits::maxStructuralDisplayChars));
        for (Measure m : measures) {
            int actual = m.value().applyAsInt(layout);
            if (actual > m.limit()) {
                throw new LayoutException("L2", m.name() + " " + actual + " exceeds " + m.limit());
            }
        }
    }

    /** UTF-8 字节数，按码点累计；孤立代理按 3 字节计（与 ts 相同）。 */
    public static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            n += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            i += Character.charCount(cp);
        }
        return n;
    }

    /** 源码行数：按 \r\n、\r、\n 断行，末尾换行不另计一行；空源码为 0 行。 */
    static int lineCount(String source) {
        if (source.isEmpty()) {
            return 0;
        }
        int breaks = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                breaks++;
            } else if (c == '\r') {
                breaks++;
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        char last = source.charAt(source.length() - 1);
        return last == '\n' || last == '\r' ? breaks : breaks + 1;
    }

    /** 存储形态（相邻内容 span 合并后）的 span 数。 */
    static int storedSpanCount(PolicyLayout layout) {
        int n = 0;
        boolean prevText = false;
        for (LayoutSpan s : layout.spans()) {
            boolean isText = s instanceof LayoutSpan.Content;
            if (!(isText && prevText)) {
                n++;
            }
            prevText = isText;
        }
        return n;
    }

    private static int maxStructuralDisplayChars(PolicyLayout layout) {
        int max = 0;
        for (LayoutSpan s : layout.spans()) {
            if (s instanceof LayoutSpan.Structural st) {
                max = Math.max(max, st.display().codePointCount(0, st.display().length()));
            }
        }
        return max;
    }
}
