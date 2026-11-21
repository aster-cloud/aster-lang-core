package aster.core.parser;

import aster.core.embed.PolicySourceCompiler;
import aster.core.lexicon.LexiconRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code The result is} / {@code Ergebnis ist} / {@code 结果为} 与英语所有格的双引擎 parity（aster-lang-core#298）。
 *
 * <p>{@code the result is} 之后的分隔空白只认 ASCII 空格与 tab：此前 core 的 {@code \s} 还收 VT、FF 与换行，
 * {@code The result is}⏎{@code x.} 被拼成一条 Return，ts 拒绝。共享夹具 {@code diagnostics/result_is_parity.json}
 * 与 aster-lang-ts {@code shared/result_is_parity.json} 逐字节相同，ts {@code result-is-parity.test.ts} 按同一夹具断言：
 * 正例与 equivalent 的 positionFreeSha256 相同，反例两引擎都拒绝且第一条错误在 errorLine 行。
 */
class ResultIsParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertCase(c)));
        }
        assertThat(tests).hasSize(43);
        return tests.stream();
    }

    private static void assertCase(JsonNode c) throws Exception {
        String locale = c.get("lexicon").asText();
        String source = c.get("source").asText();
        if (c.path("reject").asBoolean(false)) {
            var parsed = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow(locale));
            assertThat(parsed.diagnostics()).as("应有解析错误").isNotEmpty();
            assertThat(parsed.diagnostics().get(0).line()).isEqualTo(c.get("errorLine").asInt());
            assertThatThrownBy(() -> PolicySourceCompiler.compileCanonical(source, locale, null, null, false));
            return;
        }
        String ir = PolicySourceCompiler.compileCanonical(source, locale, null, null, false);
        String eq = PolicySourceCompiler.compileCanonical(c.get("equivalent").asText(), locale, null, null, false);
        assertThat(PolicySourceCompiler.positionFreeSha256(ir)).isEqualTo(PolicySourceCompiler.positionFreeSha256(eq));
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = ResultIsParityTest.class.getResourceAsStream("/diagnostics/result_is_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
