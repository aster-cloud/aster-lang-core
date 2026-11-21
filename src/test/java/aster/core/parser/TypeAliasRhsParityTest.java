package aster.core.parser;

import aster.core.ast.Decl;
import aster.core.ast.Type;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.LexiconRegistry;
import aster.core.resolve.DeclarationResolver;
import aster.core.resolve.ImportedDeclarations;
import aster.core.resolve.ResolutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 类型别名右侧在 TYPE 位置解析（ADR 0051 修订 1，aster-lang-test#174 第 5 项）。
 *
 * <p>此前别名右侧不解析，{@code Type Amount as Unbekannt.} 两引擎都静默接受。共享夹具
 * {@code diagnostics/type_alias_rhs_parity.json} 与 aster-lang-ts {@code shared/type_alias_rhs_parity.json} 逐字节相同，
 * 判定与 {@link ParseDiagnosticsParityTest} 相同（名称解析的全部 error [码, 行]）；ts {@code type-alias-rhs-parity.test.ts} 按同一夹具断言。
 */
class TypeAliasRhsParityTest {

    private static JsonNode fixture() throws Exception {
        try (InputStream in = TypeAliasRhsParityTest.class.getResourceAsStream("/diagnostics/type_alias_rhs_parity.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            List<List<Object>> expected = new ArrayList<>();
            c.get("codes").forEach(pair -> expected.add(List.of(pair.get(0).asText(), pair.get(1).asInt())));
            String lexicon = c.path("lexicon").asText("en-US");
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                var parsed = ParseDiagnostics.parse(c.get("source").asText(), LexiconRegistry.getInstance().getOrThrow(lexicon));
                assertEquals(List.of(), parsed.diagnostics(), "夹具源码须能解析");
                assertEquals(expected, ParseDiagnosticsParityTest.coreCodes(c.get("source").asText(), lexicon, c.get("structs")));
            }));
        }
        assertEquals(12, tests.size());
        return tests.stream();
    }

    @Test
    void vocabularyTypeNameOnTheRightResolvesToCanonical() {
        var parsed = ParseDiagnostics.parse("Modul p.\n\nDefiniere Customer mit alter als Ganzzahl.\n\nTyp K als Kunde.\n",
            LexiconRegistry.getInstance().getOrThrow("de-DE"));
        var ctx = new ResolutionContext(VocabularyScopes.build(new DomainVocabulary("t", "t", "de-DE", "1",
            List.of(IdentifierMapping.struct("Customer", "Kunde")), List.of(), List.of(), List.of(), List.of(), null)),
            ImportedDeclarations.NONE);
        var resolved = DeclarationResolver.resolve(parsed.module(), ctx);
        Decl.TypeAlias alias = resolved.value().decls().stream()
            .filter(Decl.TypeAlias.class::isInstance).map(Decl.TypeAlias.class::cast).findFirst().orElseThrow();
        Type.TypeName rhs = (Type.TypeName) alias.type();
        assertNotNull(rhs.resolved());
        assertEquals("Customer", rhs.resolved().canonical());
        assertEquals("Kunde", rhs.name());
    }
}
