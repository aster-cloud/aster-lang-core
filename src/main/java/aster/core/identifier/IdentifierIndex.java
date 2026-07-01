package aster.core.identifier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 标识符索引。
 *
 * 提供双向映射的快速查找，支持大小写不敏感匹配。
 */
public final class IdentifierIndex {

    /** 本地化名称 → 规范化名称（大小写不敏感） */
    private final Map<String, String> toCanonical;

    /** 规范化名称 → 本地化名称（大小写不敏感） */
    private final Map<String, String> toLocalized;

    /** 按类型分类的映射：kind → (localized → mapping) */
    private final Map<IdentifierKind, Map<String, IdentifierMapping>> byKind;

    /** 按父结构体索引的字段：parent → (localized → mapping) */
    private final Map<String, Map<String, IdentifierMapping>> fieldsByParent;

    /**
     * 字面量宏的 localized/alias key（已小写）。canonicalize 替换时据此判定：命中则把
     * toCanonical 里存的**内容**用 lexicon 引号包裹后插入，而非当作标识符原样插入。
     * 与 aster-lang-ts IdentifierIndex.literals 对称。
     */
    private final Set<String> literals;

    /** 源词汇表 */
    private final DomainVocabulary vocabulary;

    private IdentifierIndex(
        Map<String, String> toCanonical,
        Map<String, String> toLocalized,
        Map<IdentifierKind, Map<String, IdentifierMapping>> byKind,
        Map<String, Map<String, IdentifierMapping>> fieldsByParent,
        Set<String> literals,
        DomainVocabulary vocabulary
    ) {
        this.toCanonical = Collections.unmodifiableMap(toCanonical);
        this.toLocalized = Collections.unmodifiableMap(toLocalized);
        this.byKind = Collections.unmodifiableMap(byKind);
        this.fieldsByParent = Collections.unmodifiableMap(fieldsByParent);
        this.literals = Collections.unmodifiableSet(literals);
        this.vocabulary = vocabulary;
    }

    /**
     * 从词汇表构建索引。
     *
     * @param vocabulary 领域词汇表
     * @return 标识符索引
     */
    public static IdentifierIndex build(DomainVocabulary vocabulary) {
        Map<String, String> toCanonical = new HashMap<>();
        Map<String, String> toLocalized = new HashMap<>();
        Map<IdentifierKind, Map<String, IdentifierMapping>> byKind = new EnumMap<>(IdentifierKind.class);
        Map<String, Map<String, IdentifierMapping>> fieldsByParent = new HashMap<>();
        Set<String> literals = new HashSet<>();

        // 初始化 byKind 映射
        for (IdentifierKind kind : IdentifierKind.values()) {
            byKind.put(kind, new HashMap<>());
        }

        // 处理所有映射
        for (IdentifierMapping mapping : vocabulary.allMappings()) {
            String canonical = mapping.canonical();
            String localized = mapping.localized();
            boolean isLiteral = mapping.kind() == IdentifierKind.LITERAL;
            String localizedKey = localized.toLowerCase(Locale.ROOT);

            // 添加主映射（本地化 → 规范化/字面量内容）。键用小写，与 TS
            // buildIdentifierIndex 一致，保证两引擎对本地化名查找大小写不敏感且等价。
            toCanonical.put(localizedKey, canonical);
            // 字面量宏是单向宏展开，不建反向映射（与 TS 一致，避免 toLocalized 暴露内容反查）。
            if (isLiteral) {
                literals.add(localizedKey);
            } else {
                toLocalized.put(canonical.toLowerCase(Locale.ROOT), localized);
            }

            // 添加别名映射（同样用小写键）
            if (mapping.aliases() != null) {
                for (String alias : mapping.aliases()) {
                    String aliasKey = alias.toLowerCase(Locale.ROOT);
                    toCanonical.put(aliasKey, canonical);
                    if (isLiteral) {
                        literals.add(aliasKey);
                    }
                }
            }

            // 按类型分类
            byKind.get(mapping.kind()).put(localized, mapping);

            // 按父结构体索引字段
            if (mapping.kind() == IdentifierKind.FIELD && mapping.parent() != null) {
                fieldsByParent
                    .computeIfAbsent(mapping.parent(), k -> new HashMap<>())
                    .put(localized, mapping);
            }
        }

        return new IdentifierIndex(toCanonical, toLocalized, byKind, fieldsByParent, literals, vocabulary);
    }

    /**
     * 将本地化名称转换为规范化名称。
     *
     * @param localized 本地化名称
     * @return 规范化名称，如果未找到则返回原值
     */
    public String canonicalize(String localized) {
        if (localized == null) {
            return null;
        }
        // 查找用小写键（与 build 一致）：大小写不敏感，未命中返回原值。
        String result = toCanonical.get(localized.toLowerCase(Locale.ROOT));
        return result != null ? result : localized;
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
        // 大小写不敏感匹配
        String result = toLocalized.get(canonical.toLowerCase(Locale.ROOT));
        return result != null ? result : canonical;
    }

    /**
     * 检查是否存在本地化名称的映射。
     *
     * @param localized 本地化名称
     * @return 是否存在映射
     */
    public boolean hasMapping(String localized) {
        return toCanonical.containsKey(localized);
    }

    /**
     * 该本地化名称（或别名）是否为字面量宏（kind = LITERAL）。命中时 canonicalize()
     * 返回的是**字面量内容**（不含引号），调用方须用 lexicon 引号包裹后插入。
     */
    public boolean isLiteral(String localized) {
        return localized != null && literals.contains(localized.toLowerCase(Locale.ROOT));
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
     * 获取本地化到规范化的映射表（只读）。
     */
    public Map<String, String> getToCanonicalMap() {
        return toCanonical;
    }

    /**
     * 获取规范化到本地化的映射表（只读）。
     */
    public Map<String, String> getToLocalizedMap() {
        return toLocalized;
    }

    /**
     * 获取映射数量。
     */
    public int size() {
        return toCanonical.size();
    }
}
