package aster.core.resolve;

import aster.core.typecheck.model.Diagnostic;

import java.util.List;

/**
 * 解析阶段的输出：新值（输入不变）与按产生顺序排列的诊断。
 *
 * @param value       解析后的值
 * @param diagnostics 诊断列表
 */
public record ResolutionResult<T>(T value, List<Diagnostic> diagnostics) {

    public ResolutionResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
