package aster.core.embed;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.Lexicon;
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

/**
 * 字面量宏内容的字符规则（ADR 0060 修订 1 R18，aster-lang-ts#362 第 5 项）：反斜杠、ASCII 双引号与弯引号允许，
 * 「」『』«»、控制字符与空内容拒绝。共享夹具 {@code literal_value_parity.json} 与 aster-lang-ts
 * {@code shared/literal_value_parity.json} 逐字节相同；ts {@code literal-value-parity.test.ts} 按同一夹具断言。
 *
 * <p>允许的内容另验证展开结果：编译后 Return 的字符串值等于宏值本身，规范化结果再规范化一次不变。
 */
class LiteralValueParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertCase(c)));
        }
        assertThat(tests).hasSize(25);
        return tests.stream();
    }

    private static void assertCase(JsonNode c) throws IOException {
        String locale = c.get("lexicon").asText();
        String content = c.get("content").asText();
        DomainVocabulary vocab = DomainVocabulary.builder("p", "p", locale)
            .addLiteral(content, c.get("trigger").asText())
            .build();
        boolean valid = c.get("valid").asBoolean();
        assertThat(vocab.validate().valid()).as("校验结论 %s", vocab.validate().errors()).isEqualTo(valid);
        if (!valid) {
            return;
        }
        String source = c.get("source").asText();
        VocabularyScopes scopes = VocabularyScopes.build(vocab);
        JsonNode ir = MAPPER.readTree(PolicySourceCompiler.compileCanonical(source, locale, scopes, null, false));
        JsonNode expr = ir.get("decls").get(0).get("body").get("statements").get(0).get("expr");
        assertThat(expr.get("kind").asText()).isEqualTo("String");
        assertThat(expr.get("value").asText()).as("读回值").isEqualTo(content);

        Lexicon lexicon = LexiconRegistry.getInstance().getOrThrow(locale);
        String canonical = new Canonicalizer(lexicon, scopes).canonicalize(source);
        assertThat(new Canonicalizer(lexicon).canonicalize(canonical)).as("展开结果再规范化不变").isEqualTo(canonical);
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = LiteralValueParityTest.class.getResourceAsStream("/diagnostics/literal_value_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
