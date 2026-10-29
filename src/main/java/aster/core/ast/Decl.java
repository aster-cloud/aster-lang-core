package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * 顶层声明节点（sealed interface）
 * <p>
 * 包含 Import（导入）、Data（数据类型）、Enum（枚举）、Func（函数）四种声明。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Decl.Import.class, name = "Import"),
    @JsonSubTypes.Type(value = Decl.Data.class, name = "Data"),
    @JsonSubTypes.Type(value = Decl.Enum.class, name = "Enum"),
    @JsonSubTypes.Type(value = Decl.TypeAlias.class, name = "TypeAlias"),
    @JsonSubTypes.Type(value = Decl.Func.class, name = "Func")
})
public sealed interface Decl extends AstNode permits Decl.Import, Decl.Data, Decl.Enum, Decl.TypeAlias, Decl.Func {

    /**
     * Import 导入声明
     *
     * @param path  模块路径（允许多段）
     * @param version 钉住的模块版本（可能为 null）
     * @param alias   别名（可能为 null）
     * @param span    源码位置信息
     */
    @JsonTypeName("Import")
    record Import(
        @JsonProperty("path") String path,
        @JsonProperty("version") Integer version,
        @JsonProperty("alias") String alias,
        @JsonProperty("span") Span span
    ) implements Decl {
        @Override
        public String kind() {
            return "Import";
        }
    }

    /**
     * Data 数据类型声明
     *
     * @param name     类型名称（源码原写法）
     * @param fields   字段列表
     * @param span     源码位置信息
     * @param resolved 声明名的解析结果（ADR 0051，TYPE_DECL；解析前为 null）
     */
    @JsonTypeName("Data")
    record Data(
        @JsonProperty("name") String name,
        @JsonProperty("fields") List<Field> fields,
        @JsonProperty("span") Span span,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved
    ) implements Decl {
        /** 未解析的数据声明。 */
        public Data(String name, List<Field> fields, Span span) {
            this(name, fields, span, null);
        }

        @Override
        public String kind() {
            return "Data";
        }
    }

    /**
     * Field 字段定义（用于 Data 类型）
     *
     * @param name        字段名称（源码原写法）
     * @param type        字段类型
     * @param annotations 注解列表（可能为 null）
     * @param resolved    字段声明名的解析结果（ADR 0051，FIELD_DECL；解析前为 null）
     * @param span        字段条目位置（字段名起至类型止，与 TS 一致）；手工构建的 AST 可为 null，诊断退回声明位置
     */
    record Field(
        @JsonProperty("name") String name,
        @JsonProperty("type") Type type,
        @JsonProperty("annotations") List<Annotation> annotations,
        /** 源码未声明类型（`Define X has a, b.`）：type 是按字段名推断的占位，成员访问时按动态值处理，与 TS 对齐。 */
        @JsonProperty("typeInferred") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean typeInferred,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("span") Span span
    ) {
        /** 旧签名构造器：显式类型字段。 */
        public Field(String name, Type type, List<Annotation> annotations) {
            this(name, type, annotations, false);
        }

        /** 未解析的字段。 */
        public Field(String name, Type type, List<Annotation> annotations, boolean typeInferred) {
            this(name, type, annotations, typeInferred, null, null);
        }

        /** 未解析、带条目位置的字段。 */
        public Field(String name, Type type, List<Annotation> annotations, boolean typeInferred, Span span) {
            this(name, type, annotations, typeInferred, null, span);
        }

        /** 无条目位置的已解析字段。 */
        public Field(String name, Type type, List<Annotation> annotations, boolean typeInferred, Resolved resolved) {
            this(name, type, annotations, typeInferred, resolved, null);
        }
    }

    /**
     * Enum 枚举声明
     *
     * @param name             枚举名称（源码原写法）
     * @param variants         变体列表（源码原写法）
     * @param span             源码位置信息
     * @param resolved         声明名的解析结果（ADR 0051，TYPE_DECL；解析前为 null）
     * @param resolvedVariants 变体的解析结果（MEMBER_DECL，与 variants 同长；解析前为 null）
     * @param variantSpans     各变体的位置（与 variants 同长，与 TS 同名）；手工构建的 AST 可为 null，诊断退回声明位置
     */
    @JsonTypeName("Enum")
    record Enum(
        @JsonProperty("name") String name,
        @JsonProperty("variants") List<String> variants,
        @JsonProperty("span") Span span,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolvedVariants") List<Resolved> resolvedVariants,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("variantSpans") List<Span> variantSpans
    ) implements Decl {
        /** 未解析的枚举声明。 */
        public Enum(String name, List<String> variants, Span span) {
            this(name, variants, span, null, null, null);
        }

        /** 未解析、带变体位置的枚举声明。 */
        public Enum(String name, List<String> variants, Span span, List<Span> variantSpans) {
            this(name, variants, span, null, null, variantSpans);
        }

        /** 无变体位置的已解析枚举声明。 */
        public Enum(String name, List<String> variants, Span span, Resolved resolved, List<Resolved> resolvedVariants) {
            this(name, variants, span, resolved, resolvedVariants, null);
        }

        /** 第 i 个变体的位置；无条目位置时退回整条声明。 */
        public Span variantSpan(int i) {
            return variantSpans != null && i < variantSpans.size() && variantSpans.get(i) != null ? variantSpans.get(i) : span;
        }

        @Override
        public String kind() {
            return "Enum";
        }
    }

