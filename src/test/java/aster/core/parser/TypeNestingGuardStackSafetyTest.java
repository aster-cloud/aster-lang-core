package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.typecheck.ErrorCode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 类型嵌套守卫必须在**真实线程栈**溢出之前触发（aster-lang-core#250）。
 *
 * <p>实测全链路（Canonicalizer → AsterCustomLexer → AsterParser.module() → AstBuilder）在守卫之前的首次溢出深度
 * （单位同 {@link AsterCustomLexer#MAX_TYPE_NESTING_DEPTH}；GraalVM 25 / macOS，每种形态独立 JVM，
 * JIT 为从深度 10 逐层递增扫描，-Xint 为解释器最坏情形）：
 * <pre>
 *   线程栈   list 链      option 链    Map 值 / 键 / 交替          result 失败分量   result 成功分量
 *   512K     547 / 228    571 / 228    1049 / 967 / 1095 / 544      115 / 91          143 / 110
 *   1024K    1207 / 526   1232 / 526   2414 / 2517 / 2519 / 1256    262 / 206–211     —    / 230–253
 * </pre>
 * （-Xint 的 result 两列为二分得到的区间；1MB JIT 的 result 成功分量逐层扫描耗时过长，未测完；
 * Map 三种形态 -Xint 下相同。）最窄的是 result 分量嵌套：参数类型后紧跟 {@code ,} 时，{@code X of A and B} 的追加参数循环要靠 ANTLR 全上下文预测决定是否继续，预测的 closure 递归深度
 * 随嵌套层数增长，比 {@code type} 规则本身的递归更早耗尽栈。上限取 1MB {@code -Xint} 最窄值（约 206）的一半以下，
 * 并让 512K {@code -Xint}（91）仍有余量，定为 64，与表达式守卫同值。
 *
 * <p>本测试在显式指定栈大小的线程上跑全链路：任何深度只允许「正常构建」或「带码 E054」，绝不允许
 * {@code StackOverflowError}。
 */
class TypeNestingGuardStackSafetyTest {

  private static final long KB = 1024L;
  private static final int LIMIT = AsterCustomLexer.MAX_TYPE_NESTING_DEPTH;

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
    }, "type-nesting-guard-probe", stackBytes);
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

  @ParameterizedTest(name = "{0}：上限深度在 1MB 栈上正常构建")
  @ValueSource(strings = {"list", "option", "mapval", "mapkey", "mapalt", "resultok", "resulterr", "func"})
  void depthAtLimitBuildsOnOneMegabyteStack(String form) throws InterruptedException {
    assertNull(buildOn(1024 * KB, TypeNestingShapes.source(form, LIMIT)), form + " 深度 " + LIMIT + " 应正常构建");
  }

  @ParameterizedTest(name = "{0}：上限深度在 512K 栈上也正常构建")
  @ValueSource(strings = {"list", "option", "mapval", "mapkey", "mapalt", "resultok", "resulterr", "func"})
  void depthAtLimitBuildsOnHalfMegabyteStack(String form) throws InterruptedException {
    assertNull(buildOn(512 * KB, TypeNestingShapes.source(form, LIMIT)), form + " 深度 " + LIMIT + " 应正常构建");
  }

  @ParameterizedTest(name = "{0}：超限与更深输入在 1MB / 512K 栈上都是 E054")
  @ValueSource(strings = {"list", "option", "mapval", "mapkey", "mapalt", "resultok", "resulterr", "func"})
  void deeperInputIsE054NotStackOverflow(String form) throws InterruptedException {
    for (int depth : new int[] {LIMIT + 1, 300, 2000}) {
      assertRecoverableE054(buildOn(1024 * KB, TypeNestingShapes.source(form, depth)), form + " 深度 " + depth + "（1MB）");
      assertRecoverableE054(buildOn(512 * KB, TypeNestingShapes.source(form, depth)), form + " 深度 " + depth + "（512K）");
    }
  }

  @ParameterizedTest(name = "构造词后夹杂多余 token「{0}」不能让计数清零")
  @ValueSource(strings = {"+ ", "as ", ". ", ": ", "+ + "})
  void garbageAfterConstructorDoesNotResetCount(String garbage) throws InterruptedException {
    // ANTLR 的错误恢复会删掉 / 跳过 `list of` 后面的多余 token，在同一层继续递归；
    // 守卫若在这些 token 上退出类型模式，就会每段从零数起，3000 层仍然栈溢出。
    String src = "Module probe.\n\nRule f given x as " + ("list of " + garbage).repeat(3000) + "Int, produce Int:\n  Return 1.\n";
    assertRecoverableE054(buildOn(1024 * KB, src), "夹杂「" + garbage.strip() + "」的 3000 层 list 链");
  }
}
