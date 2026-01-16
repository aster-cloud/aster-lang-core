package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * 表达式节点（sealed interface）
 * <p>
 * 包含 Name、字面量（Bool, Int, Long, Double, String, Null）、
 * 函数调用（Call）、构造（Construct）、Result 包装（Ok, Err）、
 * Option 包装（Some, None）、Lambda 和 Await。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Expr.Name.class, name = "Name"),
    @JsonSubTypes.Type(value = Expr.Bool.class, name = "Bool"),
    @JsonSubTypes.Type(value = Expr.Int.class, name = "Int"),
    @JsonSubTypes.Type(value = Expr.Long.class, name = "Long"),
    @JsonSubTypes.Type(value = Expr.Double.class, name = "Double"),
    @JsonSubTypes.Type(value = Expr.String.class, name = "String"),
    @JsonSubTypes.Type(value = Expr.Null.class, name = "Null"),
    @JsonSubTypes.Type(value = Expr.Call.class, name = "Call"),
    @JsonSubTypes.Type(value = Expr.Construct.class, name = "Construct"),
    @JsonSubTypes.Type(value = Expr.Ok.class, name = "Ok"),
    @JsonSubTypes.Type(value = Expr.Err.class, name = "Err"),
    @JsonSubTypes.Type(value = Expr.Some.class, name = "Some"),
    @JsonSubTypes.Type(value = Expr.None.class, name = "None"),
    @JsonSubTypes.Type(value = Expr.ListLiteral.class, name = "ListLiteral"),
    @JsonSubTypes.Type(value = Expr.Lambda.class, name = "Lambda"),
    @JsonSubTypes.Type(value = Expr.Await.class, name = "Await")
})
public sealed interface Expr extends AstNode
    permits Expr.Name, Expr.Bool, Expr.Int, Expr.Long, Expr.Double, Expr.String, Expr.Null,
            Expr.Call, Expr.Construct, Expr.Ok, Expr.Err, Expr.Some, Expr.None, Expr.ListLiteral,
            Expr.Lambda, Expr.Await {

    /**
     * Name 名称表达式（变量引用或函数名）
     *
     * @param name 名称
     * @param span 源码位置信息
     */
    @JsonTypeName("Name")
    record Name(
        @JsonProperty("name") java.lang.String name,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Name";
        }
    }

    /**
     * Bool 布尔字面量
     *
     * @param value 布尔值
     * @param span  源码位置信息
     */
    @JsonTypeName("Bool")
    record Bool(
        @JsonProperty("value") boolean value,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Bool";
        }
    }

    /**
     * Int 整数字面量
     *
     * @param value 整数值
     * @param span  源码位置信息
     */
    @JsonTypeName("Int")
    record Int(
        @JsonProperty("value") int value,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Int";
        }
    }

    /**
     * Long 长整数字面量
     *
     * @param value 长整数值
     * @param span  源码位置信息
     */
    @JsonTypeName("Long")
    record Long(
        @JsonProperty("value") long value,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Long";
        }
    }

    /**
     * Double 浮点数字面量
     *
     * @param value 浮点数值
     * @param span  源码位置信息
     */
    @JsonTypeName("Double")
    record Double(
        @JsonProperty("value") double value,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Double";
        }
    }

    /**
     * String 字符串字面量
     *
     * @param value 字符串值
     * @param span  源码位置信息
     */
    @JsonTypeName("String")
    record String(
        @JsonProperty("value") java.lang.String value,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "String";
        }
    }

    /**
     * Null 空值字面量
     *
     * @param span 源码位置信息
     */
    @JsonTypeName("Null")
    record Null(
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Null";
        }
    }

    /**
     * Call 函数调用表达式
     *
     * @param target 目标表达式（函数名或 Lambda）
     * @param args   参数列表
     * @param span   源码位置信息
     */
    @JsonTypeName("Call")
    record Call(
        @JsonProperty("target") Expr target,
        @JsonProperty("args") List<Expr> args,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Call";
        }
    }

    @JsonTypeName("ListLiteral")
    record ListLiteral(
        @JsonProperty("items") List<Expr> items,
        @JsonProperty("span") Span span
    ) implements Expr {
        public ListLiteral {
            items = items == null ? List.of() : List.copyOf(items);
        }

        @Override
        public java.lang.String kind() {
            return "ListLiteral";
        }
    }

    /**
     * Construct 构造表达式（创建 Data 实例）
     *
     * @param typeName 类型名称
     * @param fields   字段初始化列表
     * @param span     源码位置信息
     */
    @JsonTypeName("Construct")
    record Construct(
        @JsonProperty("typeName") java.lang.String typeName,
        @JsonProperty("fields") List<ConstructField> fields,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Construct";
        }

        /**
         * ConstructField 构造字段（用于 Construct 表达式）
         *
         * @param name 字段名称
         * @param expr 字段值表达式
         */
        public record ConstructField(
            @JsonProperty("name") java.lang.String name,
            @JsonProperty("expr") Expr expr
        ) {}
    }

    /**
     * Ok Result 成功包装（Result<T, E> 的 Ok 变体）
     *
     * @param expr 包装的表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Ok")
    record Ok(
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Ok";
        }
    }

    /**
     * Err Result 错误包装（Result<T, E> 的 Err 变体）
     *
     * @param expr 包装的表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Err")
    record Err(
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Err";
        }
    }

    /**
     * Some Option 有值包装（Option<T> 的 Some 变体）
     *
     * @param expr 包装的表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Some")
    record Some(
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Some";
        }
    }

    /**
     * None Option 空值包装（Option<T> 的 None 变体）
     *
     * @param span 源码位置信息
     */
    @JsonTypeName("None")
    record None(
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "None";
        }
    }

    /**
     * Lambda Lambda 表达式（匿名函数）
     *
     * @param params  参数列表
     * @param retType 返回类型
     * @param body    函数体块
     * @param span    源码位置信息
     */
    @JsonTypeName("Lambda")
    record Lambda(
        @JsonProperty("params") List<Decl.Parameter> params,
        @JsonProperty("retType") Type retType,
        @JsonProperty("body") Block body,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Lambda";
        }
    }

    /**
     * Await 等待表达式（等待异步任务结果）
     *
     * @param expr 要等待的表达式
     * @param span 源码位置信息
     */
    @JsonTypeName("Await")
    record Await(
        @JsonProperty("expr") Expr expr,
        @JsonProperty("span") Span span
    ) implements Expr {
        @Override
        public java.lang.String kind() {
            return "Await";
        }
    }
}
