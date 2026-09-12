package aster.core.nodeid;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.nodeid.ChangeImpact.Change;
import aster.core.nodeid.ChangeImpact.ChangeKind;
import aster.core.nodeid.ChangeImpact.Rename;
import aster.core.nodeid.NodeIdMap.NodeIdentity;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式 rename 声明（ADR 0037 §8.5 的最后一项未决）。
 *
 * <h2>问题</h2>
 *
 * 命名作用域路径对重命名敏感：改一条规则的名字，其整棵子树的 nodeId 全变，
 * change impact 报成「一堆节点消失 + 一堆节点出现」。实测 16 节点样本：
 *
 * <pre>
 *   改名一条规则 → 31 条变更（15 REMOVED + 15 ADDED + 1 MODIFIED）
 *   而其中 14 / 16 个节点的 contentHash **逐字节未变**
 * </pre>
 *
 * <h2>为什么不能自动推断</h2>
 *
 * 「按 contentHash 自动配对」看似可行，实测不成立——<b>contentHash 不唯一</b>。
 * 见 {@link #contentHashIsNotUniqueSoAutoMatchingIsUnsound()}：两条函数体相同的
 * 规则，其各级子节点 hash 完全相同，改名后无法判定谁是谁。
 *
 * <p>★把两个不同的节点当成同一个，比「识别为新节点」危险得多：前者给出
 * <b>错误</b>的溯源答案，后者只是丢失历史关联。故立场是<b>宁可少认，不可错认</b>。
 */
class RenameDeclarationTest {

    private static final String V1 = """
        Module demo.approve.

        Rule approve given amount, produce:
          If amount greater than 10000:
            Return "REFER".
          Otherwise:
            Return "APPROVE".
        """;

    private static Map<String, NodeIdentity> idsOf(String src) {
        String canonical = new Canonicalizer().canonicalize(src);
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
        return NodeIdMap.compute(new ObjectMapper().valueToTree(core));
    }

    @Test
    @DisplayName("★声明重命名后：纯改名不再产生任何变更噪声")
    void declaringARenameCollapsesTheNoise() {
        String v2 = V1.replace("Rule approve given amount", "Rule assess given amount");
        assertNotEquals(V1, v2, "★replace 未生效则本用例测的是「未编辑」，结论无效。");

        Map<String, NodeIdentity> before = idsOf(V1);
        Map<String, NodeIdentity> after = idsOf(v2);

        // 不声明：整棵子树身份迁移，报成大量 REMOVED + ADDED
        List<Change> withoutDecl = ChangeImpact.diff(before, after);
        assertTrue(withoutDecl.size() > 10,
            "未声明重命名时应产生大量噪声变更（这是待解决的问题本身），实际 "
                + withoutDecl.size());

        // 声明后：子树整体迁移，噪声塌缩
        List<Change> withDecl = ChangeImpact.diff(before, after,
            List.of(new Rename("approve", "assess")));

        // ★不是「零变更」——名字本身确实变了，且 name 是节点内容的一部分：
        //   `Func` 节点自身与 `Module` 根节点的 contentHash **应该**变。
        //   这是**如实报告**，不是噪声：调用方确实需要知道「这条规则改名了」。
        //   我最初断言零变更，是把「消解改名噪声」错当成了「假装什么都没发生」。
        //   真正要消解的是**子树内部**那 15 个 REMOVED + 15 个 ADDED。
        assertEquals(2, withDecl.size(),
            "声明重命名后应只剩 2 条真实变更（Func 自身 + Module 根，因 name 变了）。实际："
                + withDecl);
        assertTrue(withDecl.stream().allMatch(c -> c.kind() == ChangeKind.MODIFIED),
            "剩余变更应全部是 MODIFIED —— 子树身份已迁移，不该再有 REMOVED/ADDED。实际："
                + withDecl);
        assertTrue(withoutDecl.size() > withDecl.size() * 5,
            "声明重命名应显著减少噪声（实测 31 → 2）。实际 "
                + withoutDecl.size() + " → " + withDecl.size());
    }

    @Test
    @DisplayName("★子树内部的身份必须整体迁移（不再有 REMOVED/ADDED）")
    void subtreeIdentitiesMigrateWholesale() {
        // 这条才是重命名声明真正要解决的问题：子树里那些**内容一字未改**的节点，
        // 不应因为祖先改名而被报成「消失 + 新增」。
        String v2 = V1.replace("Rule approve given amount", "Rule assess given amount");
        Map<String, NodeIdentity> before = idsOf(V1);
        Map<String, NodeIdentity> after = idsOf(v2);

        long noiseBefore = ChangeImpact.diff(before, after).stream()
            .filter(c -> c.kind() != ChangeKind.MODIFIED).count();
        long noiseAfter = ChangeImpact.diff(before, after,
                List.of(new Rename("approve", "assess"))).stream()
            .filter(c -> c.kind() != ChangeKind.MODIFIED).count();

        assertTrue(noiseBefore > 0, "未声明时应存在 REMOVED/ADDED 噪声（问题本身）。");
        assertEquals(0, noiseAfter,
            "声明后子树内部不应再有任何 REMOVED/ADDED，实际仍有 " + noiseAfter + " 条。");
    }

    @Test
    @DisplayName("★重命名 + 真实改动：改名噪声消失，真实改动仍被报告")
    void realChangesSurviveTheRenameCollapse() {
        // 反向守卫。没有这条，「声明重命名」可以退化成「把所有变更都吞掉」——
        // 那样上面那条用例照样绿，而 change impact 彻底失效。
        String v2 = V1
            .replace("Rule approve given amount", "Rule assess given amount")
            .replace("10000", "20000");

        List<Change> changes = ChangeImpact.diff(idsOf(V1), idsOf(v2),
            List.of(new Rename("approve", "assess")));

        assertTrue(changes.stream().anyMatch(c -> c.kind() == ChangeKind.MODIFIED),
            "阈值确实改了，必须仍报 MODIFIED。实际：" + changes);
        assertTrue(changes.stream().noneMatch(c -> c.kind() == ChangeKind.REMOVED),
            "改名噪声应已消解，不该有 REMOVED。实际：" + changes);
        // 受影响的规则应以**新**名字出现在 stale 列表里
        assertTrue(changes.stream().flatMap(c -> c.staleAncestors().stream())
                .anyMatch(a -> a.contains("{assess}")),
            "受影响的规则应以新名字 assess 出现在 staleAncestors 里。实际：" + changes);
    }

    @Test
    @DisplayName("★contentHash 不唯一 —— 这正是「必须显式声明、不能自动配对」的依据")
    void contentHashIsNotUniqueSoAutoMatchingIsUnsound() {
        // 两条函数体完全相同的规则。
        String src = """
            Module m.

            Rule alpha, produce:
              Return 1.

            Rule beta, produce:
              Return 1.
            """;
        Map<String, NodeIdentity> ids = idsOf(src);

        NodeIdentity alphaBody = ids.get("$.decls{alpha}.body");
        NodeIdentity betaBody = ids.get("$.decls{beta}.body");

        assertNotEquals(null, alphaBody, "未找到 alpha.body，测试失去被测对象。");
        assertNotEquals(null, betaBody, "未找到 beta.body，测试失去被测对象。");

        assertEquals(alphaBody.contentHash(), betaBody.contentHash(),
            "两条同体规则的 body 应有相同的 contentHash。"
                + "\n★若本条变红，说明 contentHash 已变得唯一——那时可以重新评估"
                + "\n  「自动配对」方案；在此之前，显式声明是唯一可靠的做法。");
    }

    @Test
    @DisplayName("重命名只替换完整路径段，不做子串误伤")
    void renameMatchesWholeSegmentsOnly() {
        // `approve` 不应误伤 `approveAll`。
        String src = """
            Module m.

            Rule approve, produce:
              Return 1.

            Rule approveAll, produce:
              Return 2.
            """;
        String renamed = src.replace("Rule approve,", "Rule assess,");
        assertNotEquals(src, renamed, "★construct 未生效则本用例无效。");

        List<Change> changes = ChangeImpact.diff(idsOf(src), idsOf(renamed),
            List.of(new Rename("approve", "assess")));

        // approveAll 完全没被碰过，不应出现在任何变更里（无论哪种 kind）。
        assertTrue(changes.stream().noneMatch(c -> c.nodeId().contains("approveAll")),
            "approveAll 未被修改，不应出现在变更列表里 —— 多半是把 `approve` 当子串误伤了。"
                + "\n实际：" + changes);
        // 且不应有 REMOVED/ADDED：approve 的子树应整体迁移到 assess。
        assertTrue(changes.stream().allMatch(c -> c.kind() == ChangeKind.MODIFIED),
            "声明后不应再有 REMOVED/ADDED。实际：" + changes);
    }

    @Test
    @DisplayName("自相矛盾的声明直接拒绝，不静默取其一")
    void conflictingRenamesAreRejected() {
        // 静默取其一会给出无声错误的溯源结果，故必须报错。
        assertThrows(IllegalArgumentException.class,
            () -> ChangeImpact.diff(idsOf(V1), idsOf(V1),
                List.of(new Rename("approve", "assess"), new Rename("approve", "review"))),
            "同一个名字被声明重命名到两个不同目标，应当拒绝。");
    }

    @Test
    @DisplayName("非法的 Rename 构造直接拒绝（空名 / 新旧同名）")
    void invalidRenameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Rename("", "x"));
        assertThrows(IllegalArgumentException.class, () -> new Rename("x", " "));
        assertThrows(IllegalArgumentException.class, () -> new Rename(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Rename("same", "same"),
            "新旧同名是无意义的声明，多半是调用方搞错了，应当暴露而非静默忽略。");
    }

    @Test
    @DisplayName("不传 rename 时行为与旧 API 完全一致（向后兼容）")
    void diffWithoutRenamesIsUnchanged() {
        String v2 = V1.replace("10000", "20000");

        assertEquals(ChangeImpact.diff(idsOf(V1), idsOf(v2)),
            ChangeImpact.diff(idsOf(V1), idsOf(v2), List.of()),
            "空 rename 列表应与两参重载等价。");
    }
}
