package aster.core.inference;

import aster.core.ast.Type;
import aster.core.ast.Span;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 类型推断引擎
 *
 * 为没有显式类型标注的字段提供类型推断，与 TypeScript 实现保持一致。
 *
 * 推断优先级（从高到低）：
 * 1. 显式类型声明（由调用方处理，不在本模块）
 * 2. 命名约定推断（*Id → Text, *Amount → Float 等）
 * 3. 默认类型（Text）
 */
public final class TypeInference {

    private TypeInference() {
        // 工具类，不可实例化
    }

    /**
     * 默认类型
     */
    private static final String DEFAULT_TYPE = "Text";

    /**
     * 类型推断规则
     */
    private record NamingRule(Pattern pattern, String type, int priority) {}

    /**
     * 命名约定规则（按优先级排序）
     * 与 TypeScript src/parser/type-inference.ts 保持一致
     */
    private static final List<NamingRule> NAMING_RULES = List.of(
        // 布尔类型 → Bool（优先级 11，最高优先级）
        // 前缀模式必须最先匹配，避免被 Id 后缀模式覆盖（如 isValid 以 id 结尾）
        new NamingRule(Pattern.compile("^(?:is|has|can|should|was|will|did|does|allow|enable|disable|active|valid|require)", Pattern.CASE_INSENSITIVE), "Bool", 11),
        // 后缀模式
        new NamingRule(Pattern.compile("(?:Flag|Enabled|Disabled|Active|Valid|Approved|Rejected|Completed|Confirmed|Sufficient)$", Pattern.CASE_INSENSITIVE), "Bool", 8),

        // ID 类型 → Text（优先级 10）
        // 使用 lookbehind 确保 Id 前面是小写字母（camelCase 命名），避免匹配 isValid 等布尔前缀
        new NamingRule(Pattern.compile("(?<=[a-z])(?:Id|ID|Identifier)$"), "Text", 10),
        new NamingRule(Pattern.compile("(?:Code|Key|Token|Uuid|Guid|Vin)$", Pattern.CASE_INSENSITIVE), "Text", 8),

        // 金额/价格类型 → Float（优先级 10）
        new NamingRule(Pattern.compile("(?:Amount|Price|Cost|Fee|Total|Balance|Salary|Income|Payment|Percentage|Ratio)$", Pattern.CASE_INSENSITIVE), "Float", 10),
        new NamingRule(Pattern.compile("(?:Rate|Interest)$", Pattern.CASE_INSENSITIVE), "Float", 9),

        // 计数/数量类型 → Int（优先级 10）
        new NamingRule(Pattern.compile("(?:Count|Number|Qty|Quantity|Age|Score|Level|Rank|Index|Size|Length|Width|Height)$", Pattern.CASE_INSENSITIVE), "Int", 10),
        // 时间周期模式：支持中间位置匹配（如 daysRemaining）以及末尾匹配（如 termMonths）
        new NamingRule(Pattern.compile("(?:Years?|Months?|Weeks?|Days?|Hours?|Minutes?|Seconds?)(?:[A-Z]|$)", Pattern.CASE_INSENSITIVE), "Int", 9),

        // 日期时间类型 → DateTime（优先级 10）
        new NamingRule(Pattern.compile("(?:Date|Time|At|Timestamp|Created|Updated|Modified|Expired|Birthday|Anniversary)$", Pattern.CASE_INSENSITIVE), "DateTime", 10),

        // 分类/状态类型 → Text（优先级 8）
        new NamingRule(Pattern.compile("(?:Type|Status|Category|Kind|Mode)$", Pattern.CASE_INSENSITIVE), "Text", 8)
    );

    /**
     * 从字段名推断类型
     *
     * @param fieldName 字段名
     * @return 推断的 AST 类型节点
     */
    public static Type inferFieldType(String fieldName) {
        return inferFieldType(fieldName, null);
    }

    /**
     * 从字段名推断类型（带源代码位置）
     *
     * @param fieldName 字段名
     * @param span      源代码位置（可选）
     * @return 推断的 AST 类型节点
     */
    public static Type inferFieldType(String fieldName, Span span) {
        String typeName = inferTypeNameFromFieldName(fieldName);
        return new Type.TypeName(typeName, List.of(), span);
    }

    /**
     * 从字段名推断类型名称
     *
     * @param fieldName 字段名
     * @return 类型名称（如 "Int", "Text", "Bool"）
     */
    public static String inferTypeNameFromFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return DEFAULT_TYPE;
        }

        NamingRule matchedRule = null;

        for (NamingRule rule : NAMING_RULES) {
            if (!rule.pattern.matcher(fieldName).find()) {
                continue;
            }
            // 选择优先级最高的匹配规则
            if (matchedRule == null || rule.priority > matchedRule.priority) {
                matchedRule = rule;
            }
        }

        return matchedRule != null ? matchedRule.type : DEFAULT_TYPE;
    }

    /**
     * 检查类型名称是否为基本类型
     *
     * @param typeName 类型名称
     * @return 是否为基本类型
     */
    public static boolean isPrimitiveType(String typeName) {
        return typeName != null && (
            "Int".equals(typeName) ||
            "Float".equals(typeName) ||
            "Bool".equals(typeName) ||
            "Text".equals(typeName) ||
            "DateTime".equals(typeName) ||
            "Long".equals(typeName) ||
            "Double".equals(typeName)
        );
    }
}
