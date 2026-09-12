package aster.core.mapping;

import java.math.BigDecimal;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MappingIR —— 「人类文本片段 ↔ Core IR 节点」的可验证映射（ADR 0037 §4/§7）。
 *
 * <p>与 {@code aster-lang-ts} 的 {@code src/mapping/mapping-ir.ts} <b>逐条对等</b>：
 * ADR §7 要求 {@code Verify_TS(mapping) == Verify_Java(mapping)}，故两侧必须对
 * 同一输入给出同一判定。
 *
 * <h2>本模块的范围：只做「机器能证明的那一类」</h2>
 *
 * ADR §3 定的分工：
 *
 * <pre>
 *   LLM / 启发式 / 人   →  提出候选映射（candidate）
 *   确定性 verifier     →  判定 verified / review-required / rejected
 * </pre>
 *
 * <b>AI 可以提出映射，但不能定义什么叫正确。</b> 本类实现那个确定性 verifier，
 * 只覆盖<b>精确值字面量</b>。带业务含义的映射一律
 * {@link Verdict#REVIEW_REQUIRED} —— <b>不猜</b>。
 *
 * <h2>★硬约束：只能读两引擎已经一致的那部分（ADR §5 / §5.1）</h2>
 *
 * 归一化会剥掉 derived analysis（{@code type}/{@code ret}/{@code typeParams}/
 * {@code piiLevel}/{@code effectCaps}/{@code captures} 等）。verifier <b>只能</b>
 * 依赖 {@code kind}、{@code value}、{@code name}、{@code origin}、{@code nodeId}。
 *
 * <p>★ADR §7 原文举的 {@code "$10,000" ↔ Money(10000)} <b>不可验证</b>——
 * {@code Money} 是<b>类型</b>，属两引擎合法分叉层。可验证的写法是
 * {@code ↔ Decimal("10000")}（{@code Decimal} 是<b>节点 kind</b>）。
 */
public final class MappingIr {

    /** 人类文本里的一段（字符偏移，半开区间 {@code [start, end)}）。 */
    public record TextSpan(int start, int end) {}

    /** 一条<b>候选</b>映射：由 LLM / 启发式 / 人提出，尚未判定。 */
    public record CandidateMapping(TextSpan span, String text, String nodeId) {}

    public enum Verdict {
        /** 机器已证明：文本与目标节点的值精确对应。 */
        VERIFIED,
        /** 机器无法证明，需要人确认。<b>不是</b>「错」。 */
        REVIEW_REQUIRED,
        /** 机器已证伪：文本与目标节点的值<b>矛盾</b>。 */
        REJECTED
    }

    /**
     * @param reason 判定依据（人类可读），<b>始终</b>给出——包括 VERIFIED，便于审计复核
     * @param nodeKind 目标节点的 kind；节点不存在时为 {@code null}
     */
    public record VerificationResult(CandidateMapping mapping, Verdict verdict,
                                     String reason, String nodeKind) {}

    /**
     * verifier 需要的最小节点信息——<b>刻意</b>只暴露合法字段，从类型上挡住误用
     * （比如去读 {@code type}）。
     *
     * @param value 字面量的值；非字面量节点为 {@code null}
     * @param name  声明/引用的名字；无名节点为 {@code null}
     */
    public record VerifiableNode(String kind, Object value, String name) {}

    /**
     * 能被机械验证的字面量 kind。
     *
     * <p>★{@code Double} <b>不在</b>此列：它在 IR 里用浮点承载，源码文本与 IR 值
     * <b>不可逆</b>（{@code 1.0} → {@code 1}、{@code 1e3} → {@code 1000}）。机器
     * 无法证明「这段文本就是这个 Double」，故交人，而不是用近似规则假装能证。
     */
    private static final Set<String> EXACT_VALUE_KINDS =
        Set.of("Int", "Long", "Decimal", "String", "Bool", "PatInt");

    /** 人类数字书写：可选货币符号 → 可选正负号 → 数字（可含千分位）→ 可选小数。 */
    private static final Pattern HUMAN_NUMBER =
        Pattern.compile("^[$€£¥]?\\s*([+-]?)(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?$");

    /** 规范化十进制：可选符号 + 整数部分 + 可选小数部分。 */
    private static final Pattern DECIMAL_FORM =
        Pattern.compile("^([+-]?)(\\d+)(?:\\.(\\d*))?$");

    private MappingIr() {}

    /**
     * 验证一条候选映射。
     *
     * @param resolve 按 nodeId 取节点；返回 {@code null} 表示节点不存在
     */
    public static VerificationResult verify(CandidateMapping mapping,
                                            Function<String, VerifiableNode> resolve) {
        TextSpan span = mapping.span();
        if (span.start() < 0 || span.end() <= span.start()) {
            return reject(mapping, "文本区间非法：[" + span.start() + ", " + span.end()
                + ") —— 必须是 start ≥ 0 且 end > start 的区间。");
        }
        if (mapping.text().length() != span.end() - span.start()) {
            // ★区间长度与文本长度必须自洽，否则 span 指向的根本不是这段文本。
            //   不自洽时不能默默采信 text —— 那会让「双向导航」跳到错误位置。
            return reject(mapping, "text 长度 " + mapping.text().length()
                + " 与区间宽度 " + (span.end() - span.start()) + " 不符。");
        }

        VerifiableNode node = resolve.apply(mapping.nodeId());
        if (node == null) {
            return reject(mapping, "目标节点不存在：" + mapping.nodeId());
        }

        if (!EXACT_VALUE_KINDS.contains(node.kind())) {
            // 非精确值节点（Call / If / Func / Double …）：机器证不了，交人。
            // ★这不是失败。ADR §3：Human reviews only what machines cannot prove。
            return new VerificationResult(mapping, Verdict.REVIEW_REQUIRED,
                "目标是 " + node.kind() + " 节点，不属于可机械证明的精确值字面量"
                    + "（Int/Long/Decimal/String/Bool/PatInt）——需人工确认语义对应关系。",
                node.kind());
        }

        Object literal = parseLiteralFromText(mapping.text(), node.kind());
        if (literal == null) {
            return new VerificationResult(mapping, Verdict.REVIEW_REQUIRED,
                "无法从文本「" + mapping.text() + "」中机械解析出 " + node.kind()
                    + " 值——需人工确认。", node.kind());
        }

        if (valuesEqual(literal, node.value(), node.kind())) {
            return new VerificationResult(mapping, Verdict.VERIFIED,
                "文本「" + mapping.text() + "」解析为 " + node.kind() + " " + literal
                    + "，与目标节点的值一致。", node.kind());
        }

        return new VerificationResult(mapping, Verdict.REJECTED,
            "文本「" + mapping.text() + "」解析为 " + literal
                + "，但目标节点的值是 " + node.value() + " —— 两者矛盾。", node.kind());
    }

    private static VerificationResult reject(CandidateMapping m, String reason) {
        return new VerificationResult(m, Verdict.REJECTED, reason, null);
    }

    /**
     * 从人类文本里机械解析出字面量值。返回 {@code null} = 解析不出来（→ 交人复核），
     * <b>不是</b>解析成错的值。
     *
     * <p>★容许人类书写惯例（货币符号、千分位、前后空白）——这正是 MappingIR 要
     * 跨越的鸿沟：{@code "$10,000"} 与 {@code 10000} 之间那一步。
     */
    private static Object parseLiteralFromText(String text, String kind) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        switch (kind) {
            case "Bool" -> {
                String lower = t.toLowerCase();
                if ("true".equals(lower) || "yes".equals(lower)) return Boolean.TRUE;
                if ("false".equals(lower) || "no".equals(lower)) return Boolean.FALSE;
                return null;
            }
            case "String" -> {
                // 带引号则剥掉引号；否则取原文。★不做 trim 之外的任何改写——
                //   字符串的值是什么就是什么，规范化会制造假匹配。
                if (t.length() >= 2
                    && ((t.charAt(0) == '"' && t.endsWith("\""))
                        || (t.charAt(0) == '\'' && t.endsWith("'")))) {
                    return t.substring(1, t.length() - 1);
                }
                return text;
            }
            case "Int", "Long", "PatInt", "Decimal" -> {
                return stripHumanNumberDecorations(t);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 剥掉人类数字书写的装饰，返回<b>规范化十进制字符串</b>；无法识别时返回 {@code null}。
     *
     * <p>★不转成数值类型——Long 超出 {@code double} 安全范围时会静默丢精度
     * （{@code 9007199254740993} → {@code …992}，本仓已实测踩过）。
     */
    private static String stripHumanNumberDecorations(String t) {
        Matcher m = HUMAN_NUMBER.matcher(t);
        if (!m.matches()) {
            return null;
        }
        String sign = "-".equals(m.group(1)) ? "-" : "";
        String intPart = m.group(2).replace(",", "");
        String frac = m.group(3);
        return frac == null ? sign + intPart : sign + intPart + "." + frac;
    }

    /**
     * 按<b>值</b>比较，不按文本。
     *
     * <p>★{@code 100.00m} 在 IR 里是 {@code value:"100"}（尾随零被规范化）。若做
     * 字符串相等，文本 {@code "$10,000.00"} 会验不过一个数值上完全正确的
     * {@code Decimal("10000")}。
     */
    private static boolean valuesEqual(Object fromText, Object fromNode, String kind) {
        if ("Bool".equals(kind) || "String".equals(kind)) {
            return fromText.equals(fromNode);
        }
        String a = canonicalDecimalString(fromText);
        String b = canonicalDecimalString(fromNode);
        return a != null && a.equals(b);
    }

    /**
     * 把数值规范成可比较的十进制字符串：去尾随零、去多余前导零、{@code -0} → {@code 0}。
     *
     * <p>★全程字符串运算，<b>不经过</b> {@code double}——超安全整数的 Long 一旦过一次
     * 浮点就会静默丢精度。
     */
    private static String canonicalDecimalString(Object v) {
        if (v == null) {
            return null;
        }
        String s;
        if (v instanceof String str) {
            s = str.trim();
        } else if (v instanceof BigDecimal bd) {
            s = bd.toPlainString();
        } else if (v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger) {
            s = v.toString();
        } else if (v instanceof Double || v instanceof Float) {
            // Double 本就不参与机械验证（见 EXACT_VALUE_KINDS），走到这里说明调用方
            // 传了非法组合；返回 null 让判定落到「不一致」而非静默比较浮点。
            return null;
        } else {
            return null;
        }

        Matcher m = DECIMAL_FORM.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String sign = "-".equals(m.group(1)) ? "-" : "";
        String intPart = m.group(2).replaceFirst("^0+(?=\\d)", "");
        String frac = m.group(3) == null ? "" : m.group(3).replaceFirst("0+$", "");
        String body = frac.isEmpty() ? intPart : intPart + "." + frac;
        // -0 / -0.0 归一为 0
        return body.matches("^0(?:\\.0*)?$") ? intPart : sign + body;
    }
}
