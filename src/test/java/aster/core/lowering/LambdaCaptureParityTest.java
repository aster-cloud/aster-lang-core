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
  void letRightHandSideSeesOuterBindingNotItself() {
    // ★复评发现（2026-08-18）：TS 侧为「先递归再绑定」专门加了用例并变异验证，
    //   Java 侧同一逻辑（CoreLowering:804-807）**没有对应用例** ——
    //   把 Stmt.Let 改成先绑定后递归，6 个用例全绿，而该变异实际造成
    //   Java [[]] vs TS [["outer"]] 的分歧。两侧测试覆盖不对称。
    //
    //   `Let outer be outer.` 的右侧指的是**外层**的 outer（此时同名局部尚未生效），
    //   故必须捕获；先绑定会把它误判成已绑定而漏掉。
    String src = "Module probe.\n\nRule r given outer, produce:\n"
        + "  Let f be function with x, produce:\n"
        + "    Let outer be outer.\n"
        + "    Return outer.\n"
        + "  Return f(outer).\n";
    var lambdas = allCapturesOf(src);
    assertTrue(!lambdas.isEmpty(), "应解析出 Lambda，实际=" + lambdas);
    assertTrue(lambdas.get(0).contains("outer"),
        "Let 右侧引用的外层 outer 必须捕获（先递归后绑定），实际=" + lambdas.get(0));
  }

  @Test
  void matchScrutineeAndPatternBindingsAreHandled() {
    // ★终审发现（发现 B）：Java 侧的 Match 分支（CoreLowering:817-835）与
    //   patternBindings（:942-965）**零用例覆盖** —— 对称变异实测：
    //     JE（scrutinee 不递归）→ 7 绿逃逸
    //     JG（PatternCtor names 不绑）→ 7 绿逃逸
    //   上一轮修掉「Let 顺序两侧覆盖不对称」后，Match 上出现了同型的新不对称
    //   （这次方向相反：TS 有、Java 无）。此处补齐。

    // ① scrutinee 位的自由变量必须捕获（两个 case 都不引用 outer）
    var scrutinee = allCapturesOf("Module probe.\n\nRule r given outer, produce:\n"
        + "  Let f be function with x, produce:\n"
        + "    Match outer:\n"
        + "      When 1, Return x.\n"
        + "      When rest, Return x.\n"
        + "  Return f(outer).\n");
    assertTrue(scrutinee.get(0).contains("outer"),
        "Match 被匹配表达式里的 outer 必须捕获，实际=" + scrutinee.get(0));

    // ② 模式绑定的名字不得计入 captures
    var bound = allCapturesOf("Module probe.\n\nRule r given outer, produce:\n"
        + "  Let f be function with x, produce:\n"
        + "    Match x:\n"
        + "      When bound, Return bound.\n"
        + "  Return f(outer).\n");
    assertTrue(!bound.get(0).contains("bound"),
        "Match 绑定的 bound 不得计入 captures，实际=" + bound.get(0));

    // ③ 构造器模式 `When User(id, name)` 绑定的字段名同样不得计入 captures。
    //
    //    ★关于终审提到的变异 JG（PatternCtor 不绑 names）：补了本用例后它**仍然全绿**，
    //      我查证后确认这是**等价变异**而非测试缺口 —— 实测该模式的 AST 是
    //        PatternCtor[names=[id,name], args=[PatternName(id), PatternName(name)]]
    //      names 与 args 携带同一组名字，patternBindings 里 `names.addAll(ctor.names())`
    //      与随后对 args 的递归**互为冗余**，杀掉任一条另一条都能绑上。
    //      故 JG 不改变任何可观测行为，不存在能让它变红的用例。
    //      （若将来 args 不再镜像 names，这条冗余才会变成真覆盖缺口。）
    var ctor = allCapturesOf("Module probe.\n\n"
        + "Define User has id, name.\n\n"
        + "Rule r given prefix, produce:\n"
        + "  Let f be function with value, produce:\n"
        + "    Match value:\n"
        + "      When User(id, name), Return Text.concat(prefix, name).\n"
        + "      When other, Return prefix.\n"
        + "  Return f(prefix).\n");
    assertTrue(!ctor.get(0).contains("name"),
        "构造器模式绑定的字段 name 不得计入 captures，实际=" + ctor.get(0));
    assertTrue(ctor.get(0).contains("prefix"),
        "case 体里的 prefix 是自由变量、必须捕获，实际=" + ctor.get(0));

    // ④ **嵌套**构造器模式 —— 锁住 patternBindings 里对 args 的递归。
    //
    //    ★终审复核给出了比我原判更精确的结论：`names` 与 `args` 之所以等价，
    //      根因是 AstBuilder:1024-1034 里 `names` **由 args 过滤派生**
    //      （filter 出 PatternName 再取名），故 names ⊆ args 递归结果，
    //      数学上不可能提供额外绑定 —— 这才是 JG 无法证伪的原因。
    //
    //      而真正**承重**的是 args 递归（变异 JI）：删掉它后
    //      `When Ok(Some(inner))` 产出 [inner, outer]（正确为 [outer]），
    //      而上面 ③ 用的 `User(id, name)` 是 names == args 的**平坦**形态，
    //      锁不住嵌套递归。此处用嵌套形态补上。
    var nested = allCapturesOf("Module probe.\n\nRule r given outer, produce:\n"
        + "  Let f be function with value, produce:\n"
        + "    Match value:\n"
        + "      When Ok(Some(inner)), Return inner.\n"
        + "      When other, Return outer.\n"
        + "  Return f(outer).\n");
    assertTrue(!nested.get(0).contains("inner"),
        "嵌套构造器模式绑定的 inner 不得计入 captures（需 args 递归），实际=" + nested.get(0));
    assertTrue(nested.get(0).contains("outer"),
        "另一 case 里的 outer 仍须捕获，实际=" + nested.get(0));

    // ⑤ 各 case 独立作用域：前一个 case 的绑定不得泄漏到后一个
    var leak = allCapturesOf("Module probe.\n\nRule r given bound, produce:\n"
        + "  Let f be function with x, produce:\n"
        + "    Match x:\n"
        + "      When bound, Return bound.\n"
        + "      When other, Return bound.\n"
        + "  Return f(bound).\n");
    assertTrue(leak.get(0).contains("bound"),
        "第二个 case 里的 bound 是自由变量、必须捕获，实际=" + leak.get(0));
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
