package aster.core.parser;

import aster.core.typecheck.ErrorCode;
import org.antlr.v4.runtime.Token;

import java.math.BigInteger;
import java.util.Map;

/**
 * 数值字面量解析（带范围校验）。
 *
 * <p>存在的理由是**双引擎 parity**。裸用 {@code Integer.parseInt} /
 * {@code Long.parseLong} 时，超限字面量会抛出未捕获的 {@link NumberFormatException}——
 * 它不是 Aster 的诊断类型，会穿透 AstBuilder 直接冒泡给下游调用方（truffle / api），
 * 表现为硬崩溃而非可读的编译错误；而 TS 引擎那侧 {@code parseInt} / {@code BigInt}
 * 反倒**静默接受**（前者在 20 位以上还会丢精度）。同一份源码于是一个引擎崩、一个引擎跑。
 *
 * <p>处理方式沿用 Decimal 有效位上限（ADR 0025）确立的约定：**两侧一律硬拒**，
 * 而不是把 Java 放宽成 BigInteger。抛出的 {@link IllegalStateException} 与
 * AstBuilder 现有的 Decimal 超限路径同型，消息与 TS 侧 L006/L007/L008 对齐。
 */
final class LiteralParsing {

    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private LiteralParsing() {
    }

    /** 解析 Int 字面量；超出 int 范围时抛出结构化错误而非 NumberFormatException。 */
    static int parseInt(String raw, Token at) {
        BigInteger value = toBigInteger(raw, "Integer", at);
        if (value.compareTo(INT_MIN) < 0 || value.compareTo(INT_MAX) > 0) {
            throw outOfRange("Int", raw, at,
                "Integer literal " + raw + " is out of range for Int "
                    + "(-2147483648..2147483647). "
                    + "Add the 'L' suffix for a 64-bit Long, or use a Decimal literal.");
        }
        return value.intValue();
    }

    /** 解析 Long 字面量（调用方需先去掉 L/l 后缀）。 */
    static long parseLong(String raw, Token at) {
        BigInteger value = toBigInteger(raw, "Long", at);
        if (value.compareTo(LONG_MIN) < 0 || value.compareTo(LONG_MAX) > 0) {
            throw outOfRange("Long", raw, at,
                "Long literal " + raw + " is out of range for Long "
                    + "(-9223372036854775808..9223372036854775807). "
                    + "Use a Decimal literal instead.");
        }
        return value.longValue();
    }

    /**
     * 解析 Float 字面量；溢出成 ±Infinity 时硬拒。
     *
     * <p>两引擎的 IEEE 754 都会把超限字面量静默变成 Infinity，让明显不合法的数值继续
     * 参与运算、产出"看起来算出来了"的结果。这里拒掉，与 TS 的 L008 一致。
     */
    static double parseDouble(String raw, Token at) {
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw new ParseDiagnosticException(ErrorCode.PARSE_LITERAL_OUT_OF_RANGE,
                Map.of("kind", "Float", "literal", raw), "Malformed float literal " + raw + ".", at, ex);
        }
        if (!Double.isFinite(value)) {
            throw outOfRange("Float", raw, at,
                "Float literal " + raw + " overflows to Infinity and is not a finite Float. "
                    + "Use a Decimal literal for values beyond the double range.");
        }
        return value;
    }

    private static BigInteger toBigInteger(String raw, String kind, Token at) {
        try {
            return new BigInteger(raw);
        } catch (NumberFormatException ex) {
            throw new ParseDiagnosticException(ErrorCode.PARSE_LITERAL_OUT_OF_RANGE,
                Map.of("kind", "Integer".equals(kind) ? "Int" : kind, "literal", raw),
                "Malformed " + kind + " literal " + raw + ".", at, ex);
        }
    }

    /** E053：字面量越界（ADR 0051 §16 R56）；消息原文不变。 */
    private static ParseDiagnosticException outOfRange(String kind, String raw, Token at, String message) {
        return new ParseDiagnosticException(ErrorCode.PARSE_LITERAL_OUT_OF_RANGE,
            Map.of("kind", kind, "literal", raw), message, at);
    }
}
