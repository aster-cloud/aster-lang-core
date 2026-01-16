package aster.core.ast;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;

/**
 * CNL 模块节点
 * <p>
 * 表示一个完整的 Aster Lang 源文件或模块，包含顶层声明列表。
 *
 * @param name  模块名称（可能为 null）
 * @param decls 顶层声明列表（Import, Data, Enum, Func）
 * @param span  源码位置信息
 */
@JsonTypeName("Module")
public record Module(
    @JsonProperty("name") String name,
    @JsonProperty("decls") List<Decl> decls,
    @JsonProperty("span") Span span
) implements AstNode {
    @Override
    public String kind() {
        return "Module";
    }
}
