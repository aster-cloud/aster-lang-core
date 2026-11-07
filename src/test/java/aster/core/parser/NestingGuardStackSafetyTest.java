package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.typecheck.ErrorCode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 嵌套深度守卫必须在**真实线程栈**溢出之前触发（aster-api#472）。
 *
 * <p>★真实缺陷：两道守卫的阈值曾是 300，而 299 层括号在 1MB 栈（Linux x64 JVM 默认 -Xss，
 * 即 CI hosted runner 与生产 Quarkus worker 线程）上就已经 {@code StackOverflowError}——
 * 守卫形同虚设，Error 级异常穿透 aster-api 的 {@code catch (Exception)} 变成 500。
 *
 * <p>实测全链路（Canonicalizer → AsterCustomLexer → AsterParser.module() → AstBuilder）首次溢出深度
 * （GraalVM 25 / macOS，括号形态；-Xint 为解释器冷启动最坏情形）：
 * <pre>
 *   线程栈   括号(JIT/-Xint)   方括号(JIT/-Xint)   not 链(JIT/-Xint)   内联 if(JIT/-Xint)
 *   512K     98 / 84            69 / 68             1176 / 850          1077 / 418
 *   1024K    313 / 196          157 / 157           2500 / 1980         2500 / 973
 *   2048K    625 / 419          515 / 335           5857 / 4240         5000 / 2084
 * </pre>
 * 最窄的是 1MB 栈上的方括号（157）；上限取其一半以下的 64，并与 aster-lang-ts
 * {@code MAX_RECURSION_DEPTH} 同值。
 *
 * <p>本测试在**显式指定栈大小的线程**上跑全链路，不依赖运行平台的默认栈：1MB 对应生产 / CI，
 * 512K 证明余量。任何深度都只允许两种结果——正常构建，或带码 E054 的可恢复解析错误；
 * 绝不允许 {@code StackOverflowError}。
 */
class NestingGuardStackSafetyTest {

  private static final long KB = 1024L;

  /** 四种递归形态：括号、方括号（列表字面量）、not 链、内联 if 的 else 分支。 */
  private static String source(String form, int depth) {
    StringBuilder sb = new StringBuilder("Module probe.\nRule main given c as Bool, produce Int:\n  Return ");
    switch (form) {
      case "paren" -> sb.append("(".repeat(depth)).append("1").append(")".repeat(depth));
      case "bracket" -> sb.append("[".repeat(depth)).append("1").append("]".repeat(depth));
      case "not" -> sb.append("not ".repeat(depth)).append("c");
      case "ifexpr" -> sb.append("If c then 1 otherwise ".repeat(depth)).append("1");
      default -> throw new IllegalArgumentException(form);
    }
    return sb.append(".\n").toString();
  }

  /** 在指定栈大小的线程上跑全链路；返回 null 表示正常构建，否则为抛出的 Throwable。 */
  private static Throwable buildOn(long stackBytes, String src) throws InterruptedException {
    Throwable[] out = new Throwable[1];
    Thread t = new Thread(null, () -> {
      try {
        var lexer = new AsterCustomLexer(CharStreams.fromString(new Canonicalizer().canonicalize(src)));
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();
        tokens.seek(0);
        var parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        new AstBuilder().visitModule(parser.module());
      } catch (Throwable e) {
        out[0] = e;
      }
    }, "nesting-guard-probe", stackBytes);
    t.start();
    t.join();
    return out[0];
  }

  private static void assertRecoverableE054(Throwable actual, String what) {
    assertNotNull(actual, what + " 应被守卫拒绝");
    if (!(actual instanceof ParseDiagnosticException e)) {
      throw new AssertionError(what + " 应抛带码的可恢复解析错误，实际: " + actual, actual);
    }
    assertEquals(ErrorCode.PARSE_NESTING_TOO_DEEP, e.code(), what + " 应为 E054");
  }

  @ParameterizedTest(name = "{0}：上限内深度在 1MB 栈上正常构建")
  @ValueSource(strings = {"paren", "bracket", "not", "ifexpr"})
  void depthWithinLimitBuildsOnOneMegabyteStack(String form) throws InterruptedException {
    // 词法守卫按括号 / not 计数，AstBuilder 守卫按表达式层数计数（Return 的表达式本身占一层），
    // 故「上限内」取 MAX_NESTING_DEPTH - 1：两道守卫都不触发，且必须在 1MB 栈上建得出来。
    int depth = AsterCustomLexer.MAX_NESTING_DEPTH - 1;
    assertNull(buildOn(1024 * KB, source(form, depth)), form + " 深度 " + depth + " 应正常构建");
  }

  @ParameterizedTest(name = "{0}：超限一层在 1MB 栈上报 E054 而非栈溢出")
  @ValueSource(strings = {"paren", "bracket", "not", "ifexpr"})
  void depthJustOverLimitIsE054OnOneMegabyteStack(String form) throws InterruptedException {
    int depth = AsterCustomLexer.MAX_NESTING_DEPTH + 1;
    assertRecoverableE054(buildOn(1024 * KB, source(form, depth)), form + " 深度 " + depth);
  }

  @ParameterizedTest(name = "{0}：任意更深输入在 1MB 栈上仍是 E054")
  @ValueSource(strings = {"paren", "bracket", "not", "ifexpr"})
  void anyDeeperInputIsE054OnOneMegabyteStack(String form) throws InterruptedException {
    for (int depth : new int[] {299, 2000}) {
      assertRecoverableE054(buildOn(1024 * KB, source(form, depth)), form + " 深度 " + depth);
    }
  }

  @Test
  void guardFiresBeforeOverflowEvenOnHalfMegabyteStack() throws InterruptedException {
    // 余量：512K 栈上括号约 84 层溢出，守卫（64）仍先于溢出触发；上限内也仍建得出来。
    assertNull(buildOn(512 * KB, source("paren", AsterCustomLexer.MAX_NESTING_DEPTH - 1)));
    assertRecoverableE054(buildOn(512 * KB, source("paren", 299)), "512K 栈 299 层括号");
  }

  @Test
  void bothGuardsShareOneLimit() {
    // 两道守卫一个阈值：括号深度是表达式深度的下界，分开调只会留下一道形同虚设。
    assertEquals(AsterCustomLexer.MAX_NESTING_DEPTH, AstBuilder.MAX_EXPR_DEPTH);
  }
}
