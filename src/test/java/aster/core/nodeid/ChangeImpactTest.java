package aster.core.nodeid;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.nodeid.ChangeImpact.Change;
import aster.core.nodeid.ChangeImpact.ChangeKind;
import aster.core.nodeid.NodeIdMap.NodeIdentity;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0037 §4 的核心能力：跨版本 change impact。
 *
 * <p>每条用例都锚在一个**具体的产品承诺**上（「改阈值 → 立刻知道哪条规则 stale」），
 * 而不是锚在实现细节（路径字符串长什么样）上——后者会在重构时变红却证明不了任何事。
 */
class ChangeImpactTest {

    private static final String V1 = """
        Module demo.approve.

        Rule approve given amount, produce:
          If amount greater than 10000:
            Return "REFER".
          Otherwise:
            Return "APPROVE".
        """;

    /** 完整链路：源码 → canonicalize → parse → lower → Core IR JSON。 */
    private static JsonNode ir(String src) {
        String canonical = new Canonicalizer().canonicalize(src);
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
        return new ObjectMapper().valueToTree(core);
    }

    private static Map<String, NodeIdentity> idsOf(String src) {
        return NodeIdMap.compute(ir(src));
    }

    @Test
    @DisplayName("★改阈值：节点身份不变、内容指纹变 —— 这正是 change impact 的前提")
    void changingAThresholdKeepsIdentityButChangesContent() {
        // ADR 0037 §4 的原始场景：$10,000 → $20,000，要能说出「还是那个阈值节点，值变了」。
        String v2 = V1.replace("10000", "20000");
        assertNotEquals(V1, v2, "★replace 未生效则本用例测的是「未编辑」，结论无效。");

        Map<String, NodeIdentity> before = idsOf(V1);
        Map<String, NodeIdentity> after = idsOf(v2);

        List<Change> changes = ChangeImpact.diff(before, after);

        // 关键契约 1：不能出现「旧节点消失 + 新节点出现」——那说明身份没稳住。
        assertFalse(changes.stream().anyMatch(c -> c.kind() == ChangeKind.ADDED),
            "改一个字面量不应产生 ADDED 节点，实际：" + changes);
        assertFalse(changes.stream().anyMatch(c -> c.kind() == ChangeKind.REMOVED),
            "改一个字面量不应产生 REMOVED 节点，实际：" + changes);

        // 关键契约 2：必须有 MODIFIED，否则 change impact 根本没察觉到这次修改。
        assertTrue(changes.stream().anyMatch(c -> c.kind() == ChangeKind.MODIFIED),
            "改了阈值却没报告任何 MODIFIED —— change impact 失效。");

        // 关键契约 3：包含该阈值的那条规则必须被标为 stale（§4 的「谁受影响」）。
        boolean ruleIsStale = changes.stream()
            .flatMap(c -> c.staleAncestors().stream())
            .anyMatch(a -> a.contains("{approve}"));
        assertTrue(ruleIsStale,
            "改了 approve 规则内的阈值，该规则应出现在 staleAncestors 里。实际变更：" + changes);
    }

    @Test
    @DisplayName("★在前面插入一条无关规则：原有规则的身份必须纹丝不动")
    void insertingARuleBeforeMustNotDisturbExistingIdentities() {
        // 这是纯下标路径方案的灾难场景（实测 5/16 存活）。命名作用域路径应完全免疫。
        String v2 = V1.replace("Rule approve",
            "Rule noop, produce:\n  Return \"X\".\n\nRule approve");
        assertNotEquals(V1, v2, "★replace 未生效则本用例无效。");

        Map<String, NodeIdentity> before = idsOf(V1);
        Map<String, NodeIdentity> after = idsOf(v2);

        // 旧版每一个节点，其**身份**都必须在新版里存在。
        // ★contentHash 则只对「真的没被改动」的节点要求不变：根节点 `$`（Module）
        //   的 decls 列表确实多了一条规则，它的内容**本就应该**变——把它一并要求
        //   不变，是我第一版断言写过头了。契约是「祖先 stale、后代不受扰」，
        //   而不是「插入后整棵树一个字节都不许动」。
        for (Map.Entry<String, NodeIdentity> e : before.entrySet()) {
            NodeIdentity now = after.get(e.getKey());
            assertNotEquals(null, now,
                "插入无关规则后，原节点 " + e.getKey() + " 的身份丢失了"
                    + "\n★这会让 change impact 把「没动过的规则」误报成被删除。");
            if (!"$".equals(e.getKey())) {
                assertEquals(e.getValue().contentHash(), now.contentHash(),
                    "原节点 " + e.getKey() + " 内容未变，contentHash 却变了"
                        + "\n★多半是 contentHash 混进了位置信息（origin 未剥干净）。");
            }
        }

        // 反向守卫：根节点**必须**变——它是「这个模块多了一条规则」的唯一体现。
        // 若它也不变，说明 contentHash 没有真正反映子树内容，change impact 会漏报。
        assertNotEquals(before.get("$").contentHash(), after.get("$").contentHash(),
            "模块新增了一条规则，根节点 contentHash 却没变 —— 内容指纹失效。");

        // 反向：新规则必须被识别为 ADDED，否则「插入」这件事被漏报。
        List<Change> changes = ChangeImpact.diff(before, after);
        assertTrue(changes.stream().anyMatch(c -> c.kind() == ChangeKind.ADDED),
            "插入了一条新规则却没有任何 ADDED，实际：" + changes);
    }

