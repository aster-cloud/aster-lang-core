package aster.core.util;

/**
 * 只折叠 ASCII 字母的大小写。效应名与内建 / 保留效应名的比较按 ASCII 大小写不敏感（ADR 0055 #5，locales
 * core-ir/README.md），不能用 {@link String#toLowerCase}：后者也会折叠非 ASCII 字母，两引擎结果可能不同。
 */
public final class AsciiCase {

    private AsciiCase() {}

    /** 把 A–Z 换成 a–z，其余字符原样保留。 */
    public static String lower(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return b.toString();
    }
}