    /**
     * TypeAlias 类型别名声明
     *
     * @param annotations 注解名称列表
     * @param name        别名名称（源码原写法）
     * @param type        目标类型
     * @param span        源码位置信息
     * @param resolved    别名名的解析结果（ADR 0051，TYPE_DECL；解析前为 null）
     */
    @JsonTypeName("TypeAlias")
    record TypeAlias(
        @JsonProperty("annotations") List<String> annotations,
        @JsonProperty("name") String name,
        @JsonProperty("type") Type type,
        @JsonProperty("span") Span span,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved
    ) implements Decl {
        public TypeAlias {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        /** 未解析的类型别名。 */
        public TypeAlias(List<String> annotations, String name, Type type, Span span) {
            this(annotations, name, type, span, null);
        }

        @Override
        public String kind() {
            return "TypeAlias";
        }
    }

    /**
     * Func 函数声明
     *
     * @param name                 函数名称
     * @param typeParams           类型参数列表
     * @param params               参数列表
     * @param retType              返回类型
     * @param annotations          函数声明注解列表（可能为 null）
     * @param retAnnotations       返回类型注解列表（可能为 null）
     * @param body                 函数体（可能为 null，表示外部函数）
     * @param effects              效应列表（无副作用时为空列表）
     * @param effectCaps           效应能力列表（未声明时为空列表）
     * @param effectCapsExplicit   效应能力是否显式声明
     * @param nameSpan             函数名的准确范围
     * @param span                 源码位置信息
     * @param retTypeInferred      返回类型是否由推断得到（`produce` 省略类型或整体省略）；
     *                             为 true 时 retType 只是占位，类型检查不得据此报返回类型不匹配，与 TS 对齐
     * @param typeParamsInferred   类型参数是否由解析器从签名中未声明的大写类型名推断（无显式 `of T`）；
     *                             名称解析据此区分隐式与显式类型参数（ADR 0051 裁决 R13）
     * @param resolved             函数名的解析结果（ADR 0051，FUNC_DECL；解析前为 null）
     */
    @JsonTypeName("Func")
    record Func(
        @JsonProperty("name") String name,
        @JsonProperty("nameSpan") Span nameSpan,
        @JsonProperty("typeParams") List<String> typeParams,
        @JsonProperty("params") List<Parameter> params,
        @JsonProperty("retType") Type retType,
        @JsonProperty("annotations") List<Annotation> annotations,
        @JsonProperty("retAnnotations") List<Annotation> retAnnotations,
        @JsonProperty("body") Block body,
        @JsonProperty("effects") List<String> effects,
        @JsonProperty("effectCaps") List<String> effectCaps,
        @JsonProperty("effectCapsExplicit") boolean effectCapsExplicit,
        @JsonProperty("span") Span span,
        @JsonProperty("retTypeInferred") boolean retTypeInferred,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JsonProperty("typeParamsInferred") boolean typeParamsInferred,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved
    ) implements Decl {
        /** 旧签名构造器（无 retTypeInferred，默认 false）：保持仓外 Java 调用方源码兼容。 */
        public Func(String name, Span nameSpan, List<String> typeParams, List<Parameter> params, Type retType,
                    List<Annotation> annotations, List<Annotation> retAnnotations, Block body, List<String> effects,
                    List<String> effectCaps, boolean effectCapsExplicit, Span span) {
            this(name, nameSpan, typeParams, params, retType, annotations, retAnnotations, body, effects,
                effectCaps, effectCapsExplicit, span, false);
        }

        /** 旧签名构造器（显式类型参数、未解析）：保持仓外 Java 调用方源码兼容。 */
        public Func(String name, Span nameSpan, List<String> typeParams, List<Parameter> params, Type retType,
                    List<Annotation> annotations, List<Annotation> retAnnotations, Block body, List<String> effects,
                    List<String> effectCaps, boolean effectCapsExplicit, Span span, boolean retTypeInferred) {
            this(name, nameSpan, typeParams, params, retType, annotations, retAnnotations, body, effects,
                effectCaps, effectCapsExplicit, span, retTypeInferred, false, null);
        }

        public Func {
            typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
            params = params == null ? List.of() : List.copyOf(params);
            effects = effects == null ? List.of() : List.copyOf(effects);
            effectCaps = effectCaps == null ? List.of() : List.copyOf(effectCaps);
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            retAnnotations = retAnnotations == null ? List.of() : List.copyOf(retAnnotations);
        }

        @Override
        public String kind() {
            return "Func";
        }
    }

    /**
     * Parameter 参数定义（用于函数和 Lambda）
     *
     * @param name        参数名称
     * @param type        参数类型
     * @param annotations 注解列表（可能为 null）
     * @param span        参数整体的源码范围
     * @param resolved    参数名的解析结果（ADR 0051，LOCAL；解析前为 null）
     * @param typeInferred 源码未标注类型：type 只是按参数名推断的占位，名称解析（ADR 0051 R2）视其类型未知；
     *                     不进 AST JSON
     */
    record Parameter(
        @JsonProperty("name") String name,
        @JsonProperty("type") Type type,
        @JsonProperty("annotations") List<Annotation> annotations,
        @JsonProperty("span") Span span,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved,
        @JsonIgnore boolean typeInferred
    ) {
        /** 未解析、带显式类型的参数。 */
        public Parameter(String name, Type type, List<Annotation> annotations, Span span) {
            this(name, type, annotations, span, null, false);
        }

        /** 带显式类型的参数。 */
        public Parameter(String name, Type type, List<Annotation> annotations, Span span, Resolved resolved) {
            this(name, type, annotations, span, resolved, false);
        }
    }
}
