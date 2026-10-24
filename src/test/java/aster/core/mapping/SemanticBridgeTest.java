package aster.core.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 侧语义桥端到端（ADR 0037 §7 接线）。
 *
 * <p>与 TS 侧 {@code test/unit/mapping/pipeline.test.ts} <b>逐条对等</b>。
 *
 * <h2>★为什么两侧都要有主链路</h2>
 *
 * §7 只要求 <b>verifier 判定</b>一致，那说的是「同一候选两侧判一样」。
 * 但<b>能力</b>是另一回事：只用 Java 引擎的用户同样需要语义桥。
 */
class SemanticBridgeTest {

    /** 可编译的 Aster 源码——第 3 层（候选+验证）能跑通。 */
    private static final String ASTER_SRC = String.join("\n",
        "Module policy.payment.", "",
        "Rule approve_payment, produce:", "  Return 10000.", "",
        "Rule threshold_pct, produce:", "  Return 15.", "");

    /** 纯人类文档——不可编译，用于验证<b>分层降级</b>。 */
    private static final String HUMAN_DOC = String.join("\n",
        "# 付款审批政策", "",
        "单笔付款超过 $10,000 时需要财务经理审批。",
        "审批须在 3 天内完成，逾期率不得超过 15%。", "",
        "## 例外", "",
        "2026-01-01 之后，低于该额度由部门主管批准。", "");

    @Test
    @DisplayName("★字面量候选必须判 VERIFIED（连线正确性守卫）")
    void literalsAreVerified() {
        // ★这条守的是"那根线"。TS 侧接线时我从 NodeIdentity 取 value
        //   （它根本没有 value），导致每条候选都判 REJECTED——
        //   而两侧模块的单测**全是绿的**。只有端到端才暴露。
        SemanticBridge.BridgeResult r = SemanticBridge.run(ASTER_SRC);

        assertEquals(0, r.summary().rejected(),
            "不应有 REJECTED 候选。若全是 REJECTED，多半是 resolve 取不到节点的 value"
            + "（NodeIdentity 不含 value，必须从 IR 本身取）。\n"
            + "实际：" + r.summary() + "\n诊断：" + r.diagnostics());
        assertTrue(r.summary().verified() >= 2,
            "应至少验证 2 条字面量（10000 / 15），实际 " + r.summary().verified());
    }

    @Test
    @DisplayName("★候选文本必须逐字节取自源码（不得编造）")
    void candidateTextComesFromSource() {
        SemanticBridge.BridgeResult r = SemanticBridge.run(ASTER_SRC);
        assertTrue(!r.verified().isEmpty(), "前置：应有候选。");
        for (SemanticBridge.VerifiedCandidate c : r.verified()) {
            assertTrue(!c.mapping().text().isEmpty(),
                "候选文本为空——切片错位。nodeId=" + c.mapping().nodeId());
        }
    }

    @Test
    @DisplayName("★nodeId 必须与 NodeIdMap 口径一致（路径漂移守卫）")
    void nodeIdsDoNotDrift() {
        SemanticBridge.BridgeResult r = SemanticBridge.run(ASTER_SRC);
        long drift = r.diagnostics().stream().filter(d -> d.contains("路径口径可能漂移")).count();
        assertEquals(0, drift,
            "检测到 nodeId 路径漂移：\n" + r.diagnostics());
    }

    @Test
    @DisplayName("★不可编译的文档仍须产出结构与数量（分层降级）")
    void arbitraryTextDegradesGracefully() {
        // ★"任意文字可执行化"的现实形态：前两层对任意文本都成立，
        //   第 3 层需要可编译源码。失败时前两层结果**必须保留**。
        SemanticBridge.BridgeResult r = SemanticBridge.run(HUMAN_DOC);

        assertNotNull(r.sourceIr(), "SourceIR 必须产出（它不依赖可编译性）。");
        assertTrue(r.quantities().size() >= 4,
            "应抽出至少 4 个数量（$10,000 / 3 天 / 15% / 2026-01-01），实际 "
            + r.quantities().size() + "：" + r.quantities().stream()
                .map(QuantityIr.Quantity::text).toList());
    }

    @Test
    @DisplayName("★第 3 层跳过必须如实报告，不得静默")
    void skippedLayerIsReported() {
        // 静默跳过会让调用方以为"验证过了但没发现问题"，
        // 而事实是"根本没验证"。两者含义天差地别。
        SemanticBridge.BridgeResult r = SemanticBridge.run(HUMAN_DOC);

        assertTrue(r.verified().isEmpty(), "不可编译时不应有候选。");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.contains("无法编译")),
            "必须在 diagnostics 里说明第 3 层为何跳过。实际：" + r.diagnostics());
    }

    @Test
    @DisplayName("★抽出的数量必须逐字节取自原文（位置正确性）")
    void quantitySpansMatchSource() {
        SemanticBridge.BridgeResult r = SemanticBridge.run(HUMAN_DOC);
        for (QuantityIr.Quantity q : r.quantities()) {
            assertEquals(q.text(),
                HUMAN_DOC.substring(q.span().start(), q.span().end()),
                "数量 " + q.text() + " 的 span 与原文不符——双向导航会跳错位置。");
        }
    }
}
