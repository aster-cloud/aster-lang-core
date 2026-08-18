package aster.core.lowering;

import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * Lambda 闭包捕获的表达式覆盖完整性（2026-08-17 审计修复）。
 *
 * <p>捕获收集靠 {@code CoreLowering.visitExpr} 逐类型递归。凡是漏掉一个含子表达式的
 * 变体，该变体<b>包裹</b>下的外部变量引用就收不进 {@code captures}——而
 * {@code captures} 不是元数据：Truffle 的 {@code Loader} 用它构造 FrameDescriptor，
 * {@code LambdaRootNode} 按位置传实参。捕获缺失 = 该变量在被调帧里根本没有槽位。
 *
 * <p>本轮实测出的双向分歧（同一段源码、两个引擎的 Core IR 不同）：
 *
 * <table>
 *   <tr><th>包裹形式</th><th>TS</th><th>Java</th></tr>
 *   <tr><td>{@code [outer, x]}（列表字面量）</td><td>漏</td><td>收</td></tr>
 *   <tr><td>{@code If c then outer else x}（内联 if）</td><td>收</td><td>漏</td></tr>
 * </table>
 *
 * <p>关键点：语义完全相同，只差一层语法包裹；两侧都<b>不报错</b>，只是静默产出
 * 不同的 IR。这正是 parity 门禁该拦而没拦住的形态——
 * {@code parity-tier1.mjs} 把 {@code captures} 从 IR 比对中剔除了，
 * 理由是「两引擎闭包执行方式一致（eval-parity 覆盖）」，
 * 而语料里 lambda × 列表字面量 / 内联 if 的覆盖数为 <b>0</b>。
 */
class LambdaCaptureParityTest {

  /** 取源码中第一个 Lambda 的 captures 列表。 */
  private List<String> capturesOf(String body) {
    String src = "Module probe.\n\nRule r given outer, produce:\n"
        + "  Let f be function with x, produce:\n"
        + "    Return " + body + ".\n"
        + "  Return f(outer).\n";
    var lambdas = allCapturesOf(src);
    assertTrue(!lambdas.isEmpty(), "源码里应至少有一个 Lambda，实际没解析出来：" + body);
    return lambdas.get(0);
  }

  /** 取源码中全部 Lambda 的 captures（按遍历序，外层在前）。 */
  private List<List<String>> allCapturesOf(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    var ast = new AstBuilder().visitModule(parser.module());
    var core = new CoreLowering().lowerModule(ast);
    var found = new ArrayList<List<String>>();
    collectLambdas(core, found);
    return found;
  }

  /** 反射遍历 Core IR，收集所有 Lambda 的 captures（避免为测试给 IR 加访问器）。 */
  private void collectLambdas(Object node, List<List<String>> out) {
    if (node == null) {
      return;
    }
    if (node instanceof CoreModel.Lambda lambda) {
      out.add(lambda.captures == null ? List.of() : lambda.captures);
    }
    if (node instanceof Iterable<?> iterable) {
      for (Object child : iterable) {
        collectLambdas(child, out);
      }
      return;
    }
    if (node instanceof String || node instanceof Number || node instanceof Boolean) {
      return;
    }
    if (!node.getClass().getName().startsWith("aster.core")) {
      return;
    }
    for (var field : node.getClass().getFields()) {
      try {
        collectLambdas(field.get(node), out);
      } catch (IllegalAccessException ignored) {
        // 不可达字段跳过
      }
    }
  }

  @Test
  void captureThroughListLiteralIsCollected() {
    // 基线：Java 侧本来就收（TS 侧此前漏，已在 aster-lang-ts 同步修复）
    assertTrue(capturesOf("[outer, x]").contains("outer"),
        "列表字面量内引用的外部变量必须计入 captures");
  }

  @Test
  void captureThroughInlineIfIsCollected() {
    // ★本次修复的核心：内联 if 的三个子表达式此前在 Java 侧完全不递归，
    //   `outer` 被静默丢弃 → Truffle 帧里没有该变量的槽位。
    assertTrue(capturesOf("If x then outer else x").contains("outer"),
        "内联 if 的分支里引用的外部变量必须计入 captures");
  }

  @Test
  void captureInInlineIfConditionIsCollected() {
    // 条件位也是子表达式，不能只递归两个分支。
    assertTrue(capturesOf("If outer then x else x").contains("outer"),
        "内联 if 的条件里引用的外部变量必须计入 captures");
  }

  @Test
  void nestedListInsideInlineIfIsCollected() {
    // 组合嵌套：两条修复路径必须能互相穿透，而不是各自只补一层。
    assertTrue(capturesOf("If x then [outer] else [x]").contains("outer"),
        "内联 if 分支内的列表字面量里的外部变量必须计入 captures");
  }

  @Test
  void nestedLambdaCapturePropagatesToOuter() {
    // 第三处分歧：内层 lambda 引用 outer 时，**外层也必须捕获**——
    // 外层不捕获就没有该变量，内层无从拿到。此前 Java 在 Expr.Lambda 直接 return。
    // ★内层体必须**真的引用 y**（对抗性审查 2026-08-18 发现）：
    //   原写法内层体是 `Return outer.`，y 从未被引用，而捕获收集只记录被引用的名字
    //   —— 于是下面那条「y 不得穿透」的反向断言**恒真**，是空断言。
    //   实证：把「压入内层形参」的逻辑改坏（改成继承外层作用域、不绑定内层形参），
    //   6 个用例仍全绿，而该变异确实制造了 y 泄漏进外层 captures 的污染。
    //   改成 `If y then outer else outer` 后，只有内层形参真正入栈才不会误记。
    String src = "Module probe.\n\nRule r given outer, produce:\n"
        + "  Let f be function with x, produce:\n"
        + "    Let g be function with y, produce:\n"
        + "      Return If y then outer else outer.\n"
        + "    Return g(x).\n"
        + "  Return f(outer).\n";
    var lambdas = allCapturesOf(src);
    assertTrue(lambdas.size() >= 2, "应有内外两个 Lambda，实际=" + lambdas);
    assertTrue(lambdas.get(0).contains("outer"),
        "外层 lambda 必须捕获内层用到的 outer，实际=" + lambdas.get(0));
    assertTrue(lambdas.get(1).contains("outer"),
        "内层 lambda 自身也必须捕获 outer，实际=" + lambdas.get(1));
    assertTrue(!lambdas.get(0).contains("y"),
        "内层形参 y 不得穿透成外层的捕获，实际=" + lambdas.get(0));
    assertTrue(!lambdas.get(1).contains("y"),
        "y 是内层自己的形参，也不得计入内层的 captures，实际=" + lambdas.get(1));
    assertTrue(!lambdas.get(0).contains("g"),
        "g 由 Let 绑定，不是自由变量，不得计入外层 captures，实际=" + lambdas.get(0));
  }

  @Test
  void lambdaParameterIsNotCaptured() {
    // ★同等重要的一半：反向断言。否则「把所有 Name 无条件塞进 captures」
    //   也能让上面四条通过——那是假修复，且会给 Truffle 多造出无用槽位。
    assertTrue(!capturesOf("[outer, x]").contains("x"),
        "lambda 自身参数 x 是绑定变量，不得计入 captures");
    assertTrue(!capturesOf("If x then outer else x").contains("x"),
        "内联 if 里出现的参数 x 同样不得计入 captures");
  }
}
