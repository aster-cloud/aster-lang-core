package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 生产管线（Canonicalizer → Lexer → Parser → AstBuilder → CoreLowering → TypeChecker）
 * 上的重名回归（issue #194）。
 *
 * <p>IR 级用例（{@link TypeCheckerEdgeCaseTest}）只证明检查器本身不抛；这里证明
 * 用户实际写出的源文本走完整管线后拿到的是 E104 诊断，而不是 CLI 里的一段 Java 栈。
 */
class DuplicateSymbolPipelineTest {

  private static List<Diagnostic> typecheck(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    var ast = new AstBuilder().visitModule(parser.module());
    CoreModel.Module core = new CoreLowering().lowerModule(ast);
    return assertDoesNotThrow(() -> new TypeChecker().typecheckModule(core),
      "重名属普通用户输入，typecheckModule 必须返回诊断而不是抛异常：\n" + src);
  }

  private static long countDuplicate(List<Diagnostic> diagnostics) {
    return diagnostics.stream().filter(d -> d.code() == ErrorCode.DUPLICATE_SYMBOL).count();
  }

  private static List<String> codesOf(List<Diagnostic> diagnostics) {
    return diagnostics.stream().map(d -> d.code().name()).toList();
  }

  @Test
  void duplicateLetReportsE104() {
    var diagnostics = typecheck(
      "Module probe.\n\n"
        + "Rule main produce Int:\n"
        + "  Let x be 1.\n"
        + "  Let x be 2.\n"
        + "  Return x.\n");
    assertEquals(List.of("DUPLICATE_SYMBOL"), codesOf(diagnostics),
      "同名 Let 应恰好报一条 E104，且首个 x 仍可见（无级联 UNDEFINED_VARIABLE）");
  }

  @Test
  void paramThenLetReportsE104() {
    var diagnostics = typecheck(
      "Module probe.\n\n"
        + "Rule main given x as Int, produce Int:\n"
        + "  Let x be 2.\n"
        + "  Return x.\n");
    assertEquals(List.of("DUPLICATE_SYMBOL"), codesOf(diagnostics),
      "形参 x 后再 Let x 应恰好报一条 E104");
  }

  @Test
  void duplicateLambdaParamsReportsE104AndScopeIsRestored() {
    var diagnostics = typecheck(
      "Module probe.\n\n"
        + "Rule main produce Int:\n"
        + "  Let f be function with y, y, produce:\n"
        + "    Return y.\n"
        + "  Let y be 1.\n"
        + "  Return y.\n");
    assertEquals(1, countDuplicate(diagnostics),
      "Lambda 形参 y, y 应恰好报一条 E104；外层 `Let y` 在函数作用域，不得因作用域栈失衡被误判重名。实际："
        + codesOf(diagnostics));
    assertTrue(diagnostics.stream().noneMatch(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE),
      "Lambda 作用域退出后外层 y 必须可见；实际：" + codesOf(diagnostics));
  }

  @Test
  void controlWithoutDuplicateIsClean() {
    var diagnostics = typecheck(
      "Module probe.\n\n"
        + "Rule main produce Int:\n"
        + "  Let x be 1.\n"
        + "  Let y be 2.\n"
        + "  Return x.\n");
    assertEquals(List.of(), codesOf(diagnostics), "对照组不应有任何诊断");
  }
}
