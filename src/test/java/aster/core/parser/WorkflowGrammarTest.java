package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.ast.Stmt;
import aster.core.canonicalizer.Canonicalizer;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

/**
 * workflow 语法：第一个 step 之后的元素（第二个 step、compensate、retry、timeout）须能解析（issue #218）。
 *
 * <p>根因：step 体 {@code block} 以 {@code NEWLINE* DEDENT} 收尾，已吞掉行尾换行与缩进回退，
 * 下一个元素前词法器不会再发 NEWLINE；而 {@code workflowBody}/{@code workflowStep} 曾要求元素之间至少一个
 * NEWLINE，导致除“纯单步”之外的 workflow 全部语法报错。TS 解析器（基准）接受这些写法。
 *
 * <p>本类所有用例都挂错误监听器并断言语法错误数为 0：ANTLR 默认做错误恢复而不抛异常，
 * 只看“不抛异常”会把语法错误静默吞掉。
 */
class WorkflowGrammarTest {

  private static final String HEADER = "Module demo.wf.\n\nRule r, produce. It performs io:\n";

  /** 解析结果：语法错误列表 + AST（有语法错误时 AST 仅供诊断参考）。 */
  private record Parsed(List<String> errors, AsterParser.ModuleContext ctx) {
    Module module() {
      return new AstBuilder().visitModule(ctx);
    }
  }

