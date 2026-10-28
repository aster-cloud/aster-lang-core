package aster.core.resolve;

import aster.core.typecheck.model.Diagnostic;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 名称解析存在 error 级诊断（E8xx）：调用方不得发出 IR。归入 {@link IllegalStateException}，
 * 使既有按“源码编译失败”处理的入口（如语义桥候选层）无需特判。
 */
public final class NameResolutionException extends IllegalStateException {

    private final transient List<Diagnostic> diagnostics;

    /** @param diagnostics 解析诊断；只保留 error 级 */
    public NameResolutionException(List<Diagnostic> diagnostics) {
        super(errorsOf(diagnostics).stream().map(NameResolutionException::format).collect(Collectors.joining("\n")));
        this.diagnostics = errorsOf(diagnostics);
    }

    private static List<Diagnostic> errorsOf(List<Diagnostic> diagnostics) {
        return diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
    }

    /** 只含 error 级诊断。 */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** {@code <code> <行>:<列> <消息>}，供命令行与日志输出。 */
    public static String format(Diagnostic d) {
        String at = d.span()
            .filter(o -> o.start != null)
            .map(o -> " " + o.start.line + ":" + o.start.col)
            .orElse("");
        return d.code().code() + at + " " + d.message();
    }
}
