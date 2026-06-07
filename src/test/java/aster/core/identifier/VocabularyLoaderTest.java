package aster.core.identifier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VocabularyLoader 单元测试。
 *
 * 重点回归 ADR 0014 线C 本地 E2E 实证的坑：aster-lang-ts / aster-cloud 序列化
 * 的 DomainVocabulary，每个 mapping 都带 `kind` 字段（TS IdentifierMapping 含
 * kind），而 Java 侧按所在数组推断 kind、JsonMapping 不声明该字段。若 loader
 * 严格 FAIL_ON_UNKNOWN，跨引擎传来的词汇 JSON 会整体解析失败 → 静默退化为
 * 仅内置（不翻译用户词）。loader 必须容忍这些冗余字段。
 */
class VocabularyLoaderTest {

    /** 模拟 cloud/TS 发来的、每个 mapping 带 kind 的词汇 Map。 */
    private static Map<String, Object> vocabMapWithKind() {
        return Map.of(
            "id", "insurance.custom",
            "name", "Custom",
            "locale", "en-US",
            "version", "user",
            "structs", List.of(Map.of(
                "canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
            "fields", List.of(Map.of(
                "canonical", "age", "localized", "alter", "kind", "field", "parent", "Driver")),
            "functions", List.of(),
            "enumValues", List.of()
        );
    }

    @Test
    void loadFromMap_tolerates_redundant_kind_field() {
        DomainVocabulary vocab = VocabularyLoader.loadFromMap(vocabMapWithKind());

        assertEquals("insurance.custom", vocab.id());
        assertEquals(1, vocab.structs().size());
        assertEquals(1, vocab.fields().size());

        IdentifierIndex index = IdentifierIndex.build(vocab);
        // 结构体 + 字段都应可翻译（含大小写不敏感，与 TS 等价）。
        assertEquals("Driver", index.canonicalize("Fahrer"));
        assertEquals("Driver", index.canonicalize("fahrer"));
        assertEquals("age", index.canonicalize("alter"));
        assertEquals("age", index.canonicalize("ALTER"));
    }

    @Test
    void loadFromString_tolerates_redundant_kind_field() throws Exception {
        String json = """
            {
              "id": "insurance.custom", "name": "Custom",
              "locale": "en-US", "version": "user",
              "structs": [{"canonical": "Driver", "localized": "Fahrer", "kind": "struct"}],
              "fields": [{"canonical": "age", "localized": "alter", "kind": "field", "parent": "Driver"}],
              "functions": [], "enumValues": []
            }
            """;
        DomainVocabulary vocab = VocabularyLoader.loadFromString(json);
        IdentifierIndex index = IdentifierIndex.build(vocab);
        assertEquals("Driver", index.canonicalize("Fahrer"));
        assertEquals("age", index.canonicalize("alter"));
    }
}
