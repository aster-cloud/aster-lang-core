package aster.core.util;

import java.util.Objects;

/**
 * 字符串转义处理工具。
 * <p>
 * 将源码中的字符串字面量解析为实际字符序列，统一处理常见的转义语法。
 */
public final class StringEscapes {

    private StringEscapes() {
        // 工具类不允许被实例化
    }

    /**
     * 将源码字符串（不含首尾引号）解析为实际字符串。
     *
     * @param raw 含转义符的源码片段
     * @return 已解码的字符串
     * @throws IllegalArgumentException 遇到不被支持或不完整的转义序列时抛出
     */
    public static String unescape(String raw) {
        Objects.requireNonNull(raw, "raw 字符串不能为空");

        StringBuilder result = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length();) {
            char ch = raw.charAt(i++);
            if (ch != '\\') {
                result.append(ch);
                continue;
            }

            if (i >= raw.length()) {
                throw new IllegalArgumentException("转义序列不完整");
            }

            char next = raw.charAt(i++);
            switch (next) {
                case '"':
                    result.append('"');
                    break;
                case '\'':
                    result.append('\'');
                    break;
                case '\\':
                    result.append('\\');
                    break;
                case 'n':
                    result.append('\n');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 't':
                    result.append('\t');
                    break;
                case 'b':
                    result.append('\b');
                    break;
                case 'f':
                    result.append('\f');
                    break;
                case '0':
                    result.append('\0');
                    break;
                case '/':
                    result.append('/');
                    break;
                case 'u': {
                    if (raw.length() - i < 4) {
                        throw new IllegalArgumentException("Unicode 转义长度不足");
                    }
                    int codePoint = 0;
                    for (int k = 0; k < 4; k++) {
                        char hex = raw.charAt(i + k);
                        int digit = Character.digit(hex, 16);
                        if (digit < 0) {
                            String sequence = raw.substring(i, i + 4);
                            throw new IllegalArgumentException("非法的 Unicode 转义: \\u" + sequence);
                        }
                        codePoint = (codePoint << 4) | digit;
                    }
                    result.append((char) codePoint);
                    i += 4;
                    break;
                }
                default:
                    throw new IllegalArgumentException("不支持的转义序列: \\" + next);
            }
        }

        return result.toString();
    }
}
