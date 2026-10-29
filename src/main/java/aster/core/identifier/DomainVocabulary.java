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
    List<IdentifierMapping> literals,
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
        if (literals == null) literals = List.of();
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
        all.addAll(literals);
        return all;
    }

    /**
     * 验证词汇表（ADR 0051：名称按位置解析），规则与 TS {@code validateVocabulary} 逐条一致：
     * <ul>
     *   <li>规范名必须是 ASCII 标识符；字面量宏内容做注入防护。</li>
     *   <li>字段与枚举成员必须有 parent（按父类型分作用域；不支持全局字段）。</li>
     *   <li>冲突只在同一角色、同一作用域内判定：NFC 后写法（本地化名或同义词）完全相同却对应不同规范名 → error。
     *       同规范名的多个写法（同义词）、仅大小写不同的写法、不同父类型下的同名字段都合法，使用点由解析器
     *       按位置精确优先、折叠唯一再判定。</li>
     *   <li>字面量宏触发词须全局唯一：canonicalize 在解析前做上下文无关替换，触发词若与任何其它映射同名，
     *       一个词展开成字符串还是标识符就不可预测。</li>
     * </ul>
     *
     * @return 验证结果（warnings 目前恒为空，保留字段以兼容调用方）
     */
    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();
        Validation v = new Validation(errors);
        checkAll(v, "structs", structs);
        checkAll(v, "fields", fields);
        checkAll(v, "functions", functions);
        checkAll(v, "enumValues", enumValues);
        checkAll(v, "literals", literals);
        return new ValidationResult(errors.isEmpty(), errors, List.of());
    }

    private static void checkAll(Validation v, String list, List<IdentifierMapping> mappings) {
        for (int i = 0; i < mappings.size(); i++) v.check(mappings.get(i), list + "[" + i + "]");
    }

    /** 一次校验的累积状态：作用域内写法与字面量宏触发词。 */
    private static final class Validation {
        private record Seen(String canonical, String context) {}

        private final List<String> errors;
        /** 作用域 + NFC 写法 → 首个映射的规范名与位置，用于判定同写法不同规范名。 */
        private final java.util.Map<String, Seen> seenWritten = new java.util.HashMap<>();
        /** 触发词（小写）→ 是否字面量宏；一旦记为字面量宏不回退，保证后续普通标识符同名也报错。 */
        private final java.util.Map<String, Boolean> seenTrigger = new java.util.HashMap<>();

        Validation(List<String> errors) {
            this.errors = errors;
        }

        void check(IdentifierMapping mapping, String context) {
            checkCanonical(mapping, context);
            String missingParent = missingParentMessage(mapping, context);
            if (missingParent != null) errors.add(missingParent);
            List<String> triggers = new ArrayList<>();
            triggers.add(mapping.localized());
            if (mapping.aliases() != null) triggers.addAll(mapping.aliases());
            for (String trigger : triggers) {
                checkScopeConflict(mapping, context, CaseFold.nfc(trigger));
                checkTrigger(mapping, context, trigger);
            }
        }

        /** 字面量宏内容的注入防护校验；普通标识符校验规范名是 ASCII 标识符。 */
        private void checkCanonical(IdentifierMapping mapping, String context) {
            if (mapping.kind() != IdentifierKind.LITERAL) {
                if (!mapping.isValidCanonical()) {
                    errors.add(context + ": 规范化名称 \"" + mapping.canonical() + "\" 必须是有效的 ASCII 标识符");
                }
                return;
            }
            if (!mapping.isValidLiteralContent()) {
                errors.add(context + ": 字面量宏内容 \"" + mapping.canonical()
                    + "\" 须为单行、非空、无控制字符、无引号定界符或反斜杠（防编译期注入）");
            }
        }

        private void checkScopeConflict(IdentifierMapping mapping, String context, String written) {
            String scope = scopeOf(mapping);
            if (scope == null) return;
            String key = scope + '\u0000' + written;
            Seen prev = seenWritten.get(key);
            if (prev == null) {
                seenWritten.put(key, new Seen(mapping.canonical(), context));
            } else if (!prev.canonical().equals(mapping.canonical())) {
                errors.add(context + ": 写法 \"" + written + "\" 在 " + scope + " 中对应多个规范名（"
                    + prev.canonical() + "、" + mapping.canonical() + "，另见 " + prev.context() + "）");
            }
        }

        private void checkTrigger(IdentifierMapping mapping, String context, String trigger) {
            boolean isLit = mapping.kind() == IdentifierKind.LITERAL;
            String key = trigger.toLowerCase(java.util.Locale.ROOT);
            Boolean prev = seenTrigger.get(key);
            if (prev != null && (prev || isLit)) {
                errors.add(context + ": 触发词 \"" + trigger
                    + "\" 与另一条映射冲突（字面量宏触发词须全局唯一，防\"字符串 vs 标识符\"替换歧义）");
            }
            seenTrigger.put(key, (prev != null && prev) || isLit);
        }
    }

    /** 映射所属的解析作用域（ADR 0051 §7）：字段 / 枚举成员按规范父名分作用域；字面量宏不在其中。 */
    private static String scopeOf(IdentifierMapping mapping) {
        return switch (mapping.kind()) {
            case STRUCT -> "types";
            case FUNCTION -> "functions";
            case FIELD -> mapping.parent() == null ? null : "fields[" + mapping.parent() + "]";
            case ENUM_VALUE -> mapping.parent() == null ? null : "enumMembers[" + mapping.parent() + "]";
            default -> null;
        };
    }

    /** 缺 parent 时的诊断（字段与枚举成员措辞不同，下游按文案识别原因）。 */
    private static String missingParentMessage(IdentifierMapping mapping, String context) {
        if (mapping.parent() != null && !mapping.parent().isEmpty()) return null;
        return switch (mapping.kind()) {
            case FIELD -> context + ": 字段 \"" + mapping.localized() + "\" 没有指定父结构体";
            case ENUM_VALUE -> context + ": 枚举成员 \"" + mapping.localized() + "\" 没有指定父枚举";
            default -> null;
        };
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
        private final List<IdentifierMapping> literals = new ArrayList<>();
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

        public Builder addEnumValue(String canonical, String localized, String parent, String... aliases) {
            enumValues.add(IdentifierMapping.enumValue(canonical, localized, parent, aliases));
            return this;
        }

        /** 字面量宏：localized token 展开成字符串字面量 content（不含引号）。 */
        public Builder addLiteral(String content, String localized, String... aliases) {
            literals.add(IdentifierMapping.literal(content, localized, aliases));
            return this;
        }

        public DomainVocabulary build() {
            return new DomainVocabulary(
                id, name, locale, version,
                List.copyOf(structs),
                List.copyOf(fields),
                List.copyOf(functions),
                List.copyOf(enumValues),
                List.copyOf(literals),
                metadata
            );
        }
    }
}
