package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 红队 P2-H：表达式嵌套深度上限（防深嵌套 CNL 递归 StackOverflow DoS）。
 * AstBuilder.visitExpr 计数，超 MAX_EXPR_DEPTH(300) 即抛可恢复解析错误。
 * 与 aster-lang-ts MAX_RECURSION_DEPTH=300 对齐。
 */
class ExprDepthLimitTest {

  /**
   * 只到 AstBuilder 阶段（不 lower）：本测聚焦**访问期**递归深度守卫。
   *
   * <p>在**大栈专用线程**（16MB）上跑，让 ANTLR 生成解析器的解析期递归在深嵌套下不先
   * StackOverflow —— 从而稳定隔离测 AstBuilder.visitExpr 的深度守卫（否则解析期栈溢出
   * 与平台默认栈大小耦合：历史用 1000 层在 CI 小栈下于 BufferedTokenStream 先炸，
   * 见 red-team P2-H 回归）。生产解析期递归的 DoS 由 64KB 源长度上限兜底。
   */
  private void build(String src) throws Throwable {
    final Throwable[] err = new Throwable[1];
    Thread t = new Thread(null, () -> {
      try {
        String canonical = new Canonicalizer().canonicalize(src);
        var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();
        tokens.seek(0);
        var parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        var moduleCtx = parser.module();
        assertNotNull(moduleCtx, "解析 null: " + src);
        new AstBuilder().visitModule(moduleCtx);
      } catch (Throwable e) {
        err[0] = e;
      }
    }, "depth-probe", 16L * 1024 * 1024);
    t.start();
    t.join();
    if (err[0] != null) throw err[0];
  }

  /** n 层括号包裹字面量 1：Return ((( ... 1 ... ))). */
  private String nestedParenSource(int depth) {
    StringBuilder sb = new StringBuilder();
    sb.append("Module probe.\nRule main given seed as Int, produce Int:\n  Return ");
    sb.append("(".repeat(depth));
    sb.append("1");
    sb.append(")".repeat(depth));
    sb.append(".\n");
    return sb.toString();
  }

  @Test
  void moderateNestingParsesFine() throws Throwable {
    // 远低于上限（100 层）应正常构建，不抛。
    build(nestedParenSource(100));
  }

  @Test
  void deepNestingRejectedByDepthGuard() {
    // 超上限（400 层 > MAX_EXPR_DEPTH=300）必须被 AstBuilder 深度守卫拦下（抛可恢复
    // IllegalStateException），而非 StackOverflowError。build() 在 16MB 大栈线程上跑，
    // 保证 ANTLR 解析期不先 StackOverflow → 稳定隔离测访问期守卫（见 build() 注释）。
    // 生产解析期递归的 DoS 由 64KB 源长度上限兜底（SourcePolicyRequest @Size）。
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> build(nestedParenSource(400)));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("嵌套过深"),
        "应报表达式嵌套过深，实际: " + ex.getMessage());
  }

  @Test
  void limitConstantMatchesTsEngine() {
    // 与 aster-lang-ts MAX_RECURSION_DEPTH=300 对齐（双引擎行为一致）。
    org.junit.jupiter.api.Assertions.assertEquals(300, AstBuilder.MAX_EXPR_DEPTH,
        "Java 表达式深度上限应与 TS MAX_RECURSION_DEPTH=300 对齐");
  }
}
