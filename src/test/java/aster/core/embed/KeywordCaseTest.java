package aster.core.embed;

import aster.core.ir.CoreIrJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 关键词大小写（K，aster-lang-ts#338 第 1 部分，ADR 0056 修订 A4 §1.1、修订 A7 D1）。
 *
 * <ul>
 *   <li>{@code keyword_case_decl.json}：K-a 的声明与句首关键词在任意大小写下与规范拼写同义；同形词作名字照旧；转义的关键词
 *       永远是名字；非关键词名字的诊断与 K 之前相同。</li>
 *   <li>{@code keyword_case_stmt.json}：K-b 的语句、表达式与类型关键词（be、set、to、not、and、or、for、function、map、async），
 *       结构同上；小写 map 在类型位置是 Map 类型（aster-lang-core#314）。</li>
 *   <li>{@code keyword_case_ops.json}：K-c 的运算符与字面量（times、plus、minus、modulo、比较词前的 is、not equal to、
 *       under / over、true / false / null），结构同上；Not(x) 读作 not（产品裁决 D3），True / Null 在表达式里是字面量、
 *       在声明名字的位置报 E055（产品裁决 D2，reject 里带 tightened 的行）。</li>
 *   <li>{@code keyword_case_workflow.json}：K-d 的 workflow 与约束关键词（workflow、step、depends on、compensate、retry、backoff、
 *       timeout、seconds / second、required、between、matching），结构同上；max attempts 本来就由 Canonicalizer 归一成小写。</li>
 *   <li>{@code name_position_matrix.json}：名字位置回归矩阵。期望值取自 K 之前的 core，K-a..K-d 每个 PR 原样重跑：
 *       名字位置的结论（位置无关哈希，或第一条诊断的码与位置）必须逐例不变；唯一的例外是标了 {@code "tightened": "D2"}
 *       的行（true / false / null 的其余大小写拼写），期望值是 K-c 之后的结论，{@code before} 记录 K-c 之前的结论。</li>
 * </ul>
 */
class KeywordCaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> declarationKeywords() throws IOException {
        return keywordFixture("/keyword-case/keyword_case_decl.json", 62);
    }

    @TestFactory
    Stream<DynamicTest> statementExpressionTypeKeywords() throws IOException {
        return keywordFixture("/keyword-case/keyword_case_stmt.json", 206);
    }

    @TestFactory
    Stream<DynamicTest> operatorAndLiteralKeywords() throws IOException {
        return keywordFixture("/keyword-case/keyword_case_ops.json", 219);
    }

    @TestFactory
    Stream<DynamicTest> workflowAndConstraintKeywords() throws IOException {
        return keywordFixture("/keyword-case/keyword_case_workflow.json", 81);
    }

    private static Stream<DynamicTest> keywordFixture(String path, int acceptCount) throws IOException {
        JsonNode fixture = fixture(path);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture.get("accept")) {
            tests.add(DynamicTest.dynamicTest("accept " + c.get("name").asText(), () -> {
                // 生产路径（InProcessCnlParser，全程 SLL）与 ParseDiagnostics（LL）都接受：兼容层只在出错路径介入
                assertThat(positionFree(c.get("source").asText(), lexicon(c)))
                    .isEqualTo(positionFree(c.get("canonical").asText(), lexicon(c)));
                assertThat(aster.core.parser.ParseDiagnostics.parse(c.get("source").asText(),
                    aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow(lexicon(c))).diagnostics()).isEmpty();
            }));
        }
        for (JsonNode c : fixture.get("names")) {
            tests.add(DynamicTest.dynamicTest("names " + c.get("name").asText(), () -> assertNames(c)));
        }
        for (JsonNode c : fixture.get("reject")) {
            tests.add(DynamicTest.dynamicTest("reject " + c.get("name").asText(), () -> assertReject(c)));
        }
        assertThat(fixture.get("accept")).hasSize(acceptCount);
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> namePositionMatrix() throws IOException {
        JsonNode fixture = fixture("/keyword-case/name_position_matrix.json");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture.get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                if (c.has("pf")) {
                    assertThat(positionFree(c.get("source").asText(), "en-US")).isEqualTo(c.get("pf").asText());
                } else {
                    assertReject(c);
                }
            }));
        }
        assertThat(fixture.get("cases")).hasSize(4073);
        // D2 的刻意收紧只落在 true / false / null 上，每行都记着 K-c 之前的结论
        List<JsonNode> tightened = new ArrayList<>();
        fixture.get("cases").forEach(c -> { if (c.has("tightened")) tightened.add(c); });
        assertThat(tightened).hasSize(117).allSatisfy(c -> {
            assertThat(c.get("tightened").asText()).isEqualTo("D2");
            assertThat(c.get("name").asText()).matches("[a-z]+/(true|false|null)/[A-Za-z]+");
            assertThat(c.has("before")).isTrue();
        });
        return tests.stream();
    }

    private static void assertNames(JsonNode c) throws Exception {
        String ir = PolicySourceCompiler.compileCanonical(c.get("source").asText(), lexicon(c), null, null, false);
        for (JsonNode n : c.get("names")) {
            assertThat(ir).as("Core IR 含名字 %s", n.asText()).contains("\"" + n.asText() + "\"");
        }
    }

    private static void assertReject(JsonNode c) {
        var e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(c.get("source").asText(), lexicon(c)));
        var first = e.getDiagnostics().get(0);
        JsonNode want = c.get("reject");
        assertThat(List.of(first.code(), first.line(), first.column()))
            .isEqualTo(List.of(want.get(0).asText(), want.get(1).asInt(), want.get(2).asInt()));
        if (c.has("params")) {
            Map<String, String> params = new LinkedHashMap<>();
            c.get("params").fields().forEachRemaining(p -> params.put(p.getKey(), p.getValue().asText()));
            assertThat(first.params()).isEqualTo(params);
        }
    }

    private static String positionFree(String source, String locale) throws Exception {
        return PolicySourceCompiler.positionFreeSha256(CoreIrJson.serialize(InProcessCnlParser.parse(source, locale).core()));
    }

    private static String lexicon(JsonNode c) {
        return c.has("lexicon") ? c.get("lexicon").asText() : "en-US";
    }

    private static JsonNode fixture(String path) throws IOException {
        try (InputStream in = KeywordCaseTest.class.getResourceAsStream(path)) {
            return MAPPER.readTree(in);
        }
    }
}
