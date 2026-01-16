package aster.core.ast;

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
     * @param alias 别名（可能为 null）
     * @param span  源码位置信息
     */
    @JsonTypeName("Import")
    record Import(
        @JsonProperty("path") String path,
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
     * @param name   类型名称
     * @param fields 字段列表
     * @param span   源码位置信息
     */
    @JsonTypeName("Data")
    record Data(
        @JsonProperty("name") String name,
        @JsonProperty("fields") List<Field> fields,
        @JsonProperty("span") Span span
    ) implements Decl {
        @Override
        public String kind() {
            return "Data";
        }
    }

    /**
     * Field 字段定义（用于 Data 类型）
     *
     * @param name        字段名称
     * @param type        字段类型
     * @param annotations 注解列表（可能为 null）
     */
    record Field(
        @JsonProperty("name") String name,
        @JsonProperty("type") Type type,
        @JsonProperty("annotations") List<Annotation> annotations
    ) {}

    /**
     * Enum 枚举声明
     *
     * @param name     枚举名称
     * @param variants 变体列表
     * @param span     源码位置信息
     */
    @JsonTypeName("Enum")
    record Enum(
        @JsonProperty("name") String name,
        @JsonProperty("variants") List<String> variants,
        @JsonProperty("span") Span span
    ) implements Decl {
        @Override
        public String kind() {
            return "Enum";
        }
    }

    /**
     * TypeAlias 类型别名声明
     *
     * @param annotations 注解名称列表
     * @param name        别名名称
     * @param type        目标类型
     * @param span        源码位置信息
     */
    @JsonTypeName("TypeAlias")
    record TypeAlias(
        @JsonProperty("annotations") List<String> annotations,
        @JsonProperty("name") String name,
        @JsonProperty("type") Type type,
        @JsonProperty("span") Span span
    ) implements Decl {
        public TypeAlias {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
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
     * @param body                 函数体（可能为 null，表示外部函数）
     * @param effects              效应列表（无副作用时为空列表）
     * @param effectCaps           效应能力列表（未声明时为空列表）
     * @param effectCapsExplicit   效应能力是否显式声明
     * @param nameSpan             函数名的准确范围
     * @param span                 源码位置信息
     */
    @JsonTypeName("Func")
    record Func(
        @JsonProperty("name") String name,
        @JsonProperty("nameSpan") Span nameSpan,
        @JsonProperty("typeParams") List<String> typeParams,
        @JsonProperty("params") List<Parameter> params,
        @JsonProperty("retType") Type retType,
        @JsonProperty("retAnnotations") List<Annotation> retAnnotations,
        @JsonProperty("body") Block body,
        @JsonProperty("effects") List<String> effects,
        @JsonProperty("effectCaps") List<String> effectCaps,
        @JsonProperty("effectCapsExplicit") boolean effectCapsExplicit,
        @JsonProperty("span") Span span
    ) implements Decl {
        public Func {
            typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
            params = params == null ? List.of() : List.copyOf(params);
            effects = effects == null ? List.of() : List.copyOf(effects);
            effectCaps = effectCaps == null ? List.of() : List.copyOf(effectCaps);
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
     */
    record Parameter(
        @JsonProperty("name") String name,
        @JsonProperty("type") Type type,
        @JsonProperty("annotations") List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) {}
}
