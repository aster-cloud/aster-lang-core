package aster.core.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双引擎<b>主链路</b>输出一致性 —— Java 侧（ADR 0037 §7 扩展）。
 *
 * <h2>★与 {@link MappingIrVerdictParityTest} 的区别</h2>
 *
 * <ul>
 *   <li>那个钉的是 verifier 对<b>单条候选</b>的判定</li>
 *   <li><b>本文件</b>钉的是<b>整条主链路</b>：文档结构 + 数量 + 候选 + 判定</li>
 * </ul>
 *
 * <h2>为什么需要它</h2>
 *
 * §7 只要求 verifier 判定一致，但<b>能力</b>是另一回事——
 * 只用 Java 引擎的用户同样需要语义桥。两侧都接线后，必须证明
 * 「同一份文档，两个引擎给出同样的结构、同样的数量、同样的候选与判定」。
 *
 * <p>★语料<b>单源</b>：TS 侧 {@code bridge-parity.test.ts} 读同一文件。
 */
class SemanticBridgeParityTest {

    /** ★两种布局都要支持：CI 检出到工作区内 / 本地兄弟仓并列。 */
    private static final Path CORPUS = resolveCorpus();

    private static Path resolveCorpus() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path rel = Path.of("corpus", "mapping-verdict", "bridge-cases.json");
        for (Path base : new Path[]{
            cwd.resolve("aster-lang-test"),
            cwd.resolve("..").resolve("aster-lang-test"),
        }) {
            Path p = base.resolve(rel).normalize();
            if (Files.exists(p)) return p;
        }
        return cwd.resolve("..").resolve("aster-lang-test").resolve(rel).normalize();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("★语料必须存在（缺失即门禁失效，不得静默跳过）")
    void corpusExists() {
        assertTrue(Files.exists(CORPUS),
            "共享语料不存在：" + CORPUS + "\n"
            + "★本门禁依赖 aster-lang-test 的 checkout。路径变更须同步改两侧。");
    }

    @Test
    @DisplayName("★主链路输出必须与语料声明逐字段一致（与 TS 侧同源）")
    void bridgeOutputMatchesSharedCorpus() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(CORPUS));
        JsonNode cases = root.path("cases");
        assertTrue(cases.size() >= 2, "语料用例过少（" + cases.size() + "）。");

        for (JsonNode c : cases) {
            String name = c.path("name").asText();
            SemanticBridge.BridgeResult r = SemanticBridge.run(c.path("source").asText());

            JsonNode es = c.path("expectSummary");
            assertEquals(es.path("verified").asInt(), r.summary().verified(),
                "用例「" + name + "」verified 数不符。诊断：" + r.diagnostics());
            assertEquals(es.path("reviewRequired").asInt(), r.summary().reviewRequired(),
                "用例「" + name + "」reviewRequired 数不符。");
            assertEquals(es.path("rejected").asInt(), r.summary().rejected(),
                "用例「" + name + "」rejected 数不符。诊断：" + r.diagnostics());

            List<String> actualCands = new ArrayList<>();
            for (SemanticBridge.VerifiedCandidate v : r.verified()) {
                actualCands.add(v.result().verdict() + "|" + v.mapping().text() + "|"
                    + v.mapping().nodeId() + "|" + v.mapping().span().start()
                    + "-" + v.mapping().span().end());
            }
            List<String> expectCands = new ArrayList<>();
            for (JsonNode e : c.path("expectCandidates")) {
                expectCands.add(e.path("verdict").asText() + "|" + e.path("text").asText() + "|"
                    + e.path("nodeId").asText() + "|" + e.path("start").asInt()
                    + "-" + e.path("end").asInt());
            }
            assertEquals(expectCands, actualCands,
                "用例「" + name + "」候选不符。\n"
                + "★若 TS 侧同用例输出不同，即两引擎主链路已分叉。");

            List<String> actualQtys = new ArrayList<>();
            for (QuantityIr.Quantity q : r.quantities()) {
                actualQtys.add(q.kind() + "|" + q.text() + "|" + q.span().start()
                    + "-" + q.span().end() + "|" + q.value());
            }
            List<String> expectQtys = new ArrayList<>();
            for (JsonNode e : c.path("expectQuantities")) {
                expectQtys.add(e.path("kind").asText() + "|" + e.path("text").asText() + "|"
                    + e.path("start").asInt() + "-" + e.path("end").asInt()
                    + "|" + e.path("value").asText());
            }
            assertEquals(expectQtys, actualQtys, "用例「" + name + "」数量抽取不符。");

            JsonNode diag = c.path("expectDiagnosticContains");
            if (!diag.isMissingNode()) {
                String needle = diag.asText();
                assertTrue(r.diagnostics().stream().anyMatch(d -> d.contains(needle)),
                    "用例「" + name + "」应报出含「" + needle + "」的诊断。实际：" + r.diagnostics());
            }
        }
    }

    @Test
    @DisplayName("★语料必须同时覆盖「可编译」与「不可编译」两种形态")
    void corpusCoversBothShapes() throws Exception {
        // 反向守卫：只覆盖可编译源码，证明不了分层降级；
        // 只覆盖不可编译文档，证明不了候选/验证层的一致性。
        JsonNode cases = MAPPER.readTree(Files.readString(CORPUS)).path("cases");
        boolean hasCompilable = false, hasDegraded = false;
        for (JsonNode c : cases) {
            if (c.path("expectCandidates").size() > 0) hasCompilable = true;
            if (c.path("expectQuantities").size() > 0 && c.path("expectCandidates").size() == 0) {
                hasDegraded = true;
            }
        }
        assertTrue(hasCompilable, "语料缺少「可编译源码」用例——候选/验证层未被覆盖。");
        assertTrue(hasDegraded, "语料缺少「不可编译文档」用例——分层降级未被覆盖。");
    }
}
