package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 名称解析结果（ADR 0051）：{@code written} 为源码原写法，{@code canonical} 为规范名。
 * 节点自身的 name 字段保持原写法不变，下游以 canonical 产出 Core；解析前节点的 resolved 为 null。
 *
 * @param canonical 规范名
 * @param role      解析角色
 * @param written   源码原写法
 */
public record Resolved(
    @JsonProperty("canonical") String canonical,
    @JsonProperty("role") ResolvedRole role,
    @JsonProperty("written") String written
) {}
