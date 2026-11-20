package aster.core.embed;

import aster.core.identifier.CaseFold;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreIrJson;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字面量触发词与关键词同形时关键词优先（ADR 0060 修订 1 R16'，aster-lang-core#290）。共享夹具
 * {@code literal_keyword_shadow_parity.json} 与 aster-lang-ts {@code shared/literal_keyword_shadow_parity.json} 逐字节相同，
 * ts {@code literal-keyword-shadow-parity.test.ts} 按同一夹具断言。
 *
 * <p>每例断言 {@link VocabularyScopes#literalsVisibleUnder} 正好保留 expectVisible；带 source 的用例另以有效词法表、
 * 不经别名校验编译，与不带词汇的 equivalent 得到同一位置无关哈希（被丢弃的触发词按关键词读）。
 */
class LiteralKeywordShadowParityTest {

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
        DomainVocabulary.Builder b = DomainVocabulary.builder("p", "p", locale);
        for (JsonNode lit : c.get("literals")) {
            b.addLiteral(lit.get(1).asText(), lit.get(0).asText());
        }
        DomainVocabulary vocab = b.build();
        VocabularyScopes scopes = VocabularyScopes.build(vocab);
        Map<SemanticTokenKind, List<String>> aliasSet = aliases(c.get("aliasSet"));
        Lexicon base = AliasOverlayLexicon.wrap(LexiconRegistry.getInstance().getOrThrow(locale), aliases(c.get("officialAliases")));
        Lexicon effective = AliasOverlayLexicon.wrap(base, aliasSet);

        TreeSet<String> expected = new TreeSet<>();
        c.get("expectVisible").forEach(t -> expected.add(CaseFold.foldKey(CaseFold.nfc(t.asText()))));
        assertThat(new TreeSet<>(scopes.literalsVisibleUnder(effective).keySet())).isEqualTo(expected);

        if (c.has("source")) {
            String withVocab = CoreIrJson.serialize(
                InProcessCnlParser.parseUnsafeWithAliases(c.get("source").asText(), locale, scopes, aliasSet).core());
            String plain = CoreIrJson.serialize(
                InProcessCnlParser.parseUnsafeWithAliases(c.get("equivalent").asText(), locale, null, aliasSet).core());
            assertThat(PolicySourceCompiler.positionFreeSha256(withVocab))
                .as("被丢弃的触发词按关键词读")
                .isEqualTo(PolicySourceCompiler.positionFreeSha256(plain));
        }
    }

    private static Map<SemanticTokenKind, List<String>> aliases(JsonNode node) {
        Map<SemanticTokenKind, List<String>> out = new EnumMap<>(SemanticTokenKind.class);
        if (node == null) {
            return out;
        }
        node.fields().forEachRemaining(e -> {
            List<String> list = new ArrayList<>();
            e.getValue().forEach(a -> list.add(a.asText()));
            out.put(SemanticTokenKind.valueOf(e.getKey()), list);
        });
        return out;
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = LiteralKeywordShadowParityTest.class.getResourceAsStream(
                "/diagnostics/literal_keyword_shadow_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
