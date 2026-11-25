package aster.core.layout;

import aster.core.embed.InProcessCnlParser;
import aster.core.embed.PolicySourceCompiler;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L10 隐藏转义符共享向量（ADR 0059 修订 5 §6）：{@code src/test/resources/layout/display-escape.json} 是 aster-lang-test
 * {@code conformance/package-v1/vectors/display-escape.json} 的逐字节副本（sha256 由 {@link LayoutVectorsTest} 钉死，与 aster-lang-ts 相同）。
 * 按 L1–L7、L10a、L8–L9、L10b 的次序判定第一条失败的规则；L8–L10b 用 core 编译器（与 CLI、aster-api 同一实现）。
 */
class LayoutEscapeVectorsTest {

    @TestFactory
    Stream<DynamicTest> escapeVectors() throws Exception {
        JsonNode doc = LayoutVectorsTest.vectors("display-escape.json");
        assertTrue(doc.path("cases").size() >= 20, "display-escape 向量过少");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : doc.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("id").asText() + " → " + c.path("expect").asText(), () -> assertCase(c)));
        }
        return tests.stream();
    }

    private static void assertCase(JsonNode c) {
        String locale = c.path("lexicon").asText("en-US");
        byte[] layoutBytes = c.path("layoutText").textValue().getBytes(StandardCharsets.UTF_8);
        PolicyLayout parsed = LayoutFormat.parseStrict(layoutBytes);
        String source = parsed.toCanonical();
        Map<String, List<String>> aliasStrings = new LinkedHashMap<>();
        c.path("aliasSet").fields().forEachRemaining(e -> {
            List<String> l = new ArrayList<>();
            e.getValue().forEach(v -> l.add(v.asText()));
            aliasStrings.put(e.getKey(), l);
        });
        JsonNode vocabulary = c.hasNonNull("vocabulary") ? c.get("vocabulary") : null;
        L10bVerifier compiler = compilerFor(locale, aliasStrings, vocabulary, c.path("allowStructural").asBoolean(false));
        String got = "ok";
        try {
            PolicyLayout layout = PackageLayoutRules.checkPackage(layoutBytes, parsed.toDisplay().getBytes(StandardCharsets.UTF_8),
                source, 1, PackageLayoutRules.vocabularyLocalized(vocabulary), PackageLayoutRules.literalKeywords(locale),
                PackageLayoutRules.operatorSpellings(locale, aliasStrings), L10bVerifier.DEFERRED);
            if (c.has("triples")) {
                List<String> names = PackageLayoutRules.hiddenEscapeTriples(layout).stream().map(PackageLayoutRules.HiddenEscape::name).toList();
                assertEquals(LayoutVectorsTest.strings(c.path("triples")), names);
            }
            if (c.has("stripped")) {
                List<PackageLayoutRules.HiddenEscape> t = PackageLayoutRules.hiddenEscapeTriples(layout);
                List<String> want = LayoutVectorsTest.strings(c.path("stripped"));
                for (int k = 0; k < t.size(); k++) {
                    assertEquals(want.get(k), PackageLayoutRules.stripHiddenEscapes(source, t, 1 << k));
                }
                assertEquals(want.get(t.size()), PackageLayoutRules.stripHiddenEscapes(source, t, (1 << t.size()) - 1));
            }
            String hash = compiler.positionFreeSha256(source);
            if (hash == null) {
                throw new IllegalStateException("向量 " + c.path("id").asText() + " 的规范源码编译不了");
            }
            String ir = compileIr(source, locale, aliasStrings, vocabulary, c.path("allowStructural").asBoolean(false));
            if (!PackageLayoutRules.structuralNameHits(layout, PackageLayoutRules.irNameStrings(ir)).isEmpty()) {
                throw new LayoutException("L8", "structural span contains an IR name");
            }
            if (!hash.equals(compiler.positionFreeSha256(PackageLayoutRules.padStructural(layout)))) {
                throw new LayoutException("L9", "padding changes the program");
            }
            PackageLayoutRules.checkHiddenEscapes(layout, compiler);
        } catch (LayoutException e) {
            got = e.rule();
        }
        assertEquals(c.path("expect").asText(), got);
    }

    @Test
    void withoutVerifierHiddenEscapesAreRejected() throws Exception {
        JsonNode c = null;
        for (JsonNode x : LayoutVectorsTest.vectors("display-escape.json").path("cases")) {
            if ("E3".equals(x.path("id").asText())) {
                c = x;
            }
        }
        byte[] layoutBytes = c.path("layoutText").textValue().getBytes(StandardCharsets.UTF_8);
        PolicyLayout parsed = LayoutFormat.parseStrict(layoutBytes);
        LayoutException e = assertThrows(LayoutException.class, () -> PackageLayoutRules.checkPackage(layoutBytes,
            parsed.toDisplay().getBytes(StandardCharsets.UTF_8), parsed.toCanonical(), 1, List.of(), List.of(), List.of()));
        assertEquals("L10b", e.rule());
        // 带编译器的入口一并跑 L10b：E3 通过
        PackageLayoutRules.checkPackage(layoutBytes, parsed.toDisplay().getBytes(StandardCharsets.UTF_8), parsed.toCanonical(), 1,
            List.of(), List.of(), List.of(), compilerFor("en-US", Map.of(), null, false));
    }

    private static L10bVerifier compilerFor(String locale, Map<String, List<String>> aliasSet, JsonNode vocabulary,
                                            boolean allowStructural) {
        return source -> {
            try {
                return PolicySourceCompiler.positionFreeSha256(compileIr(source, locale, aliasSet, vocabulary, allowStructural));
            } catch (InProcessCnlParser.CnlParseException e) {
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static String compileIr(String source, String locale, Map<String, List<String>> aliasSet, JsonNode vocabulary,
                                    boolean allowStructural) {
        Map<SemanticTokenKind, List<String>> kinds = new EnumMap<>(SemanticTokenKind.class);
        aliasSet.forEach((k, v) -> kinds.put(SemanticTokenKind.valueOf(k), v));
        VocabularyScopes scopes = vocabulary == null ? null : PolicySourceCompiler.vocabularyScopesOf(vocabulary.toString());
        return PolicySourceCompiler.compileCanonical(source, locale, scopes, kinds, allowStructural);
    }
}
