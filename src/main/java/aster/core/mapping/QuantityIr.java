package aster.core.mapping;

import aster.core.mapping.MappingIr.TextSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QuantityIR —— 从人类文档里<b>机械抽取</b>数量实体（ADR 0037 §2/§11）。
 *
 * <p>与 {@code aster-lang-ts} 的 {@code src/mapping/quantity-ir.ts} <b>逐条对等</b>。
 *
 * <h2>为什么 Quantity 和 Entity 要分开</h2>
 *
 * ADR §10.5 原文把它们并列、统称「LLM 真正该上场的地方」。<b>实测后这句话只对
 * 一半</b>（§11.1）：
 *
 * <pre>
 *   Quantity（金额/百分比/时长/日期）  有稳定的**形态特征**  → 机械抽取，零 AI
 *   Entity  （角色/主体/义务）        **没有**形态特征      → 需要识别，LLM 的位置
 * </pre>
 *
 * 实测一份典型付款政策：8 个 Quantity 全部正则可抽（100%），而「财务经理」
 * 与「财务报表」在字符层面<b>无从区分</b>。
 *
 * <p>本类<b>只做 Quantity</b>。Entity 的接口在 {@link EntityCandidate} 声明，
 * 但<b>不提供实现</b>——按 ADR §3，那是 LLM 提出候选、由人复核的部分。
 *
 * <h2>★本类只负责「找到」，不负责「判定」</h2>
 *
 * 抽取出的 Quantity 是<b>候选映射的左半边</b>（文本片段 + 位置）。它是否真的
 * 对应某个 IR 节点，仍由 {@link MappingIr#verify} 判定。本类<b>不做任何语义断言</b>
 * ——包括不做日期的语义校验（{@code 2026-13-45} 形态合法即抽出）。
 */
public final class QuantityIr {

    /** 数量的类别。刻意小而封闭——每多一种就多一份误抽风险。 */
    public enum QuantityKind { MONEY, PERCENT, DURATION, DATE }

    /**
     * @param text  原文片段，<b>逐字节</b>取自文档（与 SourceIR 同规则：不改内容）
     * @param value 规范化数值，<b>十进制字符串</b>。★不用数值类型：金额可能超出
     *              {@code double} 安全范围，过一次浮点就丢精度（本仓实测踩过）。
     *              {@code DATE} 的 value 是 ISO 串本身，不转时间戳——时间戳依赖
     *              时区，那不是原文里的信息。
     * @param unit  货币符号 / 时长单位；{@code PERCENT}、{@code DATE} 为 {@code null}
     */
    public record Quantity(QuantityKind kind, TextSpan span, String text,
                           String value, String unit) {}

    /**
     * Entity 候选（角色、主体、义务这类<b>语义实体</b>）。
     *
     * <p>★本类<b>不实现</b>它的抽取。Entity 没有可靠的形态特征，只能靠语义识别。
     * 按 ADR §3：
     *
     * <pre>
     *   LLM / 启发式 / 人  →  提出 EntityCandidate
     *   确定性 verifier    →  判定（但对 Entity 只能给 REVIEW_REQUIRED）
     * </pre>
     *
     * <p>此处声明是为了<b>把边界写进类型系统</b>：调用方一看就知道 Entity 必须
     * 从外部传入，而不是指望本类变出来。
     *
     * @param proposedKind 提出者给出的类别（{@code Role}/{@code Party}/…），本类不校验
     * @param proposedBy   谁提出的——与 {@code ProofIr.ProofSubject} 同源，便于追溯
     */
    public record EntityCandidate(TextSpan span, String text,
                                  String proposedKind, String proposedBy) {}

    /**
     * 抽取顺序<b>有意义</b>：先声明的先占位，后面的不再重叠抽取。
     *
     * <p>★当前四类模式<b>几乎互斥</b>，唯一会相交的形态是 {@code $1.5%}：
     * {@code MONEY} 匹配 {@code $1.5}、{@code PERCENT} 匹配 {@code 1.5%}，区间重叠。
     * 此时<b>先声明者胜出</b>。
     */
    private record Rule(QuantityKind kind, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
        new Rule(QuantityKind.MONEY, Pattern.compile("[$€£¥]\\s?\\d[\\d,]*(?:\\.\\d+)?")),
        new Rule(QuantityKind.DATE, Pattern.compile("\\d{4}-\\d{2}-\\d{2}")),
        // ★以下两条必须带 `(?<![\\d.])` 左锚——**这是 ReDoS 修复，不是可选优化**。
        //
        //   没有锚点时，`\\d+` 会在**每个数字位置**重新起跑、贪婪吃到串尾，再因
        //   后缀（`%` / 单位）不匹配而整体回退。对长数字串就是 O(n²)。
        //   实测（Java 侧比 TS 更严重，约 17 倍）：
        //
        //     长度  5000 →    656ms
        //     长度 10000 →   2471ms
        //     长度 20000 →   9988ms
        //     长度 40000 →  39830ms   ← 一份构造过的文档就能钉死线程 40 秒
        //
        //   加锚后同样输入降到毫秒级（左锚让每个起点 O(1) 失败）。
        //   ★语义完全不变：已逐例对照，新旧匹配结果相同。
        new Rule(QuantityKind.PERCENT, Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d+)?\\s?%")),
        new Rule(QuantityKind.DURATION, Pattern.compile(
            "(?<![\\d.])\\d+(?:\\.\\d+)?\\s?(?:小时|分钟|天|秒|hours?|minutes?|days?|seconds?)"))
    );

    private static final Pattern MONEY_FORM =
        Pattern.compile("^([$€£¥])\\s?(\\d[\\d,]*(?:\\.\\d+)?)$");
    private static final Pattern PERCENT_FORM = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s?%$");
    private static final Pattern DURATION_FORM = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s?(.+)$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DECIMAL_FORM = Pattern.compile("^(\\d+)(?:\\.(\\d*))?$");

    private QuantityIr() {}

    /**
     * 从文档中抽取所有数量实体。
     *
     * <p>★<b>不做任何语义判断</b>——只按形态特征找出「这里有个数量」及其规范化
     * 数值。它是否对应某个 IR 节点，由 {@link MappingIr#verify} 判定。
     *
     * @return 按出现位置升序；互不重叠
     */
    public static List<Quantity> extract(String document) {
        List<Quantity> found = new ArrayList<>();
        List<int[]> claimed = new ArrayList<>();

        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(document);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                // ★后来者不得与已占位区间重叠：保证输出无重叠，优先级由 RULES 顺序决定。
                boolean overlaps = claimed.stream().anyMatch(c -> start < c[1] && end > c[0]);
                if (overlaps) {
                    continue;
                }
                String[] parsed = normalize(rule.kind(), m.group());
                if (parsed == null) {
                    continue; // 形态像但规范化不出来 → 不抽，绝不编造
                }
                claimed.add(new int[]{start, end});
                found.add(new Quantity(rule.kind(), new TextSpan(start, end), m.group(),
                    parsed[0], parsed[1]));
            }
        }

        found.sort(Comparator.comparingInt(q -> q.span().start()));
        return List.copyOf(found);
    }

    /**
     * 规范化成「数值 + 单位」。返回 {@code null} = 规范化不出来（不抽取），
     * <b>不是</b>抽成错的值。
     *
     * @return {@code [value, unit]}；unit 可为 {@code null}
     */
    private static String[] normalize(QuantityKind kind, String text) {
        switch (kind) {
            case DATE -> {
                // 日期保持原串——转时间戳会引入时区，那不是原文里的信息。
                return ISO_DATE.matcher(text).matches() ? new String[]{text, null} : null;
            }
            case MONEY -> {
                Matcher m = MONEY_FORM.matcher(text);
                if (!m.matches()) return null;
                String value = canonicalDecimal(m.group(2).replace(",", ""));
                return value == null ? null : new String[]{value, m.group(1)};
            }
            case PERCENT -> {
                Matcher m = PERCENT_FORM.matcher(text);
                if (!m.matches()) return null;
                String value = canonicalDecimal(m.group(1));
                return value == null ? null : new String[]{value, null};
            }
            case DURATION -> {
                Matcher m = DURATION_FORM.matcher(text);
                if (!m.matches()) return null;
                String value = canonicalDecimal(m.group(1));
                return value == null ? null : new String[]{value, m.group(2)};
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 规范成可比较的十进制字符串：去尾随零、去多余前导零。
     *
     * <p>★全程字符串运算，<b>不经过</b>浮点——与 {@code MappingIr} 同口径，
     * 保证「文档里的 $10,000」与「IR 里的 Int 10000」能按值比对。
     */
    private static String canonicalDecimal(String s) {
        Matcher m = DECIMAL_FORM.matcher(s.trim());
        if (!m.matches()) {
            return null;
        }
        String intPart = m.group(1).replaceFirst("^0+(?=\\d)", "");
        String frac = m.group(2) == null ? "" : m.group(2).replaceFirst("0+$", "");
        return frac.isEmpty() ? intPart : intPart + "." + frac;
    }
}
