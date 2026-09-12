package aster.core.mapping;

import aster.core.mapping.MappingIr.CandidateMapping;
import aster.core.mapping.MappingIr.TextSpan;
import aster.core.mapping.MappingIr.Verdict;
import aster.core.mapping.ProofIr.Inapplicability;
import aster.core.mapping.ProofIr.Proof;
import aster.core.mapping.ProofIr.ProofAnchor;
import aster.core.mapping.ProofIr.ProofRule;
import aster.core.mapping.ProofIr.ProofSubject;
import aster.core.mapping.ProofIr.Resolution;
import aster.core.mapping.ProofIr.SubjectKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProofIR（ADR 0037 §2/§6.1），与 TS 侧
 * {@code test/unit/mapping/proof-ir.test.ts} <b>逐条对等</b>。
 *
 * <p>核心契约：<b>proof 记录不可变，有效性算出来</b>。
 */
class ProofIrTest {

    private static final String NODE = "$.decls{approve}.body.statements[0].cond.args[1]";
    private static final String HASH_V1 = "aaaa1111";
    private static final String HASH_V2 = "bbbb2222";

    private static Proof proof(String recordedAt, Verdict verdict, SubjectKind kind) {
        return new Proof(
            new CandidateMapping(new TextSpan(0, 7), "$10,000", NODE),
            verdict,
            new ProofSubject(kind, kind == SubjectKind.VERIFIER ? "mapping-ir/v1" : "alice"),
            new ProofRule("exact-value", "1"),
            new ProofAnchor(NODE, HASH_V1),
            "文本解析为 Int 10000，与目标节点一致。",
            recordedAt);
    }

    /** 当前版本的 hash 表。 */
    private static Function<String, String> at(String hash) {
        return id -> NODE.equals(id) ? hash : null;
    }

    @Test
    @DisplayName("★内容未变 → proof 仍适用")
    void unchangedContentKeepsProofApplicable() {
        assertTrue(ProofIr.isApplicableTo(
            proof("2026-09-13T00:00:00Z", Verdict.VERIFIED, SubjectKind.VERIFIER),
            at(HASH_V1)).applicable());
    }

    @Test
    @DisplayName("★内容变了 → proof 自动失效，且记录本身不被修改")
    void changedContentInvalidatesProofWithoutMutatingIt() {
        Proof p = proof("2026-09-13T00:00:00Z", Verdict.VERIFIED, SubjectKind.VERIFIER);
        String frozen = p.toString();

        var r = ProofIr.isApplicableTo(p, at(HASH_V2));

        assertFalse(r.applicable(), "内容已变，proof 不应再适用。");
        assertEquals(Inapplicability.CONTENT_CHANGED, r.why());
        assertEquals(HASH_V2, r.currentHash(), "应回报当前 hash，便于排查。");

        // ★记录本身一个字节都没被改 —— 这是「不可变 + 水位线」的关键。
        assertEquals(frozen, p.toString(),
            "proof 记录被修改了 —— 应当只算有效性，绝不原地改写历史事实。");
    }

    @Test
    @DisplayName("节点已不存在 → NODE_GONE（与「内容变了」区分开）")
    void missingNodeIsDistinctFromChangedContent() {
        var r = ProofIr.isApplicableTo(
            proof("2026-09-13T00:00:00Z", Verdict.VERIFIED, SubjectKind.VERIFIER),
            id -> null);

        assertFalse(r.applicable());
        assertEquals(Inapplicability.NODE_GONE, r.why());
    }

    @Test
    @DisplayName("★仲裁：只在「仍适用」的里面取最新")
    void latestApplicableProofWins() {
        Proof old = proof("2026-01-01T00:00:00Z", Verdict.REVIEW_REQUIRED, SubjectKind.VERIFIER);
        Proof recent = proof("2026-09-13T00:00:00Z", Verdict.VERIFIED, SubjectKind.VERIFIER);

        Resolution r = ProofIr.resolveEffective(List.of(old, recent), at(HASH_V1));

        assertTrue(r.effective().isPresent());
        assertEquals("2026-09-13T00:00:00Z", r.effective().get().recordedAt(), "应取最新的一条。");
    }

    @Test
    @DisplayName("★失效的 proof 不得参与仲裁（否则会拿旧版本的结论当现在的答案）")
    void staleProofsAreExcludedFromArbitration() {
        // 反向守卫：没有这条，resolveEffective 可以退化成「无脑取最新」——
        // 上面那条照样绿，而一条早已失效的 proof 会被当成当前有效结论。
        Proof stale = proof("2099-01-01T00:00:00Z", Verdict.VERIFIED, SubjectKind.VERIFIER);

        Resolution r = ProofIr.resolveEffective(List.of(stale), at(HASH_V2));

        assertTrue(r.effective().isEmpty(), "唯一的 proof 已失效，不应选出任何有效结论。");
    }

    @Test
    @DisplayName("★并存的分歧必须如实暴露，不静默吞掉")
    void conflictingVerdictsAreSurfaced() {
        Proof machine = proof("2026-09-13T00:00:00Z", Verdict.REJECTED, SubjectKind.VERIFIER);
        Proof expert = proof("2026-09-12T00:00:00Z", Verdict.VERIFIED, SubjectKind.DOMAIN_EXPERT);

        Resolution r = ProofIr.resolveEffective(List.of(machine, expert), at(HASH_V1));

        assertEquals(Verdict.REJECTED, r.effective().orElseThrow().verdict(), "按时间应取机器那条。");
        assertEquals(1, r.conflicts().size(),
            "专家的相反结论必须出现在 conflicts 里 —— 静默吞掉会让「机器与专家分歧」这件事消失。");
        assertEquals(SubjectKind.DOMAIN_EXPERT, r.conflicts().get(0).subject().kind());
    }

    @Test
    @DisplayName("结论相同的多条 proof 不算冲突")
    void sameVerdictIsNotAConflict() {
        Resolution r = ProofIr.resolveEffective(List.of(
            proof("2026-09-13T00:00:00Z", Verdict.VERIFIED, SubjectKind.VERIFIER),
            proof("2026-09-12T00:00:00Z", Verdict.VERIFIED, SubjectKind.DOMAIN_EXPERT)),
            at(HASH_V1));

        assertEquals(0, r.conflicts().size(), "同结论不应报冲突（否则噪声淹没真分歧）。");
    }

    @Test
    @DisplayName("空输入不崩")
    void emptyInputIsSafe() {
        Resolution r = ProofIr.resolveEffective(List.of(), at(HASH_V1));
        assertTrue(r.effective().isEmpty());
        assertEquals(0, r.conflicts().size());
    }

    @Test
    @DisplayName("★proof 必须记录主体与规则（§2 的「谁验的、按什么规则」）")
    void proofCarriesSubjectAndRule() {
        Proof p = proof("2026-09-13T00:00:00Z", Verdict.VERIFIED, SubjectKind.VERIFIER);

        assertTrue(p.subject().by() != null && !p.subject().by().isBlank(),
            "subject 必须可追溯到具体主体。");
        assertTrue(p.rule().version() != null && !p.rule().version().isBlank(),
            "rule 必须带版本 —— 规则本身会演进，旧 proof 要说清当时用的哪一版。");
    }
}
