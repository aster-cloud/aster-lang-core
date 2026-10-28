package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 类型节点（sealed interface）
 * <p>
 * 包含 TypeName（类型名）、TypeVar（类型变量）、TypeApp（类型应用）、
 * Result、Maybe、Option、List、Map、FuncType（函数类型）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Type.TypeName.class, name = "TypeName"),
    @JsonSubTypes.Type(value = Type.TypeVar.class, name = "TypeVar"),
    @JsonSubTypes.Type(value = Type.TypeApp.class, name = "TypeApp"),
    @JsonSubTypes.Type(value = Type.Result.class, name = "Result"),
    @JsonSubTypes.Type(value = Type.Maybe.class, name = "Maybe"),
    @JsonSubTypes.Type(value = Type.Option.class, name = "Option"),
    @JsonSubTypes.Type(value = Type.List.class, name = "List"),
    @JsonSubTypes.Type(value = Type.Map.class, name = "Map"),
    @JsonSubTypes.Type(value = Type.FuncType.class, name = "FuncType")
})
public sealed interface Type extends AstNode
    permits Type.TypeName, Type.TypeVar, Type.TypeApp, Type.Result, Type.Maybe, Type.Option,
            Type.List, Type.Map, Type.FuncType {

    /**
     * 类型级注解列表。
     *
     * @return 不可变注解列表
     */
    java.util.List<Annotation> annotations();

    /**
     * TypeName 类型名称（如 Int, String, User）
     *
     * @param name     类型名称（源码原写法）
     * @param span     源码位置信息
     * @param resolved 名称解析结果（ADR 0051，解析前或保留的类型参数为 null）
     */
    @JsonTypeName("TypeName")
    record TypeName(
        @JsonProperty("name") java.lang.String name,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved
    ) implements Type {
        public TypeName {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        }

        /** 未解析的类型名。 */
        public TypeName(java.lang.String name, java.util.List<Annotation> annotations, Span span) {
            this(name, annotations, span, null);
        }

        @Override
        public java.lang.String kind() {
            return "TypeName";
        }
    }

    /**
     * TypeVar 类型变量（泛型参数，如 T, U）
     *
     * @param name 类型变量名称
     * @param span 源码位置信息
     */
    @JsonTypeName("TypeVar")
    record TypeVar(
        @JsonProperty("name") java.lang.String name,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) implements Type {
        public TypeVar {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        }

        @Override
        public java.lang.String kind() {
            return "TypeVar";
        }
    }

    /**
     * TypeApp 类型应用（泛型实例化，如 List<Int>）
     *
     * @param base     基础类型名称（源码原写法）
     * @param args     类型参数列表
     * @param span     源码位置信息
     * @param resolved 基础类型名的解析结果（ADR 0051，解析前为 null）
     */
    @JsonTypeName("TypeApp")
    record TypeApp(
        @JsonProperty("base") java.lang.String base,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("args") java.util.List<Type> args,
        @JsonProperty("span") Span span,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resolved") Resolved resolved
    ) implements Type {
        public TypeApp {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
            args = args == null ? java.util.List.of() : java.util.List.copyOf(args);
        }

        /** 未解析的类型应用。 */
        public TypeApp(java.lang.String base, java.util.List<Annotation> annotations, java.util.List<Type> args, Span span) {
            this(base, annotations, args, span, null);
        }

        @Override
        public java.lang.String kind() {
            return "TypeApp";
        }
    }

    /**
     * Result Result 类型（Result<Ok, Err>）
     *
     * @param ok   成功类型
     * @param err  错误类型
     * @param span 源码位置信息
     */
    @JsonTypeName("Result")
    record Result(
        @JsonProperty("ok") Type ok,
        @JsonProperty("err") Type err,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) implements Type {
        public Result {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        }

        @Override
        public java.lang.String kind() {
            return "Result";
        }
    }

    /**
     * Maybe Maybe 类型（可能为空）
     *
     * @param type 包装的类型
     * @param span 源码位置信息
     */
    @JsonTypeName("Maybe")
    record Maybe(
        @JsonProperty("type") Type type,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) implements Type {
        public Maybe {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        }

        @Override
        public java.lang.String kind() {
            return "Maybe";
        }
    }

    /**
     * Option Option 类型（Some | None）
     *
     * @param type 包装的类型
     * @param span 源码位置信息
     */
    @JsonTypeName("Option")
    record Option(
        @JsonProperty("type") Type type,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) implements Type {
        public Option {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        }

        @Override
        public java.lang.String kind() {
            return "Option";
        }
    }

    /**
     * List List 类型（列表）
     *
     * @param type 元素类型
     * @param span 源码位置信息
     */
    @JsonTypeName("List")
    record List(
        @JsonProperty("type") Type type,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) implements Type {
        public List {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        }

        @Override
        public java.lang.String kind() {
            return "List";
        }
    }

    /**
     * Map Map 类型（映射/字典）
     *
     * @param key  键类型
     * @param val  值类型
     * @param span 源码位置信息
     */
    @JsonTypeName("Map")
    record Map(
        @JsonProperty("key") Type key,
        @JsonProperty("val") Type val,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) implements Type {
        public Map {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
        }

        @Override
        public java.lang.String kind() {
            return "Map";
        }
    }

    /**
     * FuncType 函数类型（(T1, T2) -> R）
     *
     * @param params 参数类型列表
     * @param ret    返回类型
     * @param span   源码位置信息
     */
    @JsonTypeName("FuncType")
    record FuncType(
        @JsonProperty("params") java.util.List<Type> params,
        @JsonProperty("ret") Type ret,
        @JsonProperty("annotations") java.util.List<Annotation> annotations,
        @JsonProperty("span") Span span
    ) implements Type {
        public FuncType {
            annotations = annotations == null ? java.util.List.of() : java.util.List.copyOf(annotations);
            params = params == null ? java.util.List.of() : java.util.List.copyOf(params);
        }

        @Override
        public java.lang.String kind() {
            return "FuncType";
        }
    }
}
