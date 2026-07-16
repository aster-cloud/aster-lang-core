package aster.core.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical JSON serializer — 权威（Java）侧实现。
 *
 * P0-A 规则集升级回归工具（ADR 0030 §3.5 / 附录 A.2）的漂移检测地基。必须与
 * aster-cloud TS 侧 {@code src/lib/canonical-json.ts} 产出**字节级一致**的 canonical
 * 字符串 —— 否则 old（Java 权威引擎）↔ new toolchain 的 hash 比对会因表示差异误报
 * regression 或碰撞漏报。TS↔Java golden fixture（{@code CanonicalJsonParityTest}）
 * 逐条固化两侧字节一致，是发布前 parity gate。
 *
 * <p>铁律（与 TS 逐项对齐）：
 * <ul>
 *   <li>object key 按 Unicode code point 升序；array 保序。</li>
 *   <li>null 显式；missing ≠ null；不丢空对象/空数组/false/0/""。</li>
 *   <li>string 原值不 trim/case-fold；转义复刻 JS {@code JSON.stringify} 策略。</li>
 *   <li>非 Decimal number **只允许 safe integer**（|n| ≤ 2^53−1）——跨引擎浮点表示不一致，
 *       小数/超范围须走 Decimal（string 承载）+ typeCtx。</li>
 *   <li>Decimal 类型感知规范化（无 exponent/无前导零/无 trailing zero/-0→0/整数无 .0）。</li>
 * </ul>
 */
public final class CanonicalJson {

    /** 当前 canonical 算法版本，写进 hash 前缀。变更算法必 bump（须与 TS CANONICALIZATION_VERSION 一致）。 */
    public static final String CANONICALIZATION_VERSION = "aster-canonical-json/v1";

    /** JS Number.MAX_SAFE_INTEGER。非 Decimal number 的绝对值上限（含）。 */
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    /** Decimal 展开资源上限（与 TS MAX_DECIMAL_* 一致，防大指数 DoS）。 */
    private static final int MAX_DECIMAL_DIGITS = 4096;
    private static final int MAX_DECIMAL_EXPONENT = 4096;

    private CanonicalJson() {}

    /** canonical 化失败原因（对齐 TS CanonicalErrorReason / Execution.replayabilityReasons）。 */
    public enum ErrorReason {
        NON_CANONICAL_NUMBER,
        NON_INTEGER_NUMBER,
        UNSUPPORTED_VALUE,
        DECIMAL_TOO_LARGE
    }

    /** canonical 化异常（携带原因 + JSON 路径，便于诊断 / 映射 NON_REPLAYABLE）。 */
    public static final class CanonicalJsonException extends RuntimeException {
        private final ErrorReason reason;
        private final String path;

        public CanonicalJsonException(ErrorReason reason, String message, String path) {
            super(reason + " at " + path + ": " + message);
            this.reason = reason;
            this.path = path;
        }

        public ErrorReason reason() {
            return reason;
        }

        public String path() {
            return path;
        }
    }

    /**
     * 类型上下文：声明哪些字段路径是 Decimal（须做 decimal canonical）。
     * 路径用点号连接，array 元素用 {@code []} 通配（如 {@code applicants[].income}）。
     * 无 ctx 时所有 number 按 safe-integer 铁律处理。
     */
    public record TypeContext(Set<String> decimalPaths) {
        public static TypeContext of(String... paths) {
            var set = new java.util.HashSet<String>();
            for (String p : paths) {
                set.add(normalizePathForMatch(p));
            }
            return new TypeContext(set);
        }

        public static TypeContext empty() {
            return new TypeContext(Set.of());
        }

        boolean isDecimalPath(String path) {
            return decimalPaths.contains(normalizePathForMatch(path));
        }
    }

    /** array 下标归一 pattern（缓存，避免反复编译）。 */
    private static final java.util.regex.Pattern ARRAY_INDEX_PATTERN = java.util.regex.Pattern.compile("\\[\\d+]");

    /** 把具体 array 下标路径归一为 {@code []} 通配，用于 typeCtx 匹配（与 TS normalizePathForMatch 一致）。 */
    private static String normalizePathForMatch(String path) {
        return ARRAY_INDEX_PATTERN.matcher(path).replaceAll("[]");
    }

    /**
     * 把 JsonNode 序列化为 canonical 字符串（确定性、跨实现可复现，与 TS canonicalJson 字节一致）。
     *
     * @throws CanonicalJsonException 遇到 NaN/Infinity/非法 Decimal/非 JSON 值。
     */
    public static String canonicalJson(JsonNode value, TypeContext ctx) {
        StringBuilder sb = new StringBuilder();
        write(value, "", ctx, sb);
        return sb.toString();
    }

