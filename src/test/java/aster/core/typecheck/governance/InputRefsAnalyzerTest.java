package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InputRefsAnalyzer 的单元性质（ADR 0046 §11.6）。ADR §11.2 正反例表逐行、补充规则 (a)–(c)、别名、遍历顺序与
 * 本地化用例在共享夹具中（GovernanceInputRefsParityTest，两引擎同一份）；这里只锁夹具之外的性质。
 */
class InputRefsAnalyzerTest {
  private static final String HEAD = "Module guard.unit.\nProfile \"governed\".\n\n"
      + "Define Ctx has amount as Decimal, tier as Text, meta as Map Text to Text.\n"
      + "Define Req has action as Text, context as Ctx.\n\n";

  private static CoreModel.Module resolve(String src) {
    return GovernanceTestSupport.resolveSource(src, null, null);
  }

  private static CoreModel.Func func(CoreModel.Module m, String name) {
    return m.decls.stream().filter(d -> d instanceof CoreModel.Func f && name.equals(f.name))
        .map(CoreModel.Func.class::cast).findFirst().orElseThrow();
  }

  private static List<String> refs(String body) {
    CoreModel.Module m = resolve(HEAD + "Rule decide given req as Req, produce Verdict:\n" + body);
    return InputRefsAnalyzer.analyze(m, func(m, "decide")).inputRefs();
  }

  @Test void 星号独占且不与具体路径并存() {
    var refs = refs("  If req.action equals to \"x\" and Map.get(req.context.meta, req.context.tier) equals to \"y\":\n"
        + "    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n");
    assertThat(refs).containsExactly("*");
  }

  @Test void 路径按UTF16码元字典序去重() {
    var refs = refs("  If req.context.tier equals to req.action and req.context.amount greater than 0 and req.action equals to \"a\":\n"
        + "    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n");
    assertThat(refs).containsExactly("action", "context.amount", "context.tier");
  }

  @Test void 不读输入的规则为空列表而非null() {
    CoreModel.Module m = resolve(HEAD + "@id(\"R\")\nRule decide given req as Req, produce Verdict:\n  Return Verdict.allow().\n");
    assertThat(func(m, "decide").inputRefs).isNotNull().isEmpty();
  }

  @Test void 未知档案的模块同样写入inputRefs() {
    CoreModel.Module m = resolve("Module m.\nProfile \"nope\".\n\n"
        + "Rule decide given amount, produce Verdict:\n  If amount at least 1:\n    Return Verdict.deny(\"d\").\n"
        + "  Return Verdict.allow().\n");
    assertThat(func(m, "decide").inputRefs).containsExactly("");
  }

  @Test void 未声明档案的模块不写inputRefs() {
    CoreModel.Module m = resolve("Module m.\n\n"
        + "Rule decide given amount, produce Verdict:\n  If amount at least 1:\n    Return Verdict.deny(\"d\").\n"
        + "  Return Verdict.allow().\n");
    assertThat(func(m, "decide").inputRefs).isNull();
    assertThat(InputRefsAnalyzer.applies(m, func(m, "decide"))).isFalse();
  }

  @Test void 按Return推断为Verdict的规则也写入() {
    CoreModel.Module m = resolve(HEAD + "Rule decide given req as Req, produce:\n"
        + "  If req.action equals to \"x\":\n    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n");
    assertThat(func(m, "decide").inputRefs).containsExactly("action");
  }

  @Test void 同模块调用实参不含输入时不展开且不报递归() {
    var refs = refs("  If loop(1):\n    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n\n"
        + "Rule loop given n as Int, produce Bool:\n  Return loop(n).\n");
    assertThat(refs).isEmpty();
  }

  @Test void 自递归为recursion() {
    CoreModel.Module m = resolve(HEAD + "Rule decide given req as Req, produce Verdict:\n"
        + "  If deep(req.context):\n    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n\n"
        + "Rule deep given c as Ctx, produce Bool:\n  Return deep(c).\n");
    var r = InputRefsAnalyzer.analyze(m, func(m, "decide"));
    assertThat(r.inputRefs()).containsExactly("*");
    assertThat(r.construct()).isEqualTo("recursion");
  }

  @Test void 被调函数返回值的来源随结果流回调用点() {
    var refs = refs("  Let t be tierOf(req.context).\n  If t equals to \"gold\":\n    Return Verdict.allow().\n"
        + "  Return Verdict.deny(\"t\").\n\n"
        + "Rule tierOf given c as Ctx, produce Text:\n  Return c.tier.\n");
    assertThat(refs).containsExactly("context.tier");
  }

  @Test void 被调函数之外的形参不追踪但被调函数的全部形参都追踪() {
    var refs = refs("  If both(1, req.action):\n    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n\n"
        + "Rule both given n as Int, a as Text, produce Bool:\n  Return a equals to \"x\".\n");
    assertThat(refs).containsExactly("action");
  }

  @Test void lambda体内的读取计入() {
    var refs = refs("  Let f be function with x, produce:\n    Return req.context.amount greater than 0.\n"
        + "  Return Verdict.allow().\n");
    assertThat(refs).containsExactly("context.amount");
  }

  @Test void 分析是纯函数() {
    CoreModel.Module m = resolve(HEAD + "Rule decide given req as Req, produce Verdict:\n"
        + "  If req.action equals to \"x\":\n    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n");
    var f = func(m, "decide");
    assertThat(InputRefsAnalyzer.analyze(m, f)).isEqualTo(InputRefsAnalyzer.analyze(m, f));
  }
}
