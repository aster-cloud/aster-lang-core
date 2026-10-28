package aster.core.resolve;

import aster.core.identifier.VocabularyScopes;

/**
 * 名称解析（ADR 0051 R1/R2）的输入上下文：词汇作用域与导入模块的已解析声明。
 *
 * @param scopes  词汇作用域；为 null 表示无词汇，只按源码声明与原写法解析
 * @param imports 导入模块的已解析声明；须由调用方填充，否则引用导入类型的位置会报 E800
 */
public record ResolutionContext(VocabularyScopes scopes, ImportedDeclarations imports) {

    public ResolutionContext {
        imports = imports == null ? ImportedDeclarations.NONE : imports;
    }

    /** 无词汇、无导入。 */
    public static ResolutionContext none() {
        return new ResolutionContext(null, ImportedDeclarations.NONE);
    }
}
