package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.ast.Stmt;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

/**
 * Set 语句（ADR 0052，aster-cloud/aster-lang-truffle#151）在生产管线上的行为：
 * 语法能解析 `Set x to <expr>.`；只有 Let 绑定可被赋值（可跨 case 体 / If 分支写回外层），
 * 形参、lambda 形参、模式绑定报 E001，未定义名字报 E101，类型不符报 E002——与 TS 类型检查逐码一致。
 */
class SetStatementPipelineTest {

  private static Module parse(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        throw new IllegalStateException("line " + line + ":" + col + " " + msg);
      }
    });
    return new AstBuilder().visitModule(parser.module());
  }

  private static List<Diagnostic> typecheck(String... lines) {
    String src = "Module probe.\n\n" + String.join("\n", lines) + "\n";
    CoreModel.Module core = new CoreLowering().lowerModule(assertDoesNotThrow(() -> parse(src), src));
    return new TypeChecker().typecheckModule(core).stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
  }

  private static List<String> codesAndLines(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
      .map(d -> d.code().code() + "@" + d.span().map(o -> o.start.line).orElse(-1))
      .toList();
  }

  @Test
  void parsesSetStatementIntoAstAndCore() {
    Module ast = parse("Module probe.\n\nRule r given n as Int, produce Int:\n  Let total be 0.\n  Set total to total plus n.\n  Return total.\n");
    var func = assertInstanceOf(Decl.Func.class, ast.decls().get(0));
    var set = assertInstanceOf(Stmt.Set.class, func.body().statements().get(1));
    assertEquals("total", set.name());
    assertEquals(5, set.span().start().line());

    var core = new CoreLowering().lowerModule(ast);
    var coreFunc = assertInstanceOf(CoreModel.Func.class, core.decls.get(0));
    var coreSet = assertInstanceOf(CoreModel.Set.class, coreFunc.body.statements.get(1));
    assertEquals("total", coreSet.name);
  }

  @Test
  void setOuterLetInsideCaseBodyIsClean() {
    assertEquals(List.of(), codesAndLines(typecheck(
      "Rule r given n as Int, produce Int:",
      "  Let total be 0.",
      "  Match n:",
      "    When 1,",
      "      Set total to 10.",
      "    When other,",
      "      Set total to total plus other.",
      "  Return total.")));
  }

  @Test
  void setOuterLetInsideIfBranchesIsClean() {
    assertEquals(List.of(), codesAndLines(typecheck(
      "Rule r given n as Int, produce Int:",
      "  Let sign be 0.",
      "  If n greater than 0",
      "    Set sign to 1.",
      "  Otherwise",
      "    Set sign to 2.",
      "  Return sign.")));
  }

  @Test
  void setParameterReportsE001() {
    var diagnostics = typecheck(
      "Rule r given n as Int, produce Int:",
      "  Set n to 1.",
      "  Return n.");
    assertEquals(List.of("E001@4"), codesAndLines(diagnostics));
    assertTrue(diagnostics.get(0).message().contains("parameter 'n'"), diagnostics.get(0).message());
  }

  @Test
  void setParameterInsideCaseBodyReportsE001() {
    assertEquals(List.of("E001@6"), codesAndLines(typecheck(
      "Rule r given n as Int, produce Int:",
      "  Match n:",
      "    When 1,",
      "      Set n to 2.",
      "  Return n.")));
  }

  @Test
  void setPatternBindingReportsE001() {
    var diagnostics = typecheck(
      "Rule r given n as Int, produce Int:",
      "  Let total be 0.",
      "  Match n:",
      "    When other,",
      "      Set other to 1.",
      "      Set total to other.",
      "  Return total.");
    assertEquals(List.of("E001@7"), codesAndLines(diagnostics));
    assertTrue(diagnostics.get(0).message().contains("pattern binding 'other'"), diagnostics.get(0).message());
  }

  @Test
  void setLambdaParameterReportsE001() {
    assertEquals(List.of("E001@5"), codesAndLines(typecheck(
      "Rule r given n as Int, produce Int:",
      "  Let f be function with k as Int, produce Int:",
      "    Set k to 1.",
      "    Return k.",
      "  Return f(n).")));
  }

  @Test
  void setUndefinedNameReportsE101() {
    assertEquals(List.of("E101@4"), codesAndLines(typecheck(
      "Rule r given n as Int, produce Int:",
      "  Set missing to 1.",
      "  Return n.")));
  }

  @Test
  void setWithMismatchedTypeReportsE002() {
    var codes = codesAndLines(typecheck(
      "Rule r given n as Int, produce Int:",
      "  Let total be 0.",
      "  Set total to \"x\".",
      "  Return total."));
    assertTrue(codes.contains("E002@5"), codes.toString());
  }
}
