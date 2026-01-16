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
    public static IdentifierMapping enumValue(String canonical, String localized) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.ENUM_VALUE, null, null, null);
    }

    /**
     * 创建枚举值映射的便捷方法（带别名）。
     */
    public static IdentifierMapping enumValue(String canonical, String localized, String... aliases) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.ENUM_VALUE, null, null, List.of(aliases));
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
