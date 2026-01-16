package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * CNL AST 节点根接口
 * <p>
 * 所有 AST 节点的共同标记接口，用于类型安全和多态序列化支持。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
public sealed interface AstNode permits Decl, Stmt, Expr, Pattern, Type, Module, Stmt.WorkflowStep {
    /**
     * 获取节点的 kind 标识符
     * @return 节点类型的字符串标识
     */
    String kind();

    /**
     * 获取节点的源码位置信息
     * @return Span 对象，如果不可用则返回 null
     */
    Span span();
}
