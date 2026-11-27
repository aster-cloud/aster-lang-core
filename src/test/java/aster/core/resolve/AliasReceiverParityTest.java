package aster.core.resolve;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreIrJson;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.ParseDiagnostics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 类型别名接收者展开（aster-lang-core#313，ADR 0056 修订 A7 D1）。
 *
 * <p>别名不产出 Core 声明；R2 收到别名表（{@link aster.core.lowering.CoreLowering#lowerTypeAliases}）后把别名类型逐层展开为
 * 目标类型，点分路径接收者、Match 主语与期望类型与直接写目标类型时解析结果相同，不再走「接收者类型未知 → 全局唯一候选 + W801」
 * 的回退。共享夹具 {@code diagnostics/alias_receiver_parity.json} 与 aster-lang-ts {@code shared/alias_receiver_parity.json}
 * 逐字节相同（由 ts 侧断言）；ts {@code alias-receiver-parity.test.ts} 按同一夹具断言。
 */
class AliasReceiverParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ResolutionContext CTX = new ResolutionContext(VocabularyScopes.build(new DomainVocabulary(
        "t", "t", "en-US", "1",
        List.of(IdentifierMapping.struct("Driver", "Fahrer")),
        List.of(IdentifierMapping.field("age", "alter", "Driver")),
        List.of(),
        List.of(IdentifierMapping.enumValue("High", "Hoch", "Level"), IdentifierMapping.enumValue("Low", "Niedrig", "Level")),
        List.of(), null)), ImportedDeclarations.NONE);

    private static JsonNode fixture() throws Exception {
        try (InputStream in = AliasReceiverParityTest.class.getResourceAsStream("/diagnostics/alias_receiver_parity.json")) {
            return MAPPER.readTree(in);
        }
    }

    /** Func 体内 Name / PatName（kind:name）与 PatCtor（PatCtor:typeName），按对象键字典序深度优先（与 ts 同一遍历）。 */
    private static void refsOf(JsonNode node, List<String> out) {
        if (node.isArray()) {
            node.forEach(x -> refsOf(x, out));
            return;
        }
        if (!node.isObject()) return;
        String kind = node.path("kind").asText();
        if (kind.equals("Name") || kind.equals("PatName")) out.add(kind + ":" + node.path("name").asText());
        if (kind.equals("PatCtor")) out.add("PatCtor:" + node.path("typeName").asText());
        TreeSet<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) {
            if (!k.equals("origin")) refsOf(node.get(k), out);
        }
    }

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                var parsed = ParseDiagnostics.parse(c.get("source").asText(), LexiconRegistry.getInstance().getOrThrow("en-US"));
                assertEquals(List.of(), parsed.diagnostics(), "夹具源码须能解析");
                var resolved = NameResolution.resolveAndLower(parsed.module(), CTX);
                List<List<Object>> codes = resolved.diagnostics().stream()
                    .map(d -> List.<Object>of(d.code().code(), d.span().map(o -> o.start.line).orElse(0)))
                    .toList();
                List<List<Object>> expected = new ArrayList<>();
                c.get("codes").forEach(p -> expected.add(List.of(p.get(0).asText(), p.get(1).asInt())));
                assertEquals(expected, codes);
                if (!c.has("refs")) return;
                List<String> refs = new ArrayList<>();
                for (JsonNode d : MAPPER.readTree(CoreIrJson.serialize(resolved.core())).get("decls")) {
                    if (d.path("kind").asText().equals("Func")) refsOf(d, refs);
                }
                List<String> expectedRefs = new ArrayList<>();
                c.get("refs").forEach(r -> expectedRefs.add(r.asText()));
                assertEquals(expectedRefs, refs);
            }));
        }
        assertEquals(18, tests.size());
        return tests.stream();
    }
}