  private static Parsed parse(String src) {
    var lexer = new AsterCustomLexer(CharStreams.fromString(new Canonicalizer().canonicalize(src)));
    var tokens = new CommonTokenStream(lexer);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    List<String> errors = new ArrayList<>();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        errors.add(line + ":" + col + " " + msg);
      }
    });
    var ctx = parser.module();
    assertNotNull(ctx, "解析 null: " + src);
    return new Parsed(errors, ctx);
  }

  /** 解析成功（0 语法错误）并返回唯一函数体中的 workflow 语句。 */
  private static Stmt.Workflow workflowOf(String body) {
    String src = HEADER + body;
    Parsed p = parse(src);
    assertEquals(List.of(), p.errors(), "不应有语法错误:\n" + src);
    var func = assertInstanceOf(Decl.Func.class, p.module().decls().get(0));
    return assertInstanceOf(Stmt.Workflow.class, func.body().statements().get(0));
  }

  private static List<String> stepNames(Stmt.Workflow wf) {
    return wf.steps().stream().map(Stmt.WorkflowStep::name).toList();
  }

  @Test
  void singleStepStillParses() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n  .\n");
    assertEquals(List.of("a"), stepNames(wf));
    assertNull(wf.retry());
    assertNull(wf.timeout());
    assertNull(wf.steps().get(0).compensate());
  }

  @Test
  void twoStepsSeparatedByBlankLine() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n\n    step b:\n      Return ok of 2.\n  .\n");
    assertEquals(List.of("a", "b"), stepNames(wf));
  }

  @Test
  void twoStepsWithoutBlankLine() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n    step b:\n      Return ok of 2.\n  .\n");
    assertEquals(List.of("a", "b"), stepNames(wf));
  }

  @Test
  void blankLinesWithTrailingSpacesBetweenAndAfterSteps() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n    \n"
        + "    step b:\n      Return ok of 2.\n    \n  .\n");
    assertEquals(List.of("a", "b"), stepNames(wf));
  }

  @Test
  void threeStepsWithDependencies() {
    var wf = workflowOf("  workflow:\n"
        + "    step a:\n      Return ok of 1.\n"
        + "    step b depends on [\"a\"]:\n      Return ok of 2.\n"
        + "    step c depends on [\"a\", \"b\"]:\n      Return ok of 3.\n  .\n");
    assertEquals(List.of("a", "b", "c"), stepNames(wf));
    assertEquals(List.of(), wf.steps().get(0).dependencies());
    assertEquals(List.of("a"), wf.steps().get(1).dependencies());
    assertEquals(List.of("a", "b"), wf.steps().get(2).dependencies());
  }

  @Test
  void compensateAfterStepThenAnotherStep() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n\n"
        + "    compensate:\n      Return ok of 3.\n\n"
        + "    step b:\n      Return ok of 2.\n  .\n");
    assertEquals(List.of("a", "b"), stepNames(wf));
    assertNotNull(wf.steps().get(0).compensate(), "compensate 须归属前一个 step");
    assertEquals(1, wf.steps().get(0).compensate().statements().size());
    assertNull(wf.steps().get(1).compensate());
  }

  @Test
  void compensateOnLastStep() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n"
        + "    compensate:\n      Return ok of 3.\n  .\n");
    assertEquals(List.of("a"), stepNames(wf));
    assertNotNull(wf.steps().get(0).compensate());
  }

  @Test
  void retryAndTimeoutAfterSteps() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n\n"
        + "    step b:\n      Return ok of 2.\n\n"
        + "    retry:\n      max attempts: 3.\n      backoff: linear.\n\n"
        + "    timeout: 30 seconds.\n  .\n");
    assertEquals(List.of("a", "b"), stepNames(wf));
    assertEquals(new Stmt.RetryPolicy(3, "linear"), wf.retry());
    assertEquals(new Stmt.Timeout(30_000L), wf.timeout());
  }

  @Test
  void timeoutOnlyAfterSingleStep() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n    timeout: 5 seconds.\n  .\n");
    assertNull(wf.retry());
    assertEquals(new Stmt.Timeout(5_000L), wf.timeout());
  }

  @Test
  void retryOnlyAfterSingleStep() {
    var wf = workflowOf("  workflow:\n    step a:\n      Return ok of 1.\n"
        + "    retry:\n      max attempts: 2.\n      backoff: exponential.\n  .\n");
    assertEquals(new Stmt.RetryPolicy(2, "exponential"), wf.retry());
    assertNull(wf.timeout());
  }

  @Test
  void everythingTogether() {
    var wf = workflowOf("  workflow:\n"
        + "    step a:\n      Return ok of 1.\n"
        + "    compensate:\n      Return ok of 10.\n"
        + "    step b depends on [\"a\"]:\n      Return ok of 2.\n"
        + "    compensate:\n      Return ok of 20.\n"
        + "    retry:\n      max attempts: 3.\n      backoff: exponential.\n"
        + "    timeout: 30 seconds.\n  .\n");
    assertEquals(List.of("a", "b"), stepNames(wf));
    assertNotNull(wf.steps().get(0).compensate());
    assertNotNull(wf.steps().get(1).compensate());
    assertEquals(List.of("a"), wf.steps().get(1).dependencies());
    assertEquals(new Stmt.RetryPolicy(3, "exponential"), wf.retry());
    assertEquals(new Stmt.Timeout(30_000L), wf.timeout());
  }

  @Test
  void statementAfterWorkflowStillBelongsToFunction() {
    String src = HEADER + "  workflow:\n    step a:\n      Return ok of 1.\n    step b:\n      Return ok of 2.\n  .\n"
        + "  Return ok of 9.\n";
    Parsed p = parse(src);
    assertEquals(List.of(), p.errors());
    var func = assertInstanceOf(Decl.Func.class, p.module().decls().get(0));
    assertEquals(2, func.body().statements().size(), "workflow 之后的语句不得丢失");
    assertInstanceOf(Stmt.Return.class, func.body().statements().get(1));
  }

  @Test
  void missingColonAfterStepNameIsSyntaxError() {
    Parsed p = parse(HEADER + "  workflow:\n    step a:\n      Return ok of 1.\n    step b\n      Return ok of 2.\n  .\n");
    assertTrue(!p.errors().isEmpty(), "step 名后缺冒号必须报语法错误");
  }

  @Test
  void retryBeforeStepIsSyntaxError() {
    // retry/timeout 只能出现在全部 step 之后，放宽换行不能放宽元素顺序。
    Parsed p = parse(HEADER + "  workflow:\n    retry:\n      max attempts: 3.\n      backoff: linear.\n"
        + "    step a:\n      Return ok of 1.\n  .\n");
    assertTrue(!p.errors().isEmpty(), "retry 在 step 之前必须报语法错误");
  }

  @Test
  void retryWithoutBackoffIsRejected() {
    Parsed p = parse(HEADER + "  workflow:\n    step a:\n      Return ok of 1.\n    step b:\n      Return ok of 2.\n"
        + "    retry:\n      max attempts: 3.\n  .\n");
    assertEquals(List.of(), p.errors(), "缺 backoff 是语义错误而非语法错误");
    var e = assertThrows(IllegalStateException.class, p::module);
    assertTrue(e.getMessage().contains("backoff"), e.getMessage());
  }
}
