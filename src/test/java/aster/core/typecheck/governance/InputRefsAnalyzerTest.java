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
  /** 评审复现（I1）：64×64 扇出、h3 约 1500 行 Let、150 条 Verdict 规则。修复前 Java 约 70 秒。 */
  static String fanOutRepro(int lets, int rules, int fan) {
    StringBuilder b = new StringBuilder("Module dos.repro.\nProfile \"governed\".\n\n"
        + "Define Ctx has amount as Decimal.\nDefine Req has context as Ctx.\n\n"
        + "Rule h3 given c as Ctx, produce Bool:\n");
    for (int i = 0; i < lets; i++) b.append("  Let x").append(i).append(" be c.amount plus ").append(i).append(".\n");
    b.append("  Return c.amount greater than 0.\n\n");
    for (String[] hc : new String[][] {{"h2", "h3"}, {"h1", "h2"}}) {
      b.append("Rule ").append(hc[0]).append(" given c as Ctx, produce Bool:\n");
      for (int i = 0; i < fan; i++) b.append("  Let y").append(i).append(" be ").append(hc[1]).append("(c).\n");
      b.append("  Return true.\n\n");
    }
    for (int r = 0; r < rules; r++) {
      b.append("@id(\"R-").append(r).append("\")\nRule r").append(r).append(" given req as Req, produce Verdict:\n")
          .append("  If h1(req.context):\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n\n");
    }
    return b.toString();
  }

  @Test void 评审复现的扇出调用链分析在一秒内完成() {
    CoreModel.Module m = resolve(fanOutRepro(1500, 150, 64));
    long start = System.nanoTime();
    int rules = 0;
    for (CoreModel.Decl d : m.decls) {
      if (d instanceof CoreModel.Func f && InputRefsAnalyzer.applies(m, f)) {
        assertThat(InputRefsAnalyzer.analyze(m, f).inputRefs()).containsExactly("context.amount");
        rules++;
      }
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertThat(rules).isEqualTo(150);
    assertThat(ms).as("150 条规则的分析耗时（毫秒）").isLessThan(1000);
  }

  @Test void 节点预算耗尽为inline_depth且在一秒内完成() throws Exception {
    var fixture = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
        InputRefsAnalyzerTest.class.getResourceAsStream("/diagnostics/governance_input_refs_parity.json"));
    String src = null;
    for (var c : fixture.path("cases")) if ("node-visit-budget".equals(c.path("name").asText())) src = c.path("source").asText();
    assertThat(src).isNotNull();
    CoreModel.Module m = resolve(src);
    long start = System.nanoTime();
    var r = InputRefsAnalyzer.analyze(m, func(m, "decide"));
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertThat(r.construct()).isEqualTo("inline-depth");
    assertThat(ms).as("节点预算用例的分析耗时（毫秒）").isLessThan(1000);
  }
  /**
   * 第三轮审查 C1：记忆化键必须按结构比较。用户提交的 Core IR 可以在点路径段里带控制字符（源码里的字面量 Map 键已按
   * unrepresentable-key 拒绝），旧的字符串拼接键会把 {"context.meta.a\u0001zone"} 与 {"context.meta.a", "zone"} 当成
   * 同一实参，第二次调用命中缓存而漏记路径。
   */
  @Test void 记忆化键按结构比较_路径段含控制字符也不碰撞() throws Exception {
    String src = "Module guard.memo.\nProfile \"governed\".\n\n"
        + "Define Ctx has meta as Map Text to Text.\nDefine Req has context as Ctx, zone as Text.\n\n"
        + "Rule g given m as Text, produce Bool:\n  Return m equals to \"x\".\n\n"
        + "Rule decide given req as Req, produce Verdict:\n"
        + "  Let b be [Maybe.withDefault(Map.get(req.context.meta, \"a\"), \"\"), req.zone].\n"
        + "  Let r1 be g(b).\n  Let a be req.context.meta.aXzone.\n  Let r2 be g(a).\n"
        + "  If r1 and r2:\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n";
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    String json = mapper.writeValueAsString(resolve(src)).replace("aXzone", "a\\u0001zone");
    CoreModel.Module m = mapper.readValue(json, CoreModel.Module.class);
    assertThat(InputRefsAnalyzer.analyze(m, func(m, "decide")).inputRefs())
        .containsExactly("context.meta.a", "context.meta.a\u0001zone", "zone");
  }
}
