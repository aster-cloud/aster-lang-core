package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import aster.core.ast.Expr;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * workflow 语法关键词在标识符位置须当软关键字（issue #136）。
 *
 * <p>★真实缺陷：{@code Return config.timeout.} 的 token 流是
 * <pre>
 *   RETURN IDENT(config) DOT TIMEOUT(timeout) DOT
 * </pre>
 * ——{@code timeout} 被词法成 {@code TIMEOUT} 而非 {@code IDENT}，而
 * {@code MemberSuffix} 只收 {@code IDENT | TYPE_IDENT | structKeywordName}，
 * 于是 {@code .timeout} 匹配不上：解析停在 {@code config}，
 * 尾部 token 落到块外被<b>静默丢弃</b>，既不报错也不产生诊断。
 *
 * <p>这与 {@code MAX}/{@code ATTEMPTS} 当年因 {@code List.max(xs)} 被卡而加入
 * {@code structKeywordName} 是**同一类问题**（字段名撞语法关键词），故用同一套修法。
 */
class WorkflowKeywordAsFieldNameTest {

  /**
   * 解析并构建 AST，断言 0 语法错误。ANTLR 默认错误恢复、不抛异常，
   * 必须挂监听器收集错误，否则语法错误会被静默吞掉（#218 的假绿即由此而来）。
   */
  private Module build(String src) {
    var lexer = new AsterCustomLexer(CharStreams.fromString(new Canonicalizer().canonicalize(src)));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
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
    assertEquals(List.of(), errors, "不应有语法错误: " + src);
    return new AstBuilder().visitModule(ctx);
  }

  private String returnedName(Module m) {
    var func = (aster.core.ast.Decl.Func) m.decls().get(0);
    var ret = assertInstanceOf(Stmt.Return.class, func.body().statements().get(0));
    return assertInstanceOf(Expr.Name.class, ret.expr()).name();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "timeout", "step", "retry", "workflow", "depends", "compensate", "backoff", "seconds"
  })
  void workflowKeywordUsableAsMemberName(String kw) {
    // ★核心回归：修复前这些成员名会被静默丢弃，Name 只剩 "config"。
    var m = build("Module t.\n\nRule r given config:\n  Return config." + kw + ".\n");
    assertEquals("config." + kw, returnedName(m),
        "`." + kw + "` 必须被合成限定名，不得静默丢弃");
  }

  @Test
  void ordinaryFieldNameStillWorks() {
    // ★反向护栏之一：普通字段名不得被本次改动波及。
    var m = build("Module t.\n\nRule r given config:\n  Return config.foo.\n");
    assertEquals("config.foo", returnedName(m));
  }

  @Test
  void workflowSyntaxStillParses() {
    // ★反向护栏之二（最关键）：把这些 token 加进软关键字集后，
    //   **workflow 语法本身必须照常解析**——各自的语法起点仍按对应 token 分派。
    //   没有这条，「让 .timeout 能用」的代价可能是「timeout: 30 seconds. 不能用了」。
    //   函数头冒号后紧跟空行是另一个与 workflow 无关的既有语法缺口（Java 报 extraneous NEWLINE，TS 接受），
    //   不在本用例范围，故样本从冒号下一行直接开始；step 之间、timeout 之后的空行保留。
    String src = "Module demo.workflow.linear.\n\n"
        + "Rule testWorkflow, produce. It performs io:\n"
        + "  workflow:\n"
        + "    step validate:\n      return ok of \"validated\".\n    \n"
        + "    step execute:\n      return ok of \"executed\".\n    \n"
        + "    timeout: 30 seconds.\n  \n  .\n";
    var func = (aster.core.ast.Decl.Func) build(src).decls().get(0);
    var wf = assertInstanceOf(Stmt.Workflow.class, func.body().statements().get(0),
        "workflow 语法不得因软关键字改动而失效");
    assertEquals(List.of("validate", "execute"), wf.steps().stream().map(Stmt.WorkflowStep::name).toList());
    assertEquals(new Stmt.Timeout(30_000L), wf.timeout());
  }
}
