package aster.core.identifier;

import java.util.List;
import java.util.Objects;

/**
 * 单个标识符映射。
 *
 * 定义一个本地化名称与规范化（ASCII）名称之间的映射关系。
 *
 * @param canonical   规范化名称（ASCII，用于编译器）
 * @param localized   本地化名称（用于用户界面）
 * @param kind        标识符类型
 * @param parent      父结构体名称（仅用于字段类型）
 * @param description 描述信息
 * @param aliases     别名列表（可选的其他本地化名称）
 */
public record IdentifierMapping(
    String canonical,
    String localized,
    IdentifierKind kind,
    String parent,
    String description,
    List<String> aliases
) {
    /**
     * 紧凑构造函数，验证必需字段。
     */
    public IdentifierMapping {
        Objects.requireNonNull(canonical, "canonical name is required");
        Objects.requireNonNull(localized, "localized name is required");
        Objects.requireNonNull(kind, "kind is required");
        // parent 仅对 FIELD 类型是必需的
        // description 和 aliases 是可选的
        if (aliases == null) {
            aliases = List.of();
        }
    }

    /**
     * 创建结构体映射的便捷方法。
     */
    public static IdentifierMapping struct(String canonical, String localized) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.STRUCT, null, null, null);
    }

    /**
     * 创建结构体映射的便捷方法（带别名）。
     */
    public static IdentifierMapping struct(String canonical, String localized, String... aliases) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.STRUCT, null, null, List.of(aliases));
    }

    /**
     * 创建字段映射的便捷方法。
     */
    public static IdentifierMapping field(String canonical, String localized, String parent) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.FIELD, parent, null, null);
    }

    /**
     * 创建字段映射的便捷方法（带别名）。
     */
    public static IdentifierMapping field(String canonical, String localized, String parent, String... aliases) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.FIELD, parent, null, List.of(aliases));
    }

    /**
     * 创建函数映射的便捷方法。
     */
    public static IdentifierMapping function(String canonical, String localized) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.FUNCTION, null, null, null);
    }

    /**
     * 创建函数映射的便捷方法（带别名）。
     */
    public static IdentifierMapping function(String canonical, String localized, String... aliases) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.FUNCTION, null, null, List.of(aliases));
    }

    /**
     * 创建枚举值映射的便捷方法。
     */
    public static IdentifierMapping enumValue(String canonical, String localized, String parent) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.ENUM_VALUE, parent, null, null);
    }

    /**
     * 创建枚举值映射的便捷方法（带别名）。
     */
    public static IdentifierMapping enumValue(String canonical, String localized, String parent, String... aliases) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.ENUM_VALUE, parent, null, List.of(aliases));
    }

    /**
     * 创建字面量宏映射（kind = LITERAL）。{@code content} 为字符串字面量**内容**（不含引号），
     * canonicalize 时把 {@code localized} token 展开成 {@code <open>content<close>}。
     */
    public static IdentifierMapping literal(String content, String localized, String... aliases) {
        return new IdentifierMapping(content, localized, IdentifierKind.LITERAL, null, null, List.of(aliases));
    }

    /**
     * 字面量宏内容校验：非空、单行、无控制字符（0x00-0x1F/0x7F）、不含无从转义的字符串定界符（「」『』«»）。
     * 与 aster-lang-ts validateVocabulary 的 LITERAL 分支逐条对齐（防编译期文本注入）。
     *
     * <p>反斜杠、ASCII 双引号与弯引号 “ ” ‘ ’ 允许（ADR 0060 修订 1 R18，aster-lang-ts#362 第 5 项）：展开时
     * {@code \} 与 {@code "} 由 Canonicalizer 转义（aster-lang-ts#345），词法器读回的就是原值；弯引号只在充当
     * 字符串定界符时才被引号归一改写（aster-lang-ts#347），展开出的字符串里它们是内容，重新规范化也不变。
     * 「」『』«» 是各词法表的字符串定界符，词法器没有对应的转义，含它们的内容写不成字面量，照旧拒绝。
     */
    public boolean isValidLiteralContent() {
        if (canonical == null || canonical.isEmpty()) {
            return false;
        }
        for (int i = 0; i < canonical.length(); i++) {
            char c = canonical.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                return false; // 控制字符/换行
            }
            if (c == '「' || c == '」' || c == '『' || c == '』' || c == '«' || c == '»') {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查规范化名称是否有效（必须是 ASCII 标识符）。
     */
    public boolean isValidCanonical() {
        if (canonical == null || canonical.isEmpty()) {
            return false;
        }
        // 首字符必须是字母或下划线
        char first = canonical.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        // 后续字符必须是字母、数字或下划线
        for (int i = 1; i < canonical.length(); i++) {
            char c = canonical.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        // 检查是否全部为 ASCII
        return canonical.chars().allMatch(c -> c < 128);
    }
}
