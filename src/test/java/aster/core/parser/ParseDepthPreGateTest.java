package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 深嵌套必须在**词法期**被拒，而不是在 ANTLR 解析期栈溢出（issue #140）。
 *
 * <p>★真实缺陷：{@code AstBuilder.MAX_EXPR_DEPTH} 的守卫位于 **AstBuilder 访问期**
 * （{@code visitExpr}），而 ANTLR 生成解析器自身的解析期递归发生得更早。嵌套足够深时
 * {@code parser.module()} 阶段就先抛 {@code StackOverflowError}——那是 {@code Error} 级，
 * <b>不是</b>注释所称的「可恢复的解析错误」，不会被转成 Diagnostic。
 *
 * <p>实测（修复前，默认栈）：
 * <pre>
 *   depth=500   srcBytes=1043  → IllegalStateException（AstBuilder 守卫生效）
 *   depth=1000  srcBytes=2043  → IllegalStateException（生效）
 *   depth=2000  srcBytes=4043  → StackOverflowError（守卫被绕过）
 * </pre>
 * <b>只需约 4KB 源码</b>即可让守卫失效并抛出 Error。
 *
 * <p>而 {@code AstBuilder} 注释里声称的兜底「64KB 源长度上限（SourcePolicyRequest
 * @Size / CnlSourceLimits）」——这两个类型<b>在本仓不存在</b>，全仓 grep 只命中注释本身。
 * 那个上限由外部 aster-api 服务层实施，直接使用本库的调用方不受任何限制。
 *
 * <p>★本测试<b>刻意不用大栈线程</b>。既有的 {@code ExprDepthLimitTest} 在 16MB 大栈
 * 线程上跑，注释里明说那是「保证 ANTLR 解析期不先 StackOverflow」——即它靠<b>放大栈</b>
 * 绕开了本缺陷，而生产调用方没有这个大栈。故这里用默认栈，才测得到真实行为。
 */
class ParseDepthPreGateTest {

  /** 默认栈上完整走一遍 Canonicalizer → Lexer → ANTLR → AstBuilder。 */
  private void build(String src) {
    var lexer = new AsterCustomLexer(CharStreams.fromString(new Canonicalizer().canonicalize(src)));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var moduleCtx = parser.module();
    assertNotNull(moduleCtx, "解析 null");
    new AstBuilder().visitModule(moduleCtx);
  }

  private String nestedParens(int depth) {
    return "Module probe.\n\nRule r given x:\n  Return "
        + "(".repeat(depth) + "1" + ")".repeat(depth) + ".\n";
  }

  @Test
  void deepNestingIsRejectedAsRecoverableError_notStackOverflow() {
    // ★核心回归：depth=2000（约 4KB 源码）修复前抛 StackOverflowError。
    var ex = assertThrows(IllegalStateException.class, () -> build(nestedParens(2000)),
        "深嵌套必须抛可恢复的解析错误，而不是 Error 级的 StackOverflowError");
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("嵌套过深"),
        "实际: " + ex.getMessage());
  }

  @Test
  void veryDeepNestingAlsoRejected_gateIsNotDepthDependent() {
    // ★守卫本身不能随深度增加而失效——depth=5000 时同样必须是可恢复错误。
    //   若守卫仍在解析期之后，更深的输入只会更早栈溢出。
    assertThrows(IllegalStateException.class, () -> build(nestedParens(5000)));
  }

  @Test
  void bracketNestingAlsoGated() {
    // 列表字面量的方括号走同一条递归路径，必须一并守。
    String src = "Module probe.\n\nRule r given x:\n  Return "
        + "[".repeat(2000) + "1" + "]".repeat(2000) + ".\n";
    assertThrows(IllegalStateException.class, () -> build(src));
  }

  @Test
  void shallowNestingStillParses() {
    // ★反向护栏：没有这条，把守卫写成「一律拒绝」也能让上面三条变绿。
    assertDoesNotThrow(() -> build(nestedParens(100)),
        "100 层嵌套远低于上限，必须正常解析");
  }

  @Test
  void ordinaryProgramUnaffected() {
    // ★反向护栏之二：括号计数是**配对**的，正常程序里大量括号不得累积成误报。
    var sb = new StringBuilder("Module probe.\n\nRule r given x:\n");
    for (int i = 0; i < 500; i++) {
      sb.append("  Let v").append(i).append(" be double(x).\n");
    }
    sb.append("  Return x.\n");
    assertDoesNotThrow(() -> build(sb.toString()),
        "500 对**已闭合**的括号不得被计成 500 层深度");
  }
}
