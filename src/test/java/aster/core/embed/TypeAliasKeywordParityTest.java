package aster.core.embed;

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

/**
 * 类型别名引导词 TYPE_ALIAS（ADR 0058 修订 2，aster-lang-ts#346）：zh {@code 类型}、de {@code Typ}、hi {@code प्रकार} 只在顶层声明句首
 * 引导类型别名，与英文 {@code Type} 同义；名字位置的同形词照常是名字、不改名。共享夹具 {@code type_alias_keyword_parity.json}
 * 与 aster-lang-ts {@code shared/type_alias_keyword_parity.json} 逐字节相同，ts {@code type-alias-keyword-parity.test.ts} 按同一夹具断言。
 */
class TypeAliasKeywordParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertCase(c)));
        }
        assertThat(tests).hasSize(12);
        return tests.stream();
    }

    private static void assertCase(JsonNode c) throws Exception {
        String locale = c.get("lexicon").asText();
        String ir = PolicySourceCompiler.compileCanonical(c.get("source").asText(), locale, null, null, false);
        if (c.has("equivalent")) {
            String eq = PolicySourceCompiler.compileCanonical(c.get("equivalent").asText(), locale, null, null, false);
            assertThat(PolicySourceCompiler.positionFreeSha256(ir)).isEqualTo(PolicySourceCompiler.positionFreeSha256(eq));
        }
        if (c.has("names")) {
            for (JsonNode n : c.get("names")) {
                assertThat(ir).as("Core IR 原样保留名字 %s", n.asText()).contains("\"" + n.asText() + "\"");
            }
        }
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = TypeAliasKeywordParityTest.class.getResourceAsStream("/diagnostics/type_alias_keyword_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
