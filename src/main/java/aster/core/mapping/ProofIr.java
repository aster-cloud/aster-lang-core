package aster.core.mapping;

import aster.core.mapping.MappingIr.CandidateMapping;
import aster.core.mapping.MappingIr.Verdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * ProofIR —— 「这条映射为什么成立」的<b>不可变</b>记录（ADR 0037 §2/§6.1）。
 *
 * <p>与 {@code aster-lang-ts} 的 {@code src/mapping/proof-ir.ts} <b>逐条对等</b>。
 *
 * <h2>回答 ADR §2 提出的五个问题</h2>
 *
 * <pre>
 *   为什么认为这条映射成立？   → rule（判定规则）+ reason（依据）
 *   谁验证的？                 → subject（主体）
 *   使用什么规则？             → rule
 *   对应哪个版本？             → verifiedAgainst.contentHash
 *   现在是否仍然有效？         → isApplicableTo()（算出来，不是存出来）
 * </pre>
 *
 * <h2>★失效语义：不可变 + 水位线（ADR §6.1）</h2>
 *
 * 沿用本仓既有先例（BYOK 的 {@code byokQuotaResetAt} 水位线、审计日志只追加）：
 *
 * <pre>
 *   proof 记录本身   不可变、只追加     ← 「当时确实验证过」是历史事实
 *   proof 的有效性   由内容指纹算出     ← 不改写历史
 * </pre>
 *
 * <p>★<b>为什么不能原地标 stale</b>：proof 的价值就在于「在某个确定版本上、由某个
 * 确定主体、按某条确定规则验证过」。原地改写会<b>销毁这条历史事实</b>——下次审计
 * 无法回答「那次到底验没验过」。这与本仓审计链不可改写的既有立场一致。
 */
public final class ProofIr {

    /** 谁做出的判定。 */
    public enum SubjectKind {
        /** 确定性 verifier（机器证明）。 */
        VERIFIER,
        /** 领域专家（业务语义确认）。 */
        DOMAIN_EXPERT,
        /** 程序员（技术语义确认）。 */
        ENGINEER
    }

    /** @param by 可追溯的身份标识（模块标识 / 人员标识）。 */
    public record ProofSubject(SubjectKind kind, String by) {}

    /**
     * 判定所依据的规则。
     *
     * <p>★与 {@link ProofSubject} <b>分开</b>记录：同一个主体可以按不同规则做判定，
     * 而「按哪条规则」恰恰是审计时最需要回答的。揉进 subject 会让两者都说不清楚。
     *
     * @param version 规则版本——规则本身会演进，旧 proof 必须能说清当时用的哪一版
     */
    public record ProofRule(String id, String version) {}

    /**
     * proof 锚定的目标：某个版本的某个节点。
     *
     * @param contentHash 做出判定时该节点子树的内容指纹（{@code NodeIdMap} 的 contentHash）
     */
    public record ProofAnchor(String nodeId, String contentHash) {}

    /**
     * 一条<b>不可变</b>的 proof 记录。
     *
     * @param recordedAt 记录时间（ISO 8601）。★由调用方传入而非本类取系统时钟——
     *                   取时钟会让同一输入产出不同记录，无法复现、无法测试
     */
    public record Proof(CandidateMapping mapping, Verdict verdict, ProofSubject subject,
                        ProofRule rule, ProofAnchor verifiedAgainst, String reason,
                        String recordedAt) {}

    /** proof 为何对当前版本不适用。 */
    public enum Inapplicability {
        /** 目标节点内容已变 → 需重新验证。<b>不代表</b>当时的判定是错的。 */
        CONTENT_CHANGED,
        /** 目标节点已不存在 → 需重新验证。 */
        NODE_GONE
    }

    /** @param why 不适用的原因；{@code applicable=true} 时为 {@code null} */
    public record Applicability(boolean applicable, Inapplicability why, String currentHash) {}

    /** @param effective 对当前版本有效的那条；无则为空 */
    public record Resolution(Optional<Proof> effective, List<Proof> conflicts) {}

    private ProofIr() {}

    /**
     * 判定一条 proof 对<b>当前</b>版本是否仍适用。
     *
     * <p>★这是<b>算出来</b>的，不是存出来的——proof 记录本身永不修改。
     *
     * @param currentHashOf 按 nodeId 取当前 contentHash；{@code null} 表示节点已不存在
     */
    public static Applicability isApplicableTo(Proof proof,
                                               Function<String, String> currentHashOf) {
        String current = currentHashOf.apply(proof.verifiedAgainst().nodeId());
        if (current == null) {
            return new Applicability(false, Inapplicability.NODE_GONE, null);
        }
        if (!current.equals(proof.verifiedAgainst().contentHash())) {
            return new Applicability(false, Inapplicability.CONTENT_CHANGED, current);
        }
        return new Applicability(true, null, current);
    }

    /**
     * 从一组 proof 中选出对当前版本<b>有效</b>的那条。
     *
     * <h2>★仲裁规则（本仓当前的决定）</h2>
     *
     * <ol>
     *   <li><b>只考虑对当前版本仍适用的</b>（内容指纹匹配）——不适用的直接出局。</li>
     *   <li>在仍适用的里面，<b>取 {@code recordedAt} 最新的一条</b>。</li>
     * </ol>
     *
     * <p>★<b>为什么不按主体优先级</b>（例如「专家 &gt; 机器」）：那需要先定义一个
     * 跨主体的权威序，而这是<b>产品/合规决策</b>，不是技术决策。在它被明确之前，
     * 按时间取最新是唯一不需要额外假设的规则。
     *
     * <p>⚠️ 若将来引入主体优先级，必须同时回答：机器判 REJECTED 而专家判 VERIFIED
     * 时谁赢？本类<b>刻意不猜</b>——{@code conflicts} 把并存的分歧如实暴露。
     */
    public static Resolution resolveEffective(List<Proof> proofs,
                                              Function<String, String> currentHashOf) {
        List<Proof> applicable = new ArrayList<>();
        for (Proof p : proofs) {
            if (isApplicableTo(p, currentHashOf).applicable()) {
                applicable.add(p);
            }
        }
        if (applicable.isEmpty()) {
            return new Resolution(Optional.empty(), List.of());
        }

        // 按时间降序；时间相同则保持输入顺序（sort 是稳定排序），避免引入隐式随机性。
        applicable.sort(Comparator.comparing(Proof::recordedAt).reversed());
        Proof effective = applicable.get(0);

        // 结论不同的其余 proof = 真实分歧，如实暴露，不静默吞掉。
        List<Proof> conflicts = new ArrayList<>();
        for (Proof p : applicable.subList(1, applicable.size())) {
            if (p.verdict() != effective.verdict()) {
                conflicts.add(p);
            }
        }
        return new Resolution(Optional.of(effective), List.copyOf(conflicts));
    }
}
