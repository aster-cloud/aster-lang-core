package aster.core.mapping;

import aster.core.mapping.MappingIr.TextSpan;
import aster.core.mapping.QuantityIr.EntityCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity 提案的<b>确定性部分</b>（ADR 0037 §3/§12）。
 *
 * <h2>★为什么只移植一半</h2>
 *
 * ADR §7 对双引擎一致性的要求是<b>有区分</b>的：
 *
 * <pre>
 *   LLM 生成的 candidate  **不要求一致**
 *   Verifier 的结果       **必须一致**
 * </pre>
 *
 * 故本类<b>不包含</b> LLM 调用（那在 TS 侧的 {@code entity-proposer.ts}），
 * 只移植两件必须跨引擎一致的东西：
 *
 * <ol>
 *   <li>{@link #validateProposals} —— <b>幻觉闸门</b>：机械校验候选的位置与文本</li>
 *   <li>{@link #verifyCandidate} —— <b>第②段判定</b>：对 Entity 恒
 *       {@code REVIEW_REQUIRED}</li>
 * </ol>
 *
 * <p>★把提出器留在单侧、把判定移植过来，正是 §7 那句话的直接落地：
 * 两个引擎可以用不同的模型（甚至不用模型），但对同一份候选<b>必须给出同一判定</b>。
 */
public final class EntityProposal {

    /** 被丢弃的原始条目及原因。★必须如实报告，静默丢弃会让 LLM 的不可靠性消失。 */
    public record Rejection(String raw, String why) {}

    public record ValidationResult(List<EntityCandidate> candidates, List<Rejection> rejected) {}

    /** 一条未经校验的原始提案（由 LLM 输出解析而来）。 */
    public record RawProposal(String text, String kind, int start) {}

    /**
     * 类别名长度上限。★不是性能考虑——是防「LLM 把整段文本塞进 kind」这类输出，
     * 它会让 reason 变成一大段不可控内容。合法类别名都很短（Role / 角色）。
     */
    private static final int MAX_KIND_LENGTH = 64;

    /**
     * 单次提案的条目数上限。★防「LLM 返回巨量条目」耗尽下游内存。
     * 真实文档的实体数远小于此。超出部分<b>如实报告</b>，不静默截断。
     */
    private static final int MAX_PROPOSALS = 1000;

    /**
     * 类别名禁止的字符：HTML 标签符、引号、控制字符。
     *
     * <p>★{@code kind} 是 <b>LLM 完全可控</b>的自由字符串，且原样进入
     * {@code reason}（人类可读文本，可能被 UI 渲染）。本仓存在
     * {@code dangerouslySetInnerHTML} 用法——一旦有人把 reason 接进去就是
     * 存储型 XSS。
     *
     * <p>★但<b>不能</b>在此做 HTML 转义：转义会改变 kind 的值，而 ADR §12.4
     * 的约定是「proposedKind 原样保留，由第③段的人判断」。转义等于替 LLM
     * 改写了它的输出。正确做法是<b>在源头限制形态</b>——合法类别名本就不含
     * 这些字符。渲染方仍应自行转义（纵深防御）。
     */
    private static final java.util.regex.Pattern CONTROL_OR_MARKUP =
        java.util.regex.Pattern.compile("[<>\"'\\u0000-\\u001f\\u007f]");

    private EntityProposal() {}

    /**
     * <b>幻觉闸门</b>：逐条机械校验 LLM 的提案。
     *
     * <p>★核心判据：声称的位置上必须<b>逐字节</b>是那段文本。LLM 最常见的幻觉是
     * 「文本对但位置错」或「凭空编造的文本」，两者都会让后续的双向导航指向错误
     * 的地方。此处一律丢弃，<b>不做纠正</b>——「猜一下它想说什么」比丢弃更危险。
     *
     * @param offsetBase scope 起点在原文中的偏移（LLM 看到的是切片，偏移要平移回去）
     */
    public static ValidationResult validateProposals(List<RawProposal> proposals,
                                                     String document,
                                                     int offsetBase,
                                                     String proposedBy) {
        List<EntityCandidate> candidates = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();

        if (proposals.size() > MAX_PROPOSALS) {
            // ★不静默截断：调用方必须知道「LLM 返回量异常」这件事。
            return new ValidationResult(List.of(), List.of(new Rejection(
                "<" + proposals.size() + " 条>",
                "条目数超上限（> " + MAX_PROPOSALS + "）——整批拒绝")));
        }

        for (RawProposal p : proposals) {
            String asText = "{text=" + p.text() + ", kind=" + p.kind() + ", start=" + p.start() + "}";

            if (p.text() == null || p.text().isEmpty()
                || p.kind() == null || p.kind().isEmpty()) {
                rejected.add(new Rejection(asText, "缺少 text 或 kind"));
                continue;
            }
            if (CONTROL_OR_MARKUP.matcher(p.kind()).find()) {
                rejected.add(new Rejection(asText,
                    "kind 含标签或控制字符——类别名不应包含这类字符"));
                continue;
            }
            if (p.kind().length() > MAX_KIND_LENGTH) {
                rejected.add(new Rejection(asText,
                    "kind 过长（" + p.kind().length() + " > " + MAX_KIND_LENGTH + "）"));
                continue;
            }

            int start = offsetBase + p.start();
            int end = start + p.text().length();

            if (start < 0 || end > document.length()
                || !document.substring(start, end).equals(p.text())) {
                String actual = (start >= 0 && end <= document.length())
                    ? document.substring(start, end) : "<越界>";
                rejected.add(new Rejection(asText,
                    "位置 " + start + " 上的实际文本是 " + actual + "，与声称的 " + p.text() + " 不符"));
                continue;
            }

            candidates.add(new EntityCandidate(
                new TextSpan(start, end), p.text(), p.kind(), proposedBy));
        }

        return new ValidationResult(List.copyOf(candidates), List.copyOf(rejected));
    }

    /**
     * 对 Entity 候选的<b>确定性判定</b> —— 三段式的第②段。
     *
     * <p>★<b>恒返回 {@code REVIEW_REQUIRED}</b>，这不是偷懒，是 ADR §3/§5.1 的
     * 直接结论：
     *
     * <ul>
     *   <li>Entity 的目标是<b>语义</b>对应（「财务经理」↔ {@code Role.FinanceManager}），
     *       而 verifier 只能读 {@code kind}/{@code value}/{@code name}/{@code origin}/
     *       {@code nodeId}——<b>类型层是两引擎合法分叉的层</b>（§5.1）。</li>
     *   <li>机器能做的只有「这段文本确实存在于此」（已在
     *       {@link #validateProposals} 做过），证明不了「它确实指代那个角色」。</li>
     * </ul>
     *
     * <p>★写成<b>显式方法</b>而非省略，是为了让三段式在代码里可见：谁也不能
     * 跳过第②段直接把 LLM 输出当结论。
     */
    public static MappingIr.VerificationResult verifyCandidate(EntityCandidate candidate) {
        return new MappingIr.VerificationResult(
            new MappingIr.CandidateMapping(candidate.span(), candidate.text(), "<entity>"),
            MappingIr.Verdict.REVIEW_REQUIRED,
            "Entity「" + candidate.text() + "」的类别 " + candidate.proposedKind()
                + " 属**语义**判断，机器只能确认该文本存在于声称位置，"
                + "证明不了它指代该实体 —— 须领域专家确认。",
            null);
    }
}
