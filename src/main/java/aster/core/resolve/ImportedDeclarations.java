package aster.core.resolve;

import java.util.List;
import java.util.Map;

/**
 * 导入模块中已解析的声明，全部为规范名。按 ADR 0051 §6.2 第 2 步与本模块声明同层参与匹配，
 * 因此优先于词汇。
 *
 * @param types       Data / Enum / 别名规范名
 * @param functions   函数规范名
 * @param enumMembers 枚举规范名 → 成员规范名
 * @param fields      Data 规范名 → 字段规范名
 */
public record ImportedDeclarations(
    List<String> types,
    List<String> functions,
    Map<String, List<String>> enumMembers,
    Map<String, List<String>> fields
) {
    /** 无导入。 */
    public static final ImportedDeclarations NONE = new ImportedDeclarations(List.of(), List.of(), Map.of(), Map.of());

    public ImportedDeclarations {
        types = types == null ? List.of() : List.copyOf(types);
        functions = functions == null ? List.of() : List.copyOf(functions);
        enumMembers = enumMembers == null ? Map.of() : Map.copyOf(enumMembers);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