    public static String canonicalJson(JsonNode value) {
        return canonicalJson(value, TypeContext.empty());
    }

    /**
     * 计算 canonical hash：{@code sha256(CANONICALIZATION_VERSION + "\n" + canonicalJson(value))}（hex）。
     * 与 TS canonicalHash 同算法同前缀 → 同输入产同 hash。
     */
    public static String canonicalHash(JsonNode value, TypeContext ctx) {
        return hashCanonical(canonicalJson(value, ctx));
    }

    public static String canonicalHash(JsonNode value) {
        return canonicalHash(value, TypeContext.empty());
    }

    /**
     * canonical 字符串 + 其 hash 的配对（M2 回放 payload）。一次序列化同时拿到 canonical 串与其 hash，
     * 避免 canonicalJson + canonicalHash 各序列化一次的重复开销。
     *
     * <p>返回的 {@link #canonical} 就是被 hash 的那个串（{@code hash == sha256(version + "\n" + canonical)}）——
     * cloud 收到后 <b>直接 re-hash 校验即可，无需 re-canonicalize</b>（绕开 liftDecimals 的 TS↔Java parity gap，
     * 见 docs/m2-replay-payload-contract.md）。
     */
    public record CanonicalPair(String canonical, String hash) {}

    public static CanonicalPair canonicalWithHash(JsonNode value, TypeContext ctx) {
        String canonical = canonicalJson(value, ctx);
        return new CanonicalPair(canonical, hashCanonical(canonical));
    }

    public static CanonicalPair canonicalWithHash(JsonNode value) {
        return canonicalWithHash(value, TypeContext.empty());
    }

    /** 对已 canonical 化的字符串算 hash（sha256(version + "\n" + canonical)，hex）。单一构造点。 */
    private static String hashCanonical(String canonical) {
        String payload = CANONICALIZATION_VERSION + "\n" + canonical;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void write(JsonNode node, String path, TypeContext ctx, StringBuilder sb) {
        if (node == null || node.isMissingNode()) {
            throw new CanonicalJsonException(ErrorReason.UNSUPPORTED_VALUE, "missing/absent node", path);
        }
        switch (node.getNodeType()) {
            case NULL -> sb.append("null");
            case BOOLEAN -> sb.append(node.booleanValue() ? "true" : "false");
            case STRING -> writeString(node.textValue(), path, ctx, sb);
            case NUMBER -> writeNumber(node, path, ctx, sb);
            case ARRAY -> writeArray((ArrayNode) node, path, ctx, sb);
            case OBJECT -> writeObject((ObjectNode) node, path, ctx, sb);
            // BINARY / POJO / 其它非纯 JSON 类型。
            default -> throw new CanonicalJsonException(
                    ErrorReason.UNSUPPORTED_VALUE, "不支持的节点类型: " + node.getNodeType(), path);
        }
    }

    private static void writeString(String value, String path, TypeContext ctx, StringBuilder sb) {
        if (ctx.isDecimalPath(path)) {
            // Decimal 路径上的 string 承载精确值 → decimal canonical。
            sb.append(canonicalDecimal(value, path));
        } else {
            sb.append(jsonEscape(value));
        }
    }

    private static void writeNumber(JsonNode node, String path, TypeContext ctx, StringBuilder sb) {
        if (ctx.isDecimalPath(path)) {
            // ★Decimal 路径的 number 也只接受 safe integer（与 TS 一致，Codex 复审 P0#1）：
            // 非整数 / 超 safe-integer 的 number 走 canonicalDecimal 会重引入跨引擎表示隐患，
            // 精确小数必须以 string 承载。故此处先施加 safe-integer 铁律，再交 canonicalDecimal。
            requireSafeIntegerNumber(node, path);
            sb.append(canonicalDecimal(numberToRawString(node, path), path));
            return;
        }
        sb.append(canonicalNumber(node, path));
    }

    /**
     * 施加「number 只允许 safe integer」铁律（供 Decimal 路径的 number 复用），
     * 与 TS 侧 canonicalDecimal 对 number 入参的 Number.isSafeInteger 检查对齐。
     */
    private static void requireSafeIntegerNumber(JsonNode node, String path) {
        if (node.isFloatingPointNumber()) {
            double d = node.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new CanonicalJsonException(ErrorReason.NON_CANONICAL_NUMBER, "Decimal number 非 finite: " + d, path);
            }
            java.math.BigDecimal bd = node.decimalValue();
            if (bd.stripTrailingZeros().scale() > 0) {
                throw new CanonicalJsonException(ErrorReason.NON_INTEGER_NUMBER,
                        "Decimal 路径的 number 只允许 safe integer；精确小数须以 string 承载: " + node.asText(), path);
            }
            checkSafeIntegerRange(bd.toBigIntegerExact(), node.asText(), path);
        } else {
            checkSafeIntegerRange(node.bigIntegerValue(), node.asText(), path);
        }
    }

