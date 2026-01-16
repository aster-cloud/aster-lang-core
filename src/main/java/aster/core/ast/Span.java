package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 源码位置信息（行列范围）
 * <p>
 * 用于 AST 节点的源码追踪，记录起始和结束位置。
 *
 * @param start 起始位置
 * @param end   结束位置
 */
public record Span(
    @JsonProperty("start") Position start,
    @JsonProperty("end") Position end
) {
    /**
     * 位置点（行列坐标）
     *
     * @param line 行号（从 1 开始）
     * @param col  列号（从 1 开始）
     */
    public record Position(
        @JsonProperty("line") int line,
        @JsonProperty("col") int col
    ) {}
}
