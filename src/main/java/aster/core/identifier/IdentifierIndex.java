package aster.core.identifier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 标识符索引：规范名 → 本地化名的反查与按类别 / 父结构体的分类视图（供展示与校验）。
 *
 * <p>不提供本地化名 → 规范名的扁平翻译：领域词汇按位置在解析后解析（ADR 0051，见
 * {@link VocabularyScopes} 与 {@code aster.core.resolve}），字面量宏由 Canonicalizer 按
 * {@link VocabularyScopes#literals()} 展开。与 TS IdentifierIndex 同构。
 */
public final class IdentifierIndex {

    /** 规范化名称 → 本地化名称（大小写不敏感） */
    private final Map<String, String> toLocalized;

    /** 按类型分类的映射：kind → (localized → mapping) */
    private final Map<IdentifierKind, Map<String, IdentifierMapping>> byKind;

    /** 按父结构体索引的字段：parent → (localized → mapping) */
    private final Map<String, Map<String, IdentifierMapping>> fieldsByParent;

    /** 源词汇表 */
    private final DomainVocabulary vocabulary;

    private IdentifierIndex(
        Map<String, String> toLocalized,
        Map<IdentifierKind, Map<String, IdentifierMapping>> byKind,
        Map<String, Map<String, IdentifierMapping>> fieldsByParent,
        DomainVocabulary vocabulary
    ) {
        this.toLocalized = Collections.unmodifiableMap(toLocalized);
        this.byKind = Collections.unmodifiableMap(byKind);
        this.fieldsByParent = Collections.unmodifiableMap(fieldsByParent);
        this.vocabulary = vocabulary;
    }

    /**
     * 从词汇表构建索引。
     *
     * @param vocabulary 领域词汇表
     * @return 标识符索引
     */
    public static IdentifierIndex build(DomainVocabulary vocabulary) {
        Map<String, String> toLocalized = new HashMap<>();
        Map<IdentifierKind, Map<String, IdentifierMapping>> byKind = new EnumMap<>(IdentifierKind.class);
        Map<String, Map<String, IdentifierMapping>> fieldsByParent = new HashMap<>();

        for (IdentifierKind kind : IdentifierKind.values()) {
            byKind.put(kind, new HashMap<>());
        }

        for (IdentifierMapping mapping : vocabulary.allMappings()) {
            // 字面量宏是单向宏展开，不建反向映射（与 TS 一致，避免 toLocalized 暴露内容反查）。
            if (mapping.kind() != IdentifierKind.LITERAL) {
                toLocalized.put(mapping.canonical().toLowerCase(Locale.ROOT), mapping.localized());
            }
            byKind.get(mapping.kind()).put(mapping.localized(), mapping);
            if (mapping.kind() == IdentifierKind.FIELD && mapping.parent() != null) {
                fieldsByParent
                    .computeIfAbsent(mapping.parent(), k -> new HashMap<>())
                    .put(mapping.localized(), mapping);
            }
        }

        return new IdentifierIndex(toLocalized, byKind, fieldsByParent, vocabulary);
    }

    /**
     * 将规范化名称转换为本地化名称。
     *
     * @param canonical 规范化名称
     * @return 本地化名称，如果未找到则返回原值
     */
    public String localize(String canonical) {
        if (canonical == null) {
            return null;
        }
        String result = toLocalized.get(canonical.toLowerCase(Locale.ROOT));
        return result != null ? result : canonical;
    }

    /**
     * 获取指定类型的所有映射。
     *
     * @param kind 标识符类型
     * @return 映射表（本地化名称 → 映射）
     */
    public Map<String, IdentifierMapping> getByKind(IdentifierKind kind) {
        return byKind.getOrDefault(kind, Collections.emptyMap());
    }

    /**
     * 获取指定父结构体的所有字段映射。
     *
     * @param parent 父结构体规范化名称
     * @return 字段映射表（本地化名称 → 映射）
     */
    public Map<String, IdentifierMapping> getFieldsByParent(String parent) {
        return fieldsByParent.getOrDefault(parent, Collections.emptyMap());
    }

    /**
     * 获取源词汇表。
     */
    public DomainVocabulary getVocabulary() {
        return vocabulary;
    }

    /**
     * 获取规范化到本地化的映射表（只读）。
     */
    public Map<String, String> getToLocalizedMap() {
        return toLocalized;
    }
}