    private static void checkSafeIntegerRange(java.math.BigInteger bi, String raw, String path) {
        if (bi.abs().compareTo(java.math.BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0) {
            throw new CanonicalJsonException(ErrorReason.NON_INTEGER_NUMBER,
                    "Decimal 路径的 number 超 safe-integer 范围；须以 string 承载: " + raw, path);
        }
    }

    /**
     * 规范化普通 JSON number（非 Decimal 路径）——只允许 safe integer。
     * 与 TS canonicalNumber 对齐：非整数 / 超 safe-integer 一律拒绝（跨引擎浮点表示不一致）。
     */
    private static String canonicalNumber(JsonNode node, String path) {
        if (node.isFloatingPointNumber()) {
            double d = node.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new CanonicalJsonException(ErrorReason.NON_CANONICAL_NUMBER, "number 非 finite: " + d, path);
            }
            // 与 TS/JSON.parse parity：JSON `100.0` 数值即整数 100 → 接受输出 `100`（不按词法拒绝）；
            // 只有**非零小数**（如 100.5）或**超 safe-integer 范围**才拒（NON_INTEGER_NUMBER）。
            // 用 BigDecimal 判断是否恰为整数（stripTrailingZeros 后 scale≤0）且在 safe 范围内。
            java.math.BigDecimal bd = node.decimalValue();
            if (bd.stripTrailingZeros().scale() > 0) {
                throw new CanonicalJsonException(ErrorReason.NON_INTEGER_NUMBER,
                        "非 Decimal number 只允许 safe integer（小数须走 Decimal string + typeCtx）: " + node.asText(), path);
            }
            java.math.BigInteger bi = bd.toBigIntegerExact();
            return checkAndFormatSafeInteger(bi, node.asText(), path);
        }
        // 整数类型（int/long/BigInteger）。
        java.math.BigInteger bi = node.bigIntegerValue();
        return checkAndFormatSafeInteger(bi, node.asText(), path);
    }

    private static String checkAndFormatSafeInteger(java.math.BigInteger bi, String raw, String path) {
        if (bi.abs().compareTo(java.math.BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0) {
            throw new CanonicalJsonException(ErrorReason.NON_INTEGER_NUMBER,
                    "非 Decimal number 超 safe-integer 范围（须走 Decimal string + typeCtx）: " + raw, path);
        }
        // -0 归一为 0（BigInteger 无 -0，天然满足）。十进制整数表示跨引擎一致。
        return bi.toString();
    }

    /** 取 number 节点的原始字符串（供 Decimal 路径解析）。 */
    private static String numberToRawString(JsonNode node, String path) {
        if (node.isFloatingPointNumber()) {
            double d = node.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new CanonicalJsonException(ErrorReason.NON_CANONICAL_NUMBER, "Decimal number 非 finite: " + d, path);
            }
        }
        return node.asText();
    }

    /**
     * 规范化 Decimal 值（decimal canonical form），与 TS canonicalDecimal 字节一致：
     * 无 exponent / 无前导 + / 无无意义前导零 / 无 trailing zero / -0→0 / 整数无 .0。
     * string 入参不 trim（含空白即非法）。
     */
    /** Decimal 语法（缓存 Pattern，避免热路径反复编译，Codex 复审性能项）。 */
    private static final java.util.regex.Pattern DECIMAL_PATTERN =
            java.util.regex.Pattern.compile("^([+-]?)(\\d+)(?:\\.(\\d+))?(?:[eE]([+-]?\\d+))?$");

