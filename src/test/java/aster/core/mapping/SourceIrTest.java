package aster.core.mapping;

import aster.core.mapping.SourceIr.SectionKind;
import aster.core.mapping.SourceIr.SourceNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SourceIR（ADR 0037 §2/§10），与 TS 侧
 * {@code test/unit/mapping/source-ir.test.ts} <b>逐条对等</b>。
 *
 * <p>核心契约：<b>只标结构，不改内容</b>。每个节点的 span 必须能逐字节切回原文，
 * 且父必须覆盖子——这两条是「点击原文 ↔ 定位 IR 节点」的前提。
 */
class SourceIrTest {

    private static final String POLICY = String.join("\n",
        "# 付款审批政策", "",
        "## 阈值", "",
        "单笔付款超过 $10,000 时需要财务经理审批。", "",
        "- 低于阈值：自动通过", "",
        "- 高于阈值：转人工", "",
        "## 例外", "",
        "紧急付款可事后补批。", "");

    private static List<SourceNode> allNodes(SourceNode root) {
        List<SourceNode> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(SourceNode n, List<SourceNode> out) {
        out.add(n);
        n.children().forEach(c -> collect(c, out));
    }

    @Test
    @DisplayName("★每个节点的 span 必须能逐字节切回原文（不做任何改写）")
    void everyNodeSliceRoundTrips() {
        SourceNode ir = SourceIr.parse(POLICY);
        assertEquals(List.of(), SourceIr.verifyCoverage(ir, POLICY),
            "覆盖不变式被破坏 —— span 切片与 text 不符或存在重叠。");

        for (SourceNode n : allNodes(ir)) {
            assertEquals(POLICY.substring(n.span().start(), n.span().end()), n.text(),
                n.nodeId() + " 的 text 不等于 span 切片 —— 说明做了文本改写。");
        }
    }

    @Test
    @DisplayName("★标题层级必须嵌套（`##` 归到 `#` 之下）")
    void headingsNest() {
        SourceNode ir = SourceIr.parse(POLICY);

        assertEquals(1, ir.children().size(),
            "顶层应只有一个 `# ` 标题节点 —— 嵌套没建起来。");
        SourceNode h1 = ir.children().get(0);
        assertEquals(1, h1.level());

        long h2s = h1.children().stream().filter(c -> c.kind() == SectionKind.HEADING).count();
        assertEquals(2, h2s, "`## 阈值` 与 `## 例外` 都应挂在 `#` 之下。");
    }

    @Test
    @DisplayName("★父的 span 必须覆盖子（否则 nodeAtOffset 无法下降）")
    void parentSpanContainsChildren() {
        // TS 侧实测踩过：HEADING 原本只覆盖标题那一行，其子段落在父之外，
        // 于是「点击 $10,000 定位所在节点」返回 DOCUMENT 而不是那个段落。
        for (SourceNode n : allNodes(SourceIr.parse(POLICY))) {
            for (SourceNode c : n.children()) {
                assertTrue(c.span().start() >= n.span().start() && c.span().end() <= n.span().end(),
                    c.nodeId() + " 不被父 " + n.nodeId() + " 包含");
            }
        }
    }

    @Test
    @DisplayName("★nodeAtOffset 返回**最深**的命中节点")
    void nodeAtOffsetReturnsDeepest() {
        SourceNode ir = SourceIr.parse(POLICY);
        SourceNode hit = SourceIr.nodeAtOffset(ir, POLICY.indexOf("$10,000"));

        assertNotNull(hit, "offset 应落在某个节点内。");
        assertEquals(SectionKind.PARAGRAPH, hit.kind(),
            "应命中段落而非祖先，实际 " + hit.kind() + "（" + hit.nodeId() + "）"
                + "\n★若返回 DOCUMENT/HEADING，说明包含不变式被破坏、无法逐层下降。");
        assertTrue(hit.text().contains("$10,000"));
    }

    @Test
    @DisplayName("★verifyCoverage 必须能抓出错位的 span（否则它是个假门禁）")
    void coverageCheckCatchesMisalignment() {
        // 反向守卫：手工构造一个 text 与 span 不符的节点，必须被抓。
        SourceNode bad = new SourceNode("doc", SectionKind.DOCUMENT,
            new MappingIr.TextSpan(0, 5), "完全不同的内容", null, List.of());

        assertTrue(!SourceIr.verifyCoverage(bad, POLICY).isEmpty(),
            "text 与 span 不符却没被抓到 —— 这道检查形同虚设。");
    }

    @Test
    @DisplayName("★围栏代码块内的空行不得把代码块撕开")
    void fencedCodeBlockStaysWhole() {
        String doc = String.join("\n",
            "# 标题", "", "```", "line1", "", "line3", "```", "", "正文。", "");

        SourceNode ir = SourceIr.parse(doc);
        assertEquals(List.of(), SourceIr.verifyCoverage(ir, doc));

        List<SourceNode> codeBlocks = allNodes(ir).stream()
            .filter(n -> n.kind() == SectionKind.CODE_BLOCK).toList();
        assertEquals(1, codeBlocks.size(), "代码块应是一个整体节点。");
        assertTrue(codeBlocks.get(0).text().contains("line1")
            && codeBlocks.get(0).text().contains("line3"),
            "代码块内容不完整 —— 空行处被切开了。");
    }

    @Test
    @DisplayName("★文档首尾的空白必须原样保留（text 不得被 trim）")
    void leadingAndTrailingWhitespaceIsPreserved() {
        // 反向守卫。★TS 侧实测发现：若样本首尾没有可 trim 的空白，给 freeze 加
        //   `.trim()` 这个变异**测不出来**——契约说「不改内容」，但没有样本能
        //   证伪它。故显式构造首尾带空白的文档。
        String doc = "\n\n# 标题\n\n正文。\n\n   \n";
        SourceNode ir = SourceIr.parse(doc);

        assertEquals(List.of(), SourceIr.verifyCoverage(ir, doc),
            "首尾空白应原样保留 —— 若这里变红，多半是某处对 text 做了 trim/规范化。");
        assertEquals(doc, ir.text(), "DOCUMENT 的 text 必须逐字节等于原文（含首尾空白）。");
    }

    @Test
    @DisplayName("空文档与纯空白文档不崩")
    void emptyAndBlankDocumentsAreSafe() {
        for (String doc : new String[]{"", "   \n\n  \n"}) {
            SourceNode ir = SourceIr.parse(doc);
            assertEquals(List.of(), SourceIr.verifyCoverage(ir, doc));
            assertEquals(SectionKind.DOCUMENT, ir.kind());
        }
    }

    @Test
    @DisplayName("nodeId 在文档内唯一（否则映射会指向多个节点）")
    void nodeIdsAreUnique() {
        List<String> ids = allNodes(SourceIr.parse(POLICY)).stream().map(SourceNode::nodeId).toList();
        Set<String> unique = new HashSet<>(ids);
        assertEquals(ids.size(), unique.size(), "nodeId 有重复。");
    }

    @Test
    @DisplayName("★跨引擎黄金向量：与 TS 的 SourceIR 逐节点同构（ADR §7）")
    void structureMatchesTheTypeScriptEngine() {
        // ADR §7 要求两引擎对同一输入给出同一结果。下列期望值取自
        // **TS 侧 parseSourceIr 实跑**，不是从 Java 自己的输出回填——后者会让
        // 本测试退化成「Java 和它自己一致」。
        String[] golden = {
            "doc|DOCUMENT|0|87",
            "doc.h[0]|HEADING|0|86",
            "doc.h[0].h[0]|HEADING|10|67",
            "doc.h[0].h[0].p[0]|PARAGRAPH|17|42",
            "doc.h[0].h[0].li[0]|LIST_ITEM|44|55",
            "doc.h[0].h[0].li[1]|LIST_ITEM|57|67",
            "doc.h[0].h[1]|HEADING|69|86",
            "doc.h[0].h[1].p[0]|PARAGRAPH|76|86",
        };

        List<String> actual = allNodes(SourceIr.parse(POLICY)).stream()
            .map(n -> n.nodeId() + "|" + n.kind() + "|" + n.span().start() + "|" + n.span().end())
            .toList();

        assertEquals(List.of(golden), actual,
            "SourceIR 结构与 TS 引擎不一致 —— §7 的双引擎一致性不成立。");
    }
}
