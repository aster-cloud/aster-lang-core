package aster.core.mapping;

import aster.core.mapping.EntityProposal.RawProposal;
import aster.core.mapping.EntityProposal.ValidationResult;
import aster.core.mapping.MappingIr.Verdict;
import aster.core.mapping.QuantityIr.EntityCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entity 提案的确定性部分（ADR 0037 §3/§12），与 TS 侧
 * {@code test/unit/mapping/entity-proposer.test.ts} 的**判定相关用例**对等。
 *
 * <p>★只对等「幻觉闸门 + 第②段判定」——§7 明确：LLM 生成的 candidate
 * <b>不要求</b>跨引擎一致，<b>Verifier 的结果必须一致</b>。故提出器本身
 * 留在 TS 单侧，不在此移植。
 */
class EntityProposalTest {

    private static final String DOC = String.join("\n",
        "# 付款审批政策", "",
        "单笔付款超过 $10,000 时需要财务经理审批。",
        "低于该额度由部门主管批准。", "");

    private static final String BY = "llm:fake/test-model";

    /** 造一条「位置与文本都正确」的提案。 */
    private static RawProposal truthful(String text) {
        return new RawProposal(text, "Role", DOC.indexOf(text));
    }

    @Test
    @DisplayName("★位置与文本都对的候选被接受")
    void truthfulProposalIsAccepted() {
        ValidationResult r = EntityProposal.validateProposals(
            List.of(truthful("财务经理")), DOC, 0, BY);

        assertEquals(1, r.candidates().size(), "应接受 1 条，rejected=" + r.rejected());
        EntityCandidate c = r.candidates().get(0);
        assertEquals("财务经理", c.text());
        assertEquals("Role", c.proposedKind());
        assertEquals(BY, c.proposedBy());
        assertEquals("财务经理", DOC.substring(c.span().start(), c.span().end()));
    }

    @Test
    @DisplayName("★幻觉闸门：文本不在声称位置上 → 丢弃并报告")
    void wrongPositionIsRejected() {
        // LLM 最常见的幻觉：文本对但位置错。这会让双向导航指向错误的地方。
        ValidationResult r = EntityProposal.validateProposals(
            List.of(new RawProposal("财务经理", "Role", 0)), DOC, 0, BY);

        assertEquals(0, r.candidates().size(), "位置错的候选必须丢弃。");
        assertEquals(1, r.rejected().size());
        assertTrue(r.rejected().get(0).why().contains("不符"),
            "应说明不符，实际：" + r.rejected().get(0).why());
    }

    @Test
    @DisplayName("★幻觉闸门：文档里根本没有的文本 → 丢弃")
    void inventedTextIsRejected() {
        ValidationResult r = EntityProposal.validateProposals(
            List.of(new RawProposal("首席合规官", "Role", 20)), DOC, 0, BY);

        assertEquals(0, r.candidates().size(), "文档里不存在的文本必须丢弃。");
        assertEquals(1, r.rejected().size());
    }

    @Test
    @DisplayName("★被丢弃的条目必须如实报告，不得静默吞掉")
    void rejectionsAreReported() {
        // 反向守卫：若 rejected 恒为空，「LLM 产出了不合规内容」这件事就消失了，
        // 而那恰恰是评估模型可靠性最重要的信号。
        ValidationResult r = EntityProposal.validateProposals(List.of(
            truthful("财务经理"),                                   // 对
            new RawProposal("不存在的角色", "Role", 5),              // 错
            new RawProposal("部门主管", "Party", 999),               // 越界
            new RawProposal("", "Role", 0)                          // 空文本
        ), DOC, 0, BY);

        assertEquals(1, r.candidates().size(), "只应接受那条正确的。");
        assertEquals(3, r.rejected().size(), "三条不合规都要报告，实际 " + r.rejected());
        assertTrue(r.rejected().stream().allMatch(x -> !x.why().isBlank()),
            "每条丢弃都要说明原因。");
    }

    @Test
    @DisplayName("★scope 偏移必须平移回原文（否则候选整体错位）")
    void scopeOffsetIsTranslatedBack() {
        int base = 10;
        int inSlice = DOC.substring(base).indexOf("财务经理");
        ValidationResult r = EntityProposal.validateProposals(
            List.of(new RawProposal("财务经理", "Role", inSlice)), DOC, base, BY);

        assertEquals(1, r.candidates().size(),
            "切片内偏移未被平移回原文 —— rejected=" + r.rejected());
        assertEquals(base + inSlice, r.candidates().get(0).span().start());
    }

    @Test
    @DisplayName("★第②段恒判 REVIEW_REQUIRED —— 机器不得替人下结论")
    void verdictIsAlwaysReviewRequired() {
        // ADR §3：AI 可以提出映射，但不能定义什么叫正确。
        // Entity 的目标是**语义**对应，而 verifier 只能读 kind/value/name/origin
        // （类型层是两引擎合法分叉的层，见 §5.1）。
        EntityCandidate c = EntityProposal.validateProposals(
            List.of(truthful("财务经理")), DOC, 0, BY).candidates().get(0);

        var r = EntityProposal.verifyCandidate(c);

        assertEquals(Verdict.REVIEW_REQUIRED, r.verdict());
        assertTrue(r.reason().contains("语义"), "判定理由应说明这是语义判断。");
    }

    @Test
    @DisplayName("★跨引擎一致：同一候选，Java 与 TS 给出同一判定（§7）")
    void verdictMatchesTheTypeScriptEngine() {
        // §7 原文：「LLM 生成的 candidate 不要求一致，**Verifier 的结果必须一致**」。
        // 故此处钉的是**判定**，而不是提出器的输出。
        //
        // TS 侧 verifyEntityCandidate 对任何候选恒返回 REVIEW_REQUIRED（实跑确认），
        // Java 侧必须相同——包括对「类别五花八门」的候选。
        for (String kind : new String[]{"Role", "Party", "Obligation", "完全没见过的类别"}) {
            EntityCandidate c = new EntityCandidate(
                new MappingIr.TextSpan(0, 2), "付款", kind, BY);
            assertEquals(Verdict.REVIEW_REQUIRED, EntityProposal.verifyCandidate(c).verdict(),
                "类别 " + kind + " 的判定与 TS 不一致 —— §7 的双引擎一致性不成立。");
        }
    }

    @Test
    @DisplayName("类别体系刻意不校验（proposedKind 是自由字符串）")
    void proposedKindIsNotValidated() {
        // ★类别体系是**领域决策**，由第③段的人确定。过早固化成枚举会把
        //   LLM 输出硬塞进错误的格子——那种错误比「类别不统一」难发现得多。
        ValidationResult r = EntityProposal.validateProposals(
            List.of(new RawProposal("财务经理", "某个前所未见的类别", DOC.indexOf("财务经理"))),
            DOC, 0, BY);

        assertEquals(1, r.candidates().size(), "自由类别不应被拒。");
        assertEquals("某个前所未见的类别", r.candidates().get(0).proposedKind());
    }
}
