package aster.core.embed;

import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.ParseDiagnostics;
import aster.core.parser.ParseDiagnostic;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 关键词当名字（ADR 0056 修订 A6，产品裁决 2026-11-22）：
 * <ul>
 *   <li>K1 结构关键词（If / Return / Rule…及其本地化拼写）当名字，两引擎都报 E901；</li>
 *   <li>K2 声明用词 version / type / given 等当名字，两引擎都接受；</li>
 *   <li>K3 其它内置关键词的本地化拼写当名字，解析不变（取关键词的英文拼写），两引擎同码同文发出 W802。</li>
 * </ul>
 * 共享夹具 {@code keyword_name_parity.json} 与 aster-lang-ts {@code shared/keyword_name_parity.json} 逐字节相同
 * （由 ts 侧断言），ts {@code keyword-name-parity.test.ts} 按同一夹具断言。
 */
class KeywordNameParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws IOException {
        JsonNode fixture = fixture();
        List<DynamicTest> tests = new ArrayList<>();
        fixture.get("collisionWords").fields().forEachRemaining(e -> tests.add(DynamicTest.dynamicTest(
            e.getKey() + "：W802 判据集合与夹具相同", () -> assertCollisionWords(e.getKey(), e.getValue()))));
        for (JsonNode c : fixture.get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertCase(c)));
        }
        assertThat(fixture.get("cases")).hasSize(63);
        return tests.stream();
    }

    private static void assertCollisionWords(String locale, JsonNode expected) {
        List<String> words = new ArrayList<>(KeywordNameGuard.collisionWords(
            LexiconRegistry.getInstance().getOrThrow(locale)).keySet());
        words.sort(String::compareTo);
        List<String> want = new ArrayList<>();
        expected.forEach(w -> want.add(w.asText()));
        assertThat(words).isEqualTo(want);
    }

    private static void assertCase(JsonNode c) throws Exception {
        String locale = c.get("lexicon").asText();
        String source = c.get("source").asText();
        if (c.has("reject")) {
            assertThat(firstParseError(source, locale))
                .isEqualTo(List.of(c.get("reject").get(0).asText(), c.get("reject").get(1).asInt()));
            var e = assertThrows(InProcessCnlParser.CnlParseException.class, () -> InProcessCnlParser.parse(source, locale));
            if (c.has("params")) {
                var first = e.getDiagnostics().get(0);
                Map<String, String> want = new java.util.LinkedHashMap<>();
                c.get("params").fields().forEachRemaining(p -> want.put(p.getKey(), p.getValue().asText()));
                assertThat(first.params()).isEqualTo(want);
                if (c.has("message")) {
                    assertThat(first.message()).isEqualTo(c.get("message").asText());
                }
            }
            return;
        }
        assertThat(firstParseError(source, locale)).isNull();
        InProcessCnlParser.ParseResult result = InProcessCnlParser.parse(source, locale);
        String ir = PolicySourceCompiler.compileCanonical(source, locale, null, null, false);
        for (JsonNode n : c.get("names")) {
            assertThat(ir).as("Core IR 含名字 %s", n.asText()).contains("\"" + n.asText() + "\"");
        }
        List<List<Object>> warnings = result.warnings().stream()
            .filter(d -> d.code() == ErrorCode.KEYWORD_RENAMED_AS_NAME)
            .map(d -> List.<Object>of(d.code().code(), d.span().map(o -> o.start.line).orElse(0)))
            .toList();
        List<List<Object>> want = new ArrayList<>();
        c.get("warnings").forEach(w -> want.add(List.of(w.get(0).asText(), w.get(1).asInt())));
        assertThat(warnings).isEqualTo(want);
    }

    /** 第一条非 E063 的解析诊断 [码, 行]（全是 E063 时取第一条），与 ParseDiagnosticsParityTest 同一判定；能解析时返回 null。 */
    private static List<Object> firstParseError(String source, String locale) {
        var result = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow(locale));
        if (result.diagnostics().isEmpty()) {
            return null;
        }
        ParseDiagnostic primary = result.diagnostics().stream()
            .filter(d -> d.code() != ErrorCode.PARSE_NO_VIABLE)
            .findFirst()
            .orElse(result.diagnostics().get(0));
        return List.of(primary.code().code(), primary.line());
    }

    @Test
    void w802CarriesNameKeywordAndKind() {
        String source = "模块 探针.样例。\n\n定义 软件 包含 版本 作为 整数。\n";
        List<Diagnostic> warnings = InProcessCnlParser.parse(source, "zh-CN").warnings();
        assertThat(warnings).hasSize(1);
        Diagnostic d = warnings.get(0);
        assertThat(d.severity()).isEqualTo(Diagnostic.Severity.WARNING);
        assertThat(d.data()).isEqualTo(Map.of("name", "version", "keyword", "版本", "kind", "IMPORT_VERSION"));
        assertThat(d.message()).isEqualTo("'版本' is read as the keyword IMPORT_VERSION: the name becomes 'version'");
    }

    @Test
    void e901CarriesLocalizedKeywordParam() {
        String source = "模块 探针.样例。\n\n规则 取值 给定 当 作为 整数，产出 整数：\n  返回 1。\n";
        var e = assertThrows(InProcessCnlParser.CnlParseException.class, () -> InProcessCnlParser.parse(source, "zh-CN"));
        var d = e.getDiagnostics().get(0);
        assertThat(d.code()).isEqualTo("E901");
        assertThat(d.params()).isEqualTo(Map.of("keyword", "kw.when", "kind", "WHEN"));
        // 文案按语言包 overlay 渲染（zh 有译文时是中文）：只断言带引号的作者语言关键词与 kind 都在
        assertThat(d.message()).contains("'当'").contains("WHEN");
        assertThat(d.line()).isEqualTo(3);
    }

    @Test
    void englishSourceHasNoCollisionWords() {
        assertThat(KeywordNameGuard.collisionWords(LexiconRegistry.getInstance().getOrThrow("en-US"))).isEmpty();
        assertThat(InProcessCnlParser.parse(
            "Module probe.sample.\n\nDefine Software with allow as Int.\n", "en-US").warnings()).isEmpty();
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = KeywordNameParityTest.class.getResourceAsStream("/diagnostics/keyword_name_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
