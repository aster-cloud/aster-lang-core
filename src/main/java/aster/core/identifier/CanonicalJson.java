package aster.core.identifier;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范 JSON 序列化器（ADR 0051 §12），输出须与 TS 的 JSON.stringify + 码点键序逐字节一致。
 * 值只有四种：String、Integer、Map（对象，键按码点排序）、List（数组，按给定顺序）。
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    /** 创建按码点序排键的对象。 */
    static Map<String, Object> object() {
        return new TreeMap<>(CaseFold::compareCodePoints);
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case String s -> appendString(out, s);
            case Integer n -> out.append(n.intValue());
            case List<?> list -> appendArray(out, list);
            case Map<?, ?> map -> appendObject(out, map);
            default -> throw new IllegalArgumentException("不支持的规范 JSON 值: " + value.getClass());
        }
    }

    private static void appendArray(StringBuilder out, List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(',');
            append(out, list.get(i));
        }
        out.append(']');
    }

    /** 调用方须传入 {@link #object()} 创建的 Map，键序由其比较器保证。 */
    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) out.append(',');
            first = false;
            appendString(out, (String) e.getKey());
            out.append(':');
            append(out, e.getValue());
        }
        out.append('}');
    }

    /**
     * 与 JSON.stringify 相同的转义：\" \\ \b \f \n \r \t；其余小于 U+0020 的字符与孤立代理项写作小写 \\uxxxx；
     * U+007F、U+2028、U+2029 及其它字符原样输出。
     */
    private static void appendString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String esc = shortEscape(c);
            if (esc != null) {
                out.append(esc);
            } else if (c < 0x20 || isLoneSurrogate(s, i)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }

    private static String shortEscape(char c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> null;
        };
    }

    private static boolean isLoneSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        }
        return false;
    }
}
