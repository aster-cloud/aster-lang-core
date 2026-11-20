package aster.core.layout;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 显示排版的读写（ADR 0058 §7.3；ADR 0059 修订 4 L1）：严格读入、确定性序列化、两个视图的拼接。
 *
 * <p>序列化形态固定为 {@code {"v":1,"spans":[…]}}：内容 span 为 {@code {"text":…}}，结构 span 为
 * {@code {"canonical":…,"display":…}}，相邻内容 span 先合并，字符串按 ECMAScript {@code JSON.stringify} 转义，
 * 与 ts {@code serializeLayout} 逐字节相同。
 */
public final class LayoutFormat {

    private LayoutFormat() {
    }

    /**
     * L1（与 L2 的序列化字节上限）：先按 {@link LayoutLimits#SERIALIZED_BYTES} 判大小（超出为 L2，不解析），
     * 再严格 JSON → 结构校验 → 文件字节等于 {@link #serialize} 的 UTF-8。
     */
    public static PolicyLayout parseStrict(byte[] utf8) {
        if (utf8.length > LayoutLimits.SERIALIZED_BYTES) {
            throw new LayoutException("L2", "layout.json exceeds " + LayoutLimits.SERIALIZED_BYTES + " bytes");
        }
        Object tree;
        try {
            tree = new StrictJson(decodeStrict(utf8)).document();
        } catch (IllegalArgumentException e) {
            throw new LayoutException("L1", "layout.json is not strict JSON (" + e.getMessage() + ")");
        }
        PolicyLayout layout = readLayout(tree);
        if (!Arrays.equals(serialize(layout).getBytes(StandardCharsets.UTF_8), utf8)) {
            throw new LayoutException("L1", "layout.json is not in canonical serialized form");
        }
        return layout;
    }

