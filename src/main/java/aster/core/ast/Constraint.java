package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 字段 / 形参约束（源码 {@code required}、{@code between X and Y}、{@code matching "…"}，ADR 0055 #4）。
 * <p>
 * Range 的两端保留源码字面量原文（含负号），规范十进制串由 lowering 经 BigDecimal 求出，不经二进制浮点。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Constraint.Required.class, name = "Required"),
    @JsonSubTypes.Type(value = Constraint.Range.class, name = "Range"),
    @JsonSubTypes.Type(value = Constraint.Pattern.class, name = "Pattern")
})
public sealed interface Constraint permits Constraint.Required, Constraint.Range, Constraint.Pattern {

    /** 约束在源码中的位置（从约束关键字起至最后一个操作数止）。 */
    Span span();

    /**
     * 必填约束。
     *
     * @param span 源码位置
     */
    @JsonTypeName("Required")
    record Required(@JsonProperty("span") Span span) implements Constraint {}

    /**
     * 范围约束。
     *
     * @param min  下界字面量原文（如 {@code -0.5}、{@code 18}）；无下界为 null
     * @param max  上界字面量原文；无上界为 null
     * @param span 源码位置
     */
    @JsonTypeName("Range")
    record Range(
        @JsonProperty("min") String min,
        @JsonProperty("max") String max,
        @JsonProperty("span") Span span
    ) implements Constraint {}

    /**
     * 正则约束。
     *
     * @param regexp 正则表达式（字符串字面量反转义后的内容）
     * @param span   源码位置
     */
    @JsonTypeName("Pattern")
    record Pattern(@JsonProperty("regexp") String regexp, @JsonProperty("span") Span span) implements Constraint {}
}
