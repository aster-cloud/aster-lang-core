package aster.core.parser;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.Lexicon;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;

/**
 * 解析并收集结构化解析诊断（ADR 0051 §16 R56）。
 *
 * <p>流程：按 lexicon 规范化 → 词法 / 语法分析（ANTLR 事件经 {@link SyntaxErrorCollector} 分类）→
 * 无语法错误时构建 AST（{@link AstBuilder} 的 {@link ParseDiagnosticException} 转为诊断）。
 * 只做诊断收集，不改变既有解析入口的行为与产物。
 */
public final class ParseDiagnostics {

    /** 解析结果：成功时 {@code module} 非空、{@code diagnostics} 为空。 */
    public record Result(Module module, List<ParseDiagnostic> diagnostics) {}

    private ParseDiagnostics() {
    }

    public static Result parse(String source, Lexicon lexicon) {
        String canonical = new Canonicalizer(lexicon, null).canonicalize(source);
        SyntaxErrorCollector collector = new SyntaxErrorCollector();
        try {
            AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
            lexer.removeErrorListeners();
            lexer.addErrorListener(collector);
            AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(collector);
            AsterParser.ModuleContext tree = parser.module();
            if (collector.hasErrors()) {
                return new Result(null, collector.diagnostics());
            }
            return new Result(new AstBuilder().visitModule(tree), List.of());
        } catch (ParseDiagnosticException e) {
            List<ParseDiagnostic> out = new java.util.ArrayList<>(collector.diagnostics());
            out.add(e.toDiagnostic());
            return new Result(null, List.copyOf(out));
        }
    }
}
