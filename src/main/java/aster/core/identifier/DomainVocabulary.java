package aster.core.identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 领域词汇表。
 *
 * 包含特定业务领域的所有标识符映射。
 *
 * @param id        领域标识符（如 "insurance.auto"、"finance.loan"）
 * @param name      领域名称（用于显示）
 * @param locale    语言代码（如 "zh-CN"、"en-US"）
 * @param version   版本号
 * @param structs   结构体映射列表
 * @param fields    字段映射列表
 * @param functions 函数映射列表
 * @param enumValues 枚举值映射列表
 * @param metadata  元数据（可选）
 */
public record DomainVocabulary(
    String id,
    String name,
    String locale,
    String version,
    List<IdentifierMapping> structs,
    List<IdentifierMapping> fields,
    List<IdentifierMapping> functions,
    List<IdentifierMapping> enumValues,
    VocabularyMetadata metadata
) {
    /**
     * 词汇表元数据。
     */
    public record VocabularyMetadata(
        String author,
        String createdAt,
        String description
    ) {}

    /**
     * 紧凑构造函数，验证必需字段。
     */
    public DomainVocabulary {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(locale, "locale is required");
        Objects.requireNonNull(version, "version is required");

        // 确保列表不为 null
        if (structs == null) structs = List.of();
        if (fields == null) fields = List.of();
        if (functions == null) functions = List.of();
        if (enumValues == null) enumValues = List.of();
    }

    /**
     * 获取所有映射的统一列表。
     */
    public List<IdentifierMapping> allMappings() {
        List<IdentifierMapping> all = new ArrayList<>();
        all.addAll(structs);
        all.addAll(fields);
        all.addAll(functions);
        all.addAll(enumValues);
        return all;
    }

    /**
     * 验证词汇表。
     *
     * @return 验证结果
     */
    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 验证所有映射
        for (IdentifierMapping mapping : allMappings()) {
            if (!mapping.isValidCanonical()) {
                errors.add("无效的规范化名称 '" + mapping.canonical() +
                    "': 必须是有效的 ASCII 标识符");
            }

            // 检查字段是否有父结构体
            if (mapping.kind() == IdentifierKind.FIELD && mapping.parent() == null) {
                warnings.add("字段 '" + mapping.canonical() +
                    "' 没有指定父结构体");
            }
        }

        // 检查结构体名称是否唯一
        List<String> structNames = structs.stream()
            .map(IdentifierMapping::canonical)
            .toList();
        for (int i = 0; i < structNames.size(); i++) {
            for (int j = i + 1; j < structNames.size(); j++) {
                if (structNames.get(i).equalsIgnoreCase(structNames.get(j))) {
                    errors.add("重复的结构体名称: " + structNames.get(i));
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * 验证结果。
     */
    public record ValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings
    ) {}

    /**
     * Builder 模式用于构建词汇表。
     */
    public static Builder builder(String id, String name, String locale) {
        return new Builder(id, name, locale);
    }

    /**
     * 词汇表构建器。
     */
    public static class Builder {
        private final String id;
        private final String name;
        private final String locale;
        private String version = "1.0.0";
        private final List<IdentifierMapping> structs = new ArrayList<>();
        private final List<IdentifierMapping> fields = new ArrayList<>();
        private final List<IdentifierMapping> functions = new ArrayList<>();
        private final List<IdentifierMapping> enumValues = new ArrayList<>();
        private VocabularyMetadata metadata;

        private Builder(String id, String name, String locale) {
            this.id = id;
            this.name = name;
            this.locale = locale;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder metadata(String author, String createdAt, String description) {
            this.metadata = new VocabularyMetadata(author, createdAt, description);
            return this;
        }

        public Builder addStruct(String canonical, String localized, String... aliases) {
            structs.add(IdentifierMapping.struct(canonical, localized, aliases));
            return this;
        }

        public Builder addField(String canonical, String localized, String parent, String... aliases) {
            fields.add(IdentifierMapping.field(canonical, localized, parent, aliases));
            return this;
        }

        public Builder addFunction(String canonical, String localized, String... aliases) {
            functions.add(IdentifierMapping.function(canonical, localized, aliases));
            return this;
        }

        public Builder addEnumValue(String canonical, String localized, String... aliases) {
            enumValues.add(IdentifierMapping.enumValue(canonical, localized, aliases));
            return this;
        }

        public DomainVocabulary build() {
            return new DomainVocabulary(
                id, name, locale, version,
                List.copyOf(structs),
                List.copyOf(fields),
                List.copyOf(functions),
                List.copyOf(enumValues),
                metadata
            );
        }
    }
}
