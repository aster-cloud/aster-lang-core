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

  /** 只到 AstBuilder 阶段（不 lower）：本测聚焦访问期递归深度守卫。 */
  private void build(String src) {
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
  void moderateNestingParsesFine() {
    // 远低于上限（100 层）应正常构建，不抛。
    build(nestedParenSource(100));
  }

  @Test
  void deepNestingRejectedBeforeStackOverflow() {
    // 远超上限（1000 层）必须被深度守卫拦下（抛可恢复解析错误），而非 StackOverflowError。
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> build(nestedParenSource(1000)));
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
