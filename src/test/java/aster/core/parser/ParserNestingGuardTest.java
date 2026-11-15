package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.lexicon.LexiconRegistry;
import aster.core.typecheck.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 解析器层嵌套守卫（aster-lang-core#255）：词法期守卫按 token 序列估深度，语法错误里夹杂的 token 会把它的计数清零，
 * 解析器的错误恢复却越过这些 token 继续往下递归。修复前，下列输入在 1MB 栈（生产 / CI 的默认线程栈）上
 * {@code StackOverflowError}——Error 级，穿透调用方的 {@code catch (Exception)}：
 * <ul>
 *   <li>{@code not + not + … c}：每个 {@code +} 都打断词法期的连续 not 计数；</li>
 *   <li>{@code not , not , … c}：同上，分隔符换成逗号；</li>
 *   <li>{@code If c then 1 otherwise If …}：内联 if 不含括号，词法期没有计数，AstBuilder 的表达式深度守卫在解析之后才跑。</li>
 * </ul>
 * 修复后它们都在解析期按真实递归层数报 E054。合法源码在上限内的行为不变（见 {@link NestingGuardStackSafetyTest}）。
 */
class ParserNestingGuardTest {

  private static final long ONE_MEGABYTE = 1024L * 1024L;

  private static String source(String form, int depth) {
    String expr = switch (form) {
      case "not-plus" -> "not + ".repeat(depth) + "c";
      case "not-comma" -> "not , ".repeat(depth) + "c";
      case "ifexpr" -> "If c then 1 otherwise ".repeat(depth) + "1";
      default -> throw new IllegalArgumentException(form);
    };
    return "Module probe.\nRule main given c as Bool, produce Int:\n  Return " + expr + ".\n";
  }

  /** 在 1MB 栈的线程上跑 ParseDiagnostics；栈溢出等 Error 原样返回，正常结束返回诊断列表。 */
  private static Object diagnosticsOnOneMegabyteStack(String source) throws InterruptedException {
    Object[] out = new Object[1];
    Thread t = new Thread(null, () -> {
      try {
        out[0] = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow("en-US")).diagnostics();
      } catch (Throwable e) {
        out[0] = e;
      }
    }, "parser-guard-probe", ONE_MEGABYTE);
    t.start();
    t.join();
    return out[0];
  }

  @ParameterizedTest(name = "{0}：夹杂 token 绕过词法期守卫，解析器层仍报 E054")
  @ValueSource(strings = {"not-plus", "not-comma", "ifexpr"})
  void strayTokensDoNotResetTheGuard(String form) throws InterruptedException {
    for (int depth : new int[] {3000, 20000}) {
      Object result = diagnosticsOnOneMegabyteStack(source(form, depth));
      if (result instanceof Throwable e) {
        throw new AssertionError(form + " 深度 " + depth + " 应报 E054，实际抛出 " + e, e);
      }
      @SuppressWarnings("unchecked")
      List<ParseDiagnostic> diags = (List<ParseDiagnostic>) result;
      assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.PARSE_NESTING_TOO_DEEP
              && "64".equals(d.params().get("limit"))),
          form + " 深度 " + depth + " 应含 E054（limit 64），实际: " + diags.subList(Math.max(0, diags.size() - 3), diags.size()));
    }
  }

  @Test
  void inlineIfAtLimitStillBuilds() throws InterruptedException {
    // 表达式守卫与 AstBuilder.visitExpr 同单位：Return 的表达式本身占一层，63 个内联 if 共 64 层，照常构建
    Object result = diagnosticsOnOneMegabyteStack(source("ifexpr", AstBuilder.MAX_EXPR_DEPTH - 1));
    assertEquals(List.of(), result);
  }

  @Test
  void inlineIfOverLimitIsE054AtParseTime() throws InterruptedException {
    Object result = diagnosticsOnOneMegabyteStack(source("ifexpr", AstBuilder.MAX_EXPR_DEPTH));
    @SuppressWarnings("unchecked")
    List<ParseDiagnostic> diags = (List<ParseDiagnostic>) result;
    assertEquals(1, diags.size(), diags.toString());
    assertEquals(ErrorCode.PARSE_NESTING_TOO_DEEP, diags.get(0).code());
    assertEquals("64", diags.get(0).params().get("limit"));
    assertEquals(3, diags.get(0).line());
  }

  @Test
  void notChainLimitMatchesLexerGuard() throws InterruptedException {
    // 解析器层的 not 链上限与词法期相同：64 个 not 放行，65 个报 E054
    String head = "Module probe.\nRule main given c as Bool, produce Bool:\n  Return ";
    assertEquals(List.of(), diagnosticsOnOneMegabyteStack(head + "not ".repeat(64) + "c.\n"));
    @SuppressWarnings("unchecked")
    List<ParseDiagnostic> over = (List<ParseDiagnostic>) diagnosticsOnOneMegabyteStack(head + "not ".repeat(65) + "c.\n");
    assertEquals(ErrorCode.PARSE_NESTING_TOO_DEEP, over.get(over.size() - 1).code());
  }

  @Test
  void parserGuardAloneStopsTypeRecursion() {
    // 用不带守卫的生成词法器切 token，模拟词法期类型守卫被绕过：解析器层守卫在第 65 层类型处报 E054，64 层照常解析
    String type = "list of ".repeat(AsterCustomLexer.MAX_TYPE_NESTING_DEPTH) + "Int";
    var tokens = new org.antlr.v4.runtime.CommonTokenStream(new org.antlr.v4.runtime.ListTokenSource(
        tokensWithoutTypeGuard(type)));
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    ParseDiagnosticException e = org.junit.jupiter.api.Assertions.assertThrows(ParseDiagnosticException.class,
        parser::annotatedType);
    assertEquals(ErrorCode.PARSE_NESTING_TOO_DEEP, e.code());
    assertEquals("64", e.params().get("limit"));

    var ok = new AsterParser(new org.antlr.v4.runtime.CommonTokenStream(new org.antlr.v4.runtime.ListTokenSource(
        tokensWithoutTypeGuard("list of ".repeat(AsterCustomLexer.MAX_TYPE_NESTING_DEPTH - 1) + "Int"))));
    ok.removeErrorListeners();
    assertNull(ok.annotatedType().exception);
  }

  /** 用不带守卫的生成词法器（AsterLexer）切出 token：模拟词法期守卫被绕过，只剩解析器层守卫。 */
  private static List<? extends org.antlr.v4.runtime.Token> tokensWithoutTypeGuard(String text) {
    var lexer = new AsterLexer(org.antlr.v4.runtime.CharStreams.fromString(text));
    return lexer.getAllTokens().stream().filter(t -> t.getChannel() == org.antlr.v4.runtime.Token.DEFAULT_CHANNEL).toList();
  }
}
