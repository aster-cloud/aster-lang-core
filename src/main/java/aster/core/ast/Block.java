package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * Block 语句块节点
 * <p>
 * 表示一个代码块，包含多个语句的顺序执行。
 * Block 同时实现了 Stmt 和 Case.CaseBody 接口。
 *
 * @param statements 语句列表
 * @param span       源码位置信息
 */
@JsonTypeName("Block")
public record Block(
    @JsonProperty("statements") List<Stmt> statements,
    @JsonProperty("span") Span span
) implements Stmt, Stmt.Case.CaseBody {
    @Override
    public String kind() {
        return "Block";
    }
}