    @Test
    @DisplayName("未修改的程序：不得报告任何变更（防止 change impact 恒报 stale）")
    void identicalProgramsYieldNoChanges() {
        // 若本用例变红，说明 nodeId 或 contentHash 里混进了非确定性内容
        // （随机序、时间戳、位置…）——那会让每次编译都「全量 stale」，能力等于没有。
        assertEquals(List.of(), ChangeImpact.diff(idsOf(V1), idsOf(V1)),
            "同一份源码两次编译不应产生任何变更。");
    }

    @Test
    @DisplayName("★contentHash 必须剥掉 origin：位置不是内容")
    void contentHashMustIgnorePosition() {
        // 在**文件开头**加一行注释：所有代码整体下移一行，位置全变、内容全同。
        String v2 = "# 一行注释\n" + V1;
        assertNotEquals(V1, v2, "★构造未生效则本用例无效。");

        Map<String, NodeIdentity> before = idsOf(V1);
        Map<String, NodeIdentity> after = idsOf(v2);

        assertEquals(List.of(), ChangeImpact.diff(before, after),
            "只加了一行注释（位置变、内容不变），却报告了变更。"
                + "\n★说明 contentHash 未剥干净 origin —— 这会让每次挪动代码都触发"
                + "\n  全量 stale，change impact 沦为噪声。");
    }

    @Test
    @DisplayName("已知局限：重命名规则会使其子树身份迁移（如实暴露，不掩盖）")
    void renamingIsAnIntentionalIdentityMigration() {
        // ADR 0037 §8.5 记录的固有代价（实测 1/16 存活）。本用例把它**钉成已知行为**，
        // 而不是让它在生产里以「一条规则凭空消失 + 一条凭空出现」的形式被发现。
        String v2 = V1.replace("Rule approve given amount", "Rule assess given amount");
        assertNotEquals(V1, v2, "★replace 未生效则本用例无效。");

        List<Change> changes = ChangeImpact.diff(idsOf(V1), idsOf(v2));

        assertTrue(changes.stream().anyMatch(c -> c.kind() == ChangeKind.REMOVED),
            "重命名应表现为旧身份 REMOVED（需由显式 rename 声明消解），实际：" + changes);
        assertTrue(changes.stream().anyMatch(c -> c.kind() == ChangeKind.ADDED),
            "重命名应表现为新身份 ADDED，实际：" + changes);
    }

    @Test
    @DisplayName("stale 只向上传播，不向下 —— 否则影响面被夸大到整棵子树")
    void stalenessPropagatesUpwardOnly() {
        String v2 = V1.replace("10000", "20000");
        List<Change> changes = ChangeImpact.diff(idsOf(V1), idsOf(v2));

        for (Change c : changes) {
            for (String ancestor : c.staleAncestors()) {
                assertTrue(c.nodeId().startsWith(ancestor),
                    "staleAncestors 含非祖先项：节点 " + c.nodeId() + " 的 " + ancestor
                        + "\n★若把后代也标 stale，改一个字面量会波及整棵子树，影响面失真。");
                assertNotEquals(c.nodeId(), ancestor, "节点自身不应出现在 staleAncestors 里。");
            }
        }
    }
}
