package aster.core.layout;

import aster.core.identifier.CaseFold;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L9 补空格、L8 名字命中与 L6(c) / L7 拼写来源（ADR 0059 修订 4 §4；计划裁决 PR4：每个引擎取自己编译所用的词法表，
 * 并断言与共享向量 {@code keyword-spellings.json} 相同）。
 */
class LayoutPaddingTest {

    @TestFactory
    Stream<DynamicTest> paddingVectors() throws Exception {
        JsonNode doc = LayoutVectorsTest.vectors("display-padding.json");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : doc.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("id").asText(), () -> assertEquals(c.path("padded").textValue(),
                PackageLayoutRules.padStructural(LayoutFormat.parseStrict(c.path("layoutText").textValue().getBytes(StandardCharsets.UTF_8))))));
        }
        assertTrue(tests.size() >= 8);
        return tests.stream();
    }

    @Test
    void lexiconSpellingsMatchSharedVector() throws Exception {
        JsonNode doc = LayoutVectorsTest.vectors("keyword-spellings.json");
        List<String> kinds = new ArrayList<>();
        PackageLayoutRules.OPERATOR_KINDS.forEach(k -> kinds.add(k.name()));
        assertEquals(LayoutVectorsTest.strings(doc.path("operatorKinds")), kinds, "运算符 kind 清单与向量不同");
        assertEquals(aster.core.embed.UserAliasValidator.operatorKinds(), java.util.Set.copyOf(PackageLayoutRules.OPERATOR_KINDS),
            "L7 的运算符 kind 必须与 UserAliasValidator 的运算符档同一份");
        List<String> literal = new ArrayList<>();
        PackageLayoutRules.LITERAL_KINDS.forEach(k -> literal.add(k.name()));
        assertEquals(LayoutVectorsTest.strings(doc.path("literalKinds")), literal);
        for (String locale : List.of("en-US", "zh-CN", "de-DE", "hi-IN")) {
            JsonNode v = doc.path("locales").path(locale);
            assertEquals(LayoutVectorsTest.strings(v.path("literal")), PackageLayoutRules.lexiconSpellings(locale, PackageLayoutRules.LITERAL_KINDS), locale + " literal");
            assertEquals(LayoutVectorsTest.strings(v.path("operator")), PackageLayoutRules.lexiconSpellings(locale, PackageLayoutRules.OPERATOR_KINDS), locale + " operator");
        }
    }

    @Test
    void operatorSpellingsMergeOperatorAliasesOnly() {
        List<String> ops = PackageLayoutRules.operatorSpellings("zh-CN", Map.of(
            SemanticTokenKind.PLUS.name(), List.of("接 着"), SemanticTokenKind.RETURN.name(), List.of("低头")));
        assertTrue(ops.contains("接 着"));
        assertTrue(!ops.contains("低头"), "结构词别名不是运算符");
        assertTrue(ops.contains("加上") && ops.contains("plus"));
        assertTrue(PackageLayoutRules.literalKeywords("zh-CN").containsAll(List.of("真值", "true")));
    }

    @Test
    void structuralNameHitsUsesWholeWordsOnIrNamesAndWritten() {
        String ir = "{\"decls\":[{\"kind\":\"Func\",\"name\":\"approve\",\"origin\":{\"written\":\"批准\"}}],\"name\":\"m\"}";
        assertEquals(List.of("approve", "批准", "m"), PackageLayoutRules.irNameStrings(ir));
        PolicyLayout layout = new PolicyLayout(List.of(new LayoutSpan.Content("Rule"), new LayoutSpan.Structural(" APPROVE ", " "), new LayoutSpan.Content("x")));
        assertEquals(List.of("approve"), PackageLayoutRules.structuralNameHits(layout, PackageLayoutRules.irNameStrings(ir)));
        PolicyLayout ok = new PolicyLayout(List.of(new LayoutSpan.Content("Rule"), new LayoutSpan.Structural(" approved ", " "), new LayoutSpan.Content("x")));
        assertEquals(List.of(), PackageLayoutRules.structuralNameHits(ok, PackageLayoutRules.irNameStrings(ir)));
    }

    @Test
    void wholeWordBoundariesUseCodePoints() {
        assertTrue(PackageLayoutRules.containsWholeWord(CaseFold.foldKey(" (TRUE) "), "true"));
        assertTrue(!PackageLayoutRules.containsWholeWord(CaseFold.foldKey(" 𝐀true "), "true"));
        assertTrue(!PackageLayoutRules.containsWholeWord(" true̲ ", "true"));
        assertTrue(!PackageLayoutRules.containsWholeWord(" x ", ""));
    }
}