    /** 确定性序列化（相邻内容 span 合并）。 */
    public static String serialize(PolicyLayout layout) {
        StringBuilder sb = new StringBuilder("{\"v\":1,\"spans\":[");
        boolean first = true;
        for (LayoutSpan s : mergeContentSpans(layout.spans())) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (s instanceof LayoutSpan.Content c) {
                sb.append("{\"text\":");
                jsonString(sb, c.text());
            } else {
                LayoutSpan.Structural st = (LayoutSpan.Structural) s;
                sb.append("{\"canonical\":");
                jsonString(sb, st.canonical());
                sb.append(",\"display\":");
                jsonString(sb, st.display());
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    /** 编译用的规范源码。 */
    public static String toCanonical(PolicyLayout layout) {
        return layout.toCanonical();
    }

    /** 显示文本。 */
    public static String toDisplay(PolicyLayout layout) {
        return layout.toDisplay();
    }

    /** 相邻内容 span 合并（结构 span 各自携带一段映射，不合并）。 */
    public static List<LayoutSpan> mergeContentSpans(List<LayoutSpan> spans) {
        List<LayoutSpan> out = new ArrayList<>(spans.size());
        for (LayoutSpan s : spans) {
            if (s instanceof LayoutSpan.Content c && !out.isEmpty() && out.get(out.size() - 1) instanceof LayoutSpan.Content last) {
                out.set(out.size() - 1, new LayoutSpan.Content(last.text() + c.text()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** 严格 UTF-8 解码（不去 BOM）；非法字节抛 {@link IllegalArgumentException}。 */
    static String decodeStrict(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not valid UTF-8");
        }
    }

    /** ECMAScript {@code JSON.stringify} 的字符串转义（孤立代理为小写 {@code \\uXXXX}，同 ES2019 well-formed stringify）。 */
    static void jsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    boolean lone = Character.isHighSurrogate(c)
                        ? !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                        : Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || lone) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** ts {@code readLayout} 的结构校验；失败为 L1。 */
    static PolicyLayout readLayout(Object tree) {
        if (!(tree instanceof Map<?, ?> root) || !hasExactKeys(root, "v", "spans")) {
            throw new LayoutException("L1", "layout must be exactly {v, spans}");
        }
        if (!(root.get("v") instanceof BigDecimal v) || v.compareTo(BigDecimal.ONE) != 0) {
            throw new LayoutException("L1", "layout.v must be 1");
        }
        if (!(root.get("spans") instanceof List<?> raw) || raw.isEmpty()) {
            throw new LayoutException("L1", "layout.spans must be a non-empty array");
        }
        List<LayoutSpan> spans = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            spans.add(readSpan(raw.get(i), i));
        }
        assertNoSplitSurrogate(spans, true);
        assertNoSplitSurrogate(spans, false);
        return new PolicyLayout(spans);
    }

    private static LayoutSpan readSpan(Object raw, int i) {
        if (raw instanceof Map<?, ?> m && hasExactKeys(m, "text")) {
            if (!(m.get("text") instanceof String t) || t.isEmpty()) {
                throw new LayoutException("L1", "spans[" + i + "].text must be a non-empty string");
            }
            return new LayoutSpan.Content(t);
        }
        if (raw instanceof Map<?, ?> m && hasExactKeys(m, "canonical", "display")) {
            if (!(m.get("canonical") instanceof String c) || c.isEmpty()) {
                throw new LayoutException("L1", "spans[" + i + "].canonical must be a non-empty string");
            }
            if (!(m.get("display") instanceof String d)) {
                throw new LayoutException("L1", "spans[" + i + "].display must be a string");
            }
            return new LayoutSpan.Structural(c, d);
        }
        throw new LayoutException("L1", "spans[" + i + "] must be exactly {text} or {canonical, display}");
    }

    private static boolean hasExactKeys(Map<?, ?> m, String... keys) {
        if (m.size() != keys.length) {
            return false;
        }
        for (String k : keys) {
            if (!m.containsKey(k)) {
                return false;
            }
        }
        return true;
    }

    /** 同一视图里相邻的两段非空文字不得把一个代理对拆开（跨过 display 为空的结构 span 也要检查）。 */
    private static void assertNoSplitSurrogate(List<LayoutSpan> spans, boolean canonical) {
        String prev = "";
        for (LayoutSpan s : spans) {
            String piece = canonical ? s.canonicalText() : s.displayText();
            if (piece.isEmpty()) {
                continue;
            }
            if (!prev.isEmpty() && Character.isHighSurrogate(prev.charAt(prev.length() - 1)) && Character.isLowSurrogate(piece.charAt(0))) {
                throw new LayoutException("L1", "a surrogate pair is split across spans in the " + (canonical ? "canonical" : "display") + " view");
            }
            prev = piece;
        }
    }

    /**
     * 包格式的严格 JSON（aster-lang-test README「严格 JSON」）：不以 BOM 开头；RFC 8259；恰好一个值，前后只允许
     * 空格、\t、\n、\r；字符串内无未转义的控制字符；无重复键；解码后的字符串（含键）无孤立代理；深度 ≤ 64。
     * 数字读成 {@link BigDecimal}。失败抛 {@link IllegalArgumentException}。
     */
    static final class StrictJson {
        private static final int MAX_DEPTH = 64;
        private final String s;
        private int i;

        StrictJson(String s) {
            this.s = s;
        }

        Object document() {
            if (s.startsWith("﻿")) {
                throw new IllegalArgumentException("starts with a BOM");
            }
            ws();
            Object v = value(0);
            ws();
            if (i != s.length()) {
                throw new IllegalArgumentException("trailing content at " + i);
            }
            return v;
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                i++;
            }
        }

        private Object value(int depth) {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{' || c == '[') {
                if (depth + 1 > MAX_DEPTH) {
                    throw new IllegalArgumentException("nesting deeper than " + MAX_DEPTH);
                }
                return c == '{' ? object(depth + 1) : array(depth + 1);
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return number();
        }

        private Map<String, Object> object(int depth) {
            i++;
            Map<String, Object> out = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return out;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new IllegalArgumentException("expected a key at " + i);
                }
                String key = string();
                ws();
                expect(':');
                ws();
                if (out.containsKey(key)) {
                    throw new IllegalArgumentException("duplicate key " + key);
                }
                out.put(key, value(depth));
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        private List<Object> array(int depth) {
            i++;
            List<Object> out = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                ws();
                out.add(value(depth));
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return out;
            }
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            }
            i++;
        }

        private String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("unescaped control character");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("short \\u escape");
                        }
                        String hex = s.substring(i, i + 4);
                        if (!hex.chars().allMatch(h -> Character.digit(h, 16) >= 0)) {
                            throw new IllegalArgumentException("bad \\u escape");
                        }
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
            String out = sb.toString();
            for (int k = 0; k < out.length(); k++) {
                char c = out.charAt(k);
                if (Character.isHighSurrogate(c)) {
                    if (k + 1 < out.length() && Character.isLowSurrogate(out.charAt(k + 1))) {
                        k++;
                        continue;
                    }
                    throw new IllegalArgumentException("lone surrogate");
                }
                if (Character.isLowSurrogate(c)) {
                    throw new IllegalArgumentException("lone surrogate");
                }
            }
            return out;
        }

        private BigDecimal number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
            }
            if (i < s.length() && s.charAt(i) == '0') {
                i++;
            } else if (i < s.length() && s.charAt(i) >= '1' && s.charAt(i) <= '9') {
                while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 0x80) {
                    i++;
                }
            } else {
                throw new IllegalArgumentException("unexpected character at " + start);
            }
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int d = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
                if (i == d) {
                    throw new IllegalArgumentException("bad number");
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                int d = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
                if (i == d) {
                    throw new IllegalArgumentException("bad number");
                }
            }
            return new BigDecimal(s.substring(start, i));
        }
    }
}
