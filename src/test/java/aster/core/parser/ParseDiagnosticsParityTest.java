package aster.core.parser;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.LexiconRegistry;
import aster.core.resolve.ImportedDeclarations;
import aster.core.resolve.DeclarationResolver;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0051 §16 R56/R57 双引擎诊断 parity（core 侧）。
 *
 * <p>夹具 {@code diagnostics/parse_diagnostics_parity.json} 与 aster-lang-ts 的
 * {@code shared/parse_diagnostics_parity.json} byte-identical（由 ts 侧断言）。判定：有解析错误时取
 * 第一条非 E063 的诊断（E063 是 ANTLR 级联里最泛化的 no viable alternative，全是 E063 时取第一条）；
 * 无解析错误时取名称解析（R1）的全部 error。
 */
class ParseDiagnosticsParityTest {

    private static JsonNode fixture() throws Exception {
        try (InputStream in = ParseDiagnosticsParityTest.class.getResourceAsStream("/diagnostics/parse_diagnostics_parity.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    static List<List<Object>> coreCodes(String source, String lexiconId, JsonNode structs) {
        var result = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow(lexiconId));
        if (!result.diagnostics().isEmpty()) {
            ParseDiagnostic primary = result.diagnostics().stream()
                .filter(d -> d.code() != ErrorCode.PARSE_NO_VIABLE)
                .findFirst()
                .orElse(result.diagnostics().get(0));
            return List.of(List.of(primary.code().code(), primary.line()));
        }
        List<List<Object>> out = new ArrayList<>();
        List<IdentifierMapping> mappings = new ArrayList<>();
        if (structs != null) {
            structs.forEach(s -> mappings.add(IdentifierMapping.struct(s.get(0).asText(), s.get(1).asText())));
        }
        ResolutionContext ctx = mappings.isEmpty() ? ResolutionContext.none()
            : new ResolutionContext(VocabularyScopes.build(new DomainVocabulary("t", "t", "de-DE", "1",
                mappings, List.of(), List.of(), List.of(), List.of(), null)), ImportedDeclarations.NONE);
        for (Diagnostic d : DeclarationResolver.resolve(result.module(), ctx).diagnostics()) {
            if (d.severity() == Diagnostic.Severity.ERROR) {
                out.add(List.of(d.code().code(), d.span().map(o -> o.start.line).orElse(0)));
            }
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> sharedFixtureMatchesCore() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            if ("ts".equals(c.path("only").asText())) {
                continue;
            }
            List<List<Object>> expected = new ArrayList<>();
            c.get("codes").forEach(pair -> expected.add(List.of(pair.get(0).asText(), pair.get(1).asInt())));
            String lexicon = c.path("lexicon").asText("en-US");
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(),
                () -> assertEquals(expected, coreCodes(c.get("source").asText(), lexicon, c.get("structs")))));
        }
        return tests.stream();
    }

    @Test
    void everyParseCodeHasAFixture() throws Exception {
        Set<String> covered = new HashSet<>();
        fixture().get("cases").forEach(c -> c.get("codes").forEach(p -> covered.add(p.get(0).asText())));
        List<String> missing = Arrays.stream(ErrorCode.values())
            .filter(e -> e.category() == ErrorCode.Category.SYNTAX)
            .map(ErrorCode::code)
            .filter(code -> !covered.contains(code))
            .toList();
        assertEquals(List.of(), missing);
        assertEquals(20, Arrays.stream(ErrorCode.values()).filter(e -> e.category() == ErrorCode.Category.SYNTAX).count());
        assertTrue(covered.contains("E807"));
    }
}