    static String canonicalDecimal(String raw, String path) {
        // 允许可选符号 + 整数部分 + 可选小数部分 + 可选指数（无前后空白）。
        java.util.regex.Matcher m = DECIMAL_PATTERN.matcher(raw);
        if (!m.matches()) {
            throw new CanonicalJsonException(ErrorReason.NON_CANONICAL_NUMBER, "Decimal 格式非法: \"" + raw + "\"", path);
        }
        String sign = "-".equals(m.group(1)) ? "-" : "";
        String intPart = m.group(2);
        String fracPart = m.group(3) != null ? m.group(3) : "";
        String expStr = m.group(4) != null ? m.group(4) : "0";

        // 资源上限：与 TS 一致用**含符号**的完整指数串长度（Codex 复审 P1#3）。
        if (expStr.length() > 10) {
            throw new CanonicalJsonException(ErrorReason.DECIMAL_TOO_LARGE, "Decimal 指数过大: " + expStr, path);
        }
        // ★用 long 解析防 10 位无符号指数（如 1e9999999999）Integer.parseInt 溢出抛
        //   NumberFormatException（Java-only 拒绝原因偏离 TS，Codex 复审 P1）。long 稳收，
        //   再由 MAX_DECIMAL_EXPONENT 判超限 → 统一 DECIMAL_TOO_LARGE（与 TS 同因）。
        long exp = Long.parseLong(expStr);
        if (Math.abs(exp) > MAX_DECIMAL_EXPONENT) {
            throw new CanonicalJsonException(ErrorReason.DECIMAL_TOO_LARGE,
                    "Decimal 指数超限(" + exp + " > ±" + MAX_DECIMAL_EXPONENT + ")", path);
        }

        // 展开指数：把 int.frac 视为无小数点的数字串，用 exp 决定小数点位置（与 TS 同算法）。
        // exp 已被 MAX_DECIMAL_EXPONENT(±4096) 约束，(int) 转换安全。
        String digits = intPart + fracPart;
        int pointPos = intPart.length() + (int) exp;

        int expandedLen = Math.max(Math.max(digits.length(), pointPos), digits.length() - pointPos) + Math.abs(pointPos);
        if (expandedLen > MAX_DECIMAL_DIGITS) {
            throw new CanonicalJsonException(ErrorReason.DECIMAL_TOO_LARGE,
                    "Decimal 展开位数超限(" + expandedLen + " > " + MAX_DECIMAL_DIGITS + ")", path);
        }

        if (pointPos <= 0) {
            digits = "0".repeat(1 - pointPos) + digits;
            pointPos = 1;
        }
        if (pointPos >= digits.length()) {
            digits = digits + "0".repeat(pointPos - digits.length());
        }
        String newIntPart = digits.substring(0, pointPos);
        String newFracPart = digits.substring(pointPos);

        // 去无意义前导零（保留至少一位）。
        newIntPart = newIntPart.replaceFirst("^0+(?=\\d)", "");
        // 去 trailing zero。
        newFracPart = newFracPart.replaceFirst("0+$", "");

        String body = !newFracPart.isEmpty() ? newIntPart + "." + newFracPart : newIntPart;
        // -0 归一为 0。
        if ("-".equals(sign) && body.matches("^0(?:\\.0*)?$")) {
            return newIntPart; // "0"
        }
        return sign + body;
    }

    private static void writeArray(ArrayNode node, String path, TypeContext ctx, StringBuilder sb) {
        // array 保序（Jackson ArrayNode 无 hole，天然满足 own-property 语义）。
        sb.append('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) sb.append(',');
            write(node.get(i), path + "[" + i + "]", ctx, sb);
        }
        sb.append(']');
    }

    private static void writeObject(ObjectNode node, String path, TypeContext ctx, StringBuilder sb) {
        // object key 按 Unicode code point 升序（与 TS compareByCodePoint 一致，非 UTF-16 code unit 序）。
        List<String> keys = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        keys.sort(CODE_POINT_ORDER);

        sb.append('{');
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonEscape(k)).append(':');
            String childPath = path.isEmpty() ? k : path + "." + k;
            write(node.get(k), childPath, ctx, sb);
        }
        sb.append('}');
    }

    /** 按 Unicode code point 比较（正确处理代理对），与 TS compareByCodePoint 一致。 */
    static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int[] ai = a.codePoints().toArray();
        int[] bi = b.codePoints().toArray();
        int n = Math.min(ai.length, bi.length);
        for (int i = 0; i < n; i++) {
            if (ai[i] != bi[i]) return Integer.compare(ai[i], bi[i]);
        }
        return Integer.compare(ai.length, bi.length);
    };

    /**
     * JSON string 转义 —— 复刻 JS {@code JSON.stringify} 策略（与 TS canonicalString 字节一致）：
     * 短转义 \" \\ \b \f \n \r \t；U+0000..U+001F 其余用 \\u00XX（小写 hex）；不转义 {@code /}；
     * 不转义 U+2028/U+2029；非控制字符（含非 ASCII）原样输出（JSON.stringify 不 ASCII-escape）。
     */
    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
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
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)) {
                        // ★合法代理对（high+low）原样输出；孤立 high surrogate 转义为 backslash-u-XXXX
                        //   （与 JS JSON.stringify 一致，Codex 复审 P0#2）。
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(i + 1));
                            i++; // 跳过已消费的 low surrogate。
                        } else {
                            sb.append("\\u").append(String.format("%04x", (int) c));
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        // 孤立 low surrogate（前面没配对的 high）→ 转义。
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        // 普通非控制字符原样（UTF-8 编码后与 JS 一致）。
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
