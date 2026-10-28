package aster.core.identifier;

import java.text.Normalizer;
import java.util.Arrays;

/**
 * 名称比较使用的规范化与折叠（ADR 0051 §6）：NFC 之后逐码点应用 Unicode 15.1 简单折叠（C+S）。
 * 不使用宿主的 toLowerCase()，以保证 Java 与 TS 两个引擎结果一致。
 */
public final class CaseFold {

    /** 折叠表对应的 Unicode 版本。 */
    public static final String VERSION = CaseFoldTable.VERSION;

    private CaseFold() {
    }

    /** Unicode NFC 规范化。 */
    public static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** 单个码点的简单折叠；表中没有的码点折叠为自身。 */
    public static int foldCodePoint(int cp) {
        int i = Arrays.binarySearch(CaseFoldTable.FROM, cp);
        return i < 0 ? cp : CaseFoldTable.TO[i];
    }

    /**
     * 折叠键：NFC(逐码点折叠(NFC(s)))。按码点而非 UTF-16 单元处理，孤立代理项原样保留；
     * 末尾再做 NFC，使折叠键对 NFC 闭合，可直接作为规范 JSON 的键。
     */
    public static String foldKey(String s) {
        StringBuilder out = new StringBuilder(s.length());
        nfc(s).codePoints().forEach(cp -> out.appendCodePoint(foldCodePoint(cp)));
        return nfc(out.toString());
    }

    /** 按码点（等价于 UTF-8 字节序）比较字符串；String.compareTo 按 UTF-16 单元，对增补平面字符不正确。 */
    public static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }
}
