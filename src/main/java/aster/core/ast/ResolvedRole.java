package aster.core.ast;

/**
 * 名称解析角色（ADR 0051 §5）。R1/R2 写入 AST 标识符节点的 {@code resolved.role}，
 * 表示该写法在当前位置按哪种角色解析；取值与 TS ResolvedRole 一致。{@link #VALUE_REF}（§5 值位置单段名）
 * 只出现在未解析诊断中：解析成功时记录命中层的具体角色。
 */
public enum ResolvedRole {
    TYPE_DECL,
    FIELD_DECL,
    MEMBER_DECL,
    FUNC_DECL,
    TYPE,
    FIELD_REF,
    MEMBER_REF,
    FUNC_REF,
    VALUE_REF,
    LOCAL
}
