package aster.core.identifier;

/**
 * 标识符类型枚举。
 *
 * 定义领域词汇表中可以映射的标识符类型。
 */
public enum IdentifierKind {
    /**
     * 结构体/类型名称
     * 例如：Driver（驾驶员）、Vehicle（车辆）
     */
    STRUCT("struct"),

    /**
     * 字段/属性名称
     * 例如：age（年龄）、drivingYears（驾龄）
     */
    FIELD("field"),

    /**
     * 函数/方法名称
     * 例如：calculatePremium（计算保费）
     */
    FUNCTION("function"),

    /**
     * 枚举值
     * 例如：Excellent（优秀）、Good（良好）
     */
    ENUM_VALUE("enum_value");

    private final String value;

    IdentifierKind(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 从字符串解析枚举值（大小写不敏感）
     */
    public static IdentifierKind fromString(String text) {
        for (IdentifierKind kind : IdentifierKind.values()) {
            if (kind.value.equalsIgnoreCase(text)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown IdentifierKind: " + text);
    }
}
