package aster.core.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双引擎 verifier 判定一致性（ADR 0037 §7）—— <b>Java 侧</b>。
 *
 * <h2>★§7 的要求是有区分的</h2>
 *
 * <pre>
 *   LLM 生成的 candidate  →  <b>不要求</b>一致
 *   Verifier 的结果       →  <b>必须</b>一致
 * </pre>
 *
 * 故本文件只钉 {@code verdict}，<b>不钉 reason</b>（含自然语言，两侧措辞允许不同）。
 *
 * <h2>语料是<b>共享</b>的</h2>
 *
 * 用例来自 {@code aster-lang-test/corpus/mapping-verdict/cases.json}，
 * TS 侧 {@code verdict-parity.test.ts} 读<b>同一个文件</b>。
 *
 * <p>★这是"单源"。若两侧各抄一份语料，它们会各自漂移，
 * 而"一致性门禁"就退化成两个互不相干的测试——看着都绿，
 * 实际什么都没保证（本仓记过这个坑）。
 */
class MappingIrVerdictParityTest {

    /**
     * 共享语料路径。★<b>两种布局都要支持</b>：
     *
     * <ul>
     *   <li><b>本地开发</b>：兄弟仓并列 → {@code ../aster-lang-test/...}</li>
     *   <li><b>CI</b>：{@code checkout-sibling} 用 {@code path: aster-lang-test}
     *       把它检出到<b>工作区内</b> → {@code ./aster-lang-test/...}</li>
     * </ul>
     *
     * <p>★我第一版只写了 {@code ../}，本地通过但<b>在 CI 上必然失败</b>
     * ——两种布局不同，而我只验证了自己机器上的那种。
     * 这类"本地能跑"的路径假设是本仓记过的高频坑。
     */
    private static final Path CORPUS = resolveCorpus();

    private static Path resolveCorpus() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path rel = Path.of("corpus", "mapping-verdict", "cases.json");
        for (Path base : new Path[]{
            cwd.resolve("aster-lang-test"),          // CI：检出到工作区内
            cwd.resolve("..").resolve("aster-lang-test"),  // 本地：兄弟仓并列
        }) {
            Path p = base.resolve(rel).normalize();
            if (Files.exists(p)) return p;
        }
        // 都找不到时返回本地布局的路径，让 corpusExists() 报出明确的缺失信息。
        return cwd.resolve("..").resolve("aster-lang-test").resolve(rel).normalize();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("★共享语料必须存在（缺失即门禁失效，不得静默跳过）")
    void corpusExists() {
        // ★若语料路径漂了而测试"跳过"，这条门禁会在无人察觉时消失。
        //   本仓记过「跳过看起来像通过」——此处显式断言存在性。
        assertTrue(Files.exists(CORPUS),
            "共享语料不存在：" + CORPUS + "\n"
            + "★本门禁依赖 aster-lang-test 作为兄弟仓 checkout。"
            + "路径变更必须同步改两侧（TS 侧同名测试读同一文件）。");
    }

    @Test
    @DisplayName("★每条用例的判定必须与语料声明一致（与 TS 侧同源）")
    void verdictsMatchSharedCorpus() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(CORPUS));
        JsonNode cases = root.path("cases");
        assertTrue(cases.size() >= 10,
            "语料用例过少（" + cases.size() + "）——覆盖不了三种 verdict 的各种成因。");

        Set<String> seen = new HashSet<>();
        for (JsonNode c : cases) {
            String name = c.path("name").asText();
            JsonNode m = c.path("mapping");
            MappingIr.CandidateMapping mapping = new MappingIr.CandidateMapping(
                new MappingIr.TextSpan(
                    m.path("span").path("start").asInt(),
                    m.path("span").path("end").asInt()),
                m.path("text").asText(),
                m.path("nodeId").asText());

            JsonNode n = c.path("node");
            MappingIr.VerifiableNode node = n.isNull() || n.isMissingNode()
                ? null
                : new MappingIr.VerifiableNode(
                    n.path("kind").asText(),
                    toValue(n.path("value")),
                    n.hasNonNull("name") ? n.path("name").asText() : null);

            MappingIr.VerificationResult r = MappingIr.verify(mapping, id -> node);
            String expect = c.path("expect").asText();

            assertEquals(expect, r.verdict().name(),
                "用例「" + name + "」判定不符。\n"
                + "  理由：" + r.reason() + "\n"
                + "★若 TS 侧同用例判定不同，即违反 ADR §7「Verifier 结果必须一致」。");
            seen.add(r.verdict().name());
        }

        // ★反向守卫：三种 verdict 都要被覆盖，否则语料不足以证明一致性。
        //   只覆盖 VERIFIED 的语料，挡不住「把所有输入都判 VERIFIED」这种实现。
        for (String v : new String[]{"VERIFIED", "REVIEW_REQUIRED", "REJECTED"}) {
            assertTrue(seen.contains(v),
                "语料未覆盖 " + v + " —— 一致性门禁存在盲区。已覆盖：" + seen);
        }
    }

    /** JSON 值 → Java 值。★须与 TS 侧口径一致，否则"值比较"两边不同。 */
    private static Object toValue(JsonNode v) {
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isFloatingPointNumber()) return v.asDouble();
        return v.asText();
    }
}
