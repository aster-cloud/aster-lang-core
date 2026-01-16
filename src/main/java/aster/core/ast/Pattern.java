package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * 模式节点（sealed interface）
 * <p>
 * 用于 Match 语句中的模式匹配，包含 Null、构造器、名称、整数四种模式。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Pattern.PatternNull.class, name = "PatternNull"),
    @JsonSubTypes.Type(value = Pattern.PatternCtor.class, name = "PatternCtor"),
    @JsonSubTypes.Type(value = Pattern.PatternName.class, name = "PatternName"),
    @JsonSubTypes.Type(value = Pattern.PatternInt.class, name = "PatternInt")
})
public sealed interface Pattern extends AstNode
    permits Pattern.PatternNull, Pattern.PatternCtor, Pattern.PatternName, Pattern.PatternInt {

    /**
     * Null 模式（匹配 null 值）
     *
     * @param span 源码位置信息
     */
    @JsonTypeName("PatternNull")
    record PatternNull(
        @JsonProperty("span") Span span
    ) implements Pattern {
        @Override
        public String kind() {
            return "PatternNull";
        }
    }

    /**
     * 构造器模式（匹配构造的值，如 Some(x), Ok(value)）
     *
     * @param typeName 类型名称（如 "Some", "Ok"）
     * @param names    绑定的变量名列表
     * @param args     嵌套模式列表（可能为 null）
     * @param span     源码位置信息
     */
    @JsonTypeName("PatternCtor")
    record PatternCtor(
        @JsonProperty("typeName") String typeName,
        @JsonProperty("names") List<String> names,
        @JsonProperty("args") List<Pattern> args,
        @JsonProperty("span") Span span
    ) implements Pattern {
        @Override
        public String kind() {
            return "PatternCtor";
        }
    }

    /**
     * 名称模式（绑定变量名）
     *
     * @param name 变量名称
     * @param span 源码位置信息
     */
    @JsonTypeName("PatternName")
    record PatternName(
        @JsonProperty("name") String name,
        @JsonProperty("span") Span span
    ) implements Pattern {
        @Override
        public String kind() {
            return "PatternName";
        }
    }

    /**
     * 整数模式（匹配具体整数值）
     *
     * @param value 整数值
     * @param span  源码位置信息
     */
    @JsonTypeName("PatternInt")
    record PatternInt(
        @JsonProperty("value") int value,
        @JsonProperty("span") Span span
    ) implements Pattern {
        @Override
        public String kind() {
            return "PatternInt";
        }
    }
}
