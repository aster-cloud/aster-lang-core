package aster.core.resolve;

import aster.core.ast.Module;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.typecheck.governance.InputRefsAnalyzer;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 编译入口共用的名称解析流水线（ADR 0051 §4）：
 *
 * <pre>
 * 源码 ─canonicalize（关键词 / 别名 / 字面量宏 / 空白）─▶ 词法 ─▶ 语法 ─▶ AST
 *      ─▶ R1 DeclarationResolver ─▶ CoreLowering ─▶ R2 TypedReferenceResolver ─▶ inputRefs（仅档案模块）─▶ Core（只含规范名）
 * </pre>
 *
 * <p>R2 依赖 lower 在同一进程内登记的 ResolutionMark（不进 IR JSON），因此必须紧接 lower、作用于同一个 Core
 * 对象运行，不能跨序列化边界。任一 error 级诊断（E8xx）时调用方不得发出 IR。
 */
public final class NameResolution {

    private NameResolution() {}

    /**
     * @param ast         R1 输出的 AST（标识符节点带 resolved）
     * @param core        R2 输出的 Core：只含规范名，原写法记在 origin.written
     * @param diagnostics R1 与 R2 的诊断（E800–E807 / W800–W801），按产生顺序
     */
    public record ResolvedCore(Module ast, CoreModel.Module core, List<Diagnostic> diagnostics) {
        public ResolvedCore {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * R1 → lower → R2。返回全部诊断，不因 error 抛出（供需要展示诊断的入口使用）。
     *
     * @apiNote 内部 API（ADR 0051 裁决 R49）：有 E8xx 时返回值仍含 core，调用方必须自行以 {@link #hasErrors} 拒绝发出 IR。
     *     对外编译入口请使用 {@link #resolveAndLowerChecked}。
     */
    public static ResolvedCore resolveAndLower(Module ast, ResolutionContext ctx) {
        ResolutionResult<Module> r1 = DeclarationResolver.resolve(ast, ctx);
        ResolutionResult<CoreModel.Module> r2 = TypedReferenceResolver.resolve(new CoreLowering().lowerModule(r1.value()), ctx);
        List<Diagnostic> diagnostics = Stream.concat(r1.diagnostics().stream(), r2.diagnostics().stream()).toList();
        return new ResolvedCore(r1.value(), withInputRefs(r2.value()), diagnostics);
    }

    /**
     * ADR 0046 §11 D2：为声明了档案的模块中返回 Verdict 的规则写入 inputRefs。必须在 R2 之后计算——R2 把本地化
     * 名称规范化，之前计算会让 zh / de 源码产出本地化路径。未声明档案的模块原样返回（IR 逐字节不变）。
     */
    static CoreModel.Module withInputRefs(CoreModel.Module core) {
        if (core.profile == null || core.decls == null) return core;
        List<CoreModel.Decl> decls = new ArrayList<>(core.decls.size());
        for (CoreModel.Decl d : core.decls) {
            decls.add(d instanceof CoreModel.Func f && InputRefsAnalyzer.applies(core, f)
                ? CoreCopies.withInputRefs(f, InputRefsAnalyzer.analyze(core, f).inputRefs())
                : d);
        }
        return CoreCopies.module(core, decls);
    }

    /** 同 {@link #resolveAndLower}，但任一 E8xx 时抛出 {@link NameResolutionException}：产出 IR 的公开入口。 */
    public static ResolvedCore resolveAndLowerChecked(Module ast, ResolutionContext ctx) {
        ResolvedCore resolved = resolveAndLower(ast, ctx);
        if (hasErrors(resolved.diagnostics())) throw new NameResolutionException(resolved.diagnostics());
        return resolved;
    }

    public static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }
}
