package aster.core.cedar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProbeGeneratorTest {

  private static final long SAFE_MAX = 9007199254740991L;
  private static final long OUT_OF_DOMAIN = 9007199254740992L;

  @Test
  void cartesianWhenSmall() {
    var facts = factsOf("""
        @id("R-1")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.action equals to "delete":
            Return Verdict.deny("no").
          Return Verdict.allow().
        """);
    var probes = ProbeGenerator.generate(facts, "decide", 512);
    assertThat(probes).extracting(Probe::action).containsExactlyInAnyOrder("delete", "__aster_other__");
  }

  @Test
  void numericNeighbours() {
    var facts = factsOf("""
        @id("R-2")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.context.amount at least 1000:
            Return Verdict.escalate("big").
          Return Verdict.allow().
        """);
    var values = ProbeGenerator.generate(facts, "decide", 512).stream()
        .map(p -> p.context().get("amount")).toList();
    assertThat(values).contains(999L, 1000L, 1001L).containsNull();
  }

  @Test
  void capRespectedAndDeterministic() {
    var facts = wideFacts();
    var a = ProbeGenerator.generate(facts, "decide", 512);
    var b = ProbeGenerator.generate(facts, "decide", 512);
    assertThat(a).hasSizeLessThanOrEqualTo(512).isEqualTo(b);
    assertThat(a).doesNotHaveDuplicates();
  }

  @Test
  void noContextRuleProbes() {
    var facts = factsOf("""
        @id("R-3")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.action equals to "a":
            Return Verdict.deny("x").
          If req.action equals to "b":
            Return Verdict.deny("y").
          Return Verdict.allow().
        """);
    assertThat(ProbeGenerator.generate(facts, "decide", 512)).hasSizeGreaterThanOrEqualTo(3);
  }

  @Test
  void cartesianOrderIsMixedRadixWithFirstDimensionMostSignificant() {
    var facts = factsOf("""
        @id("R-4")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.action equals to "a" and req.context.vip:
            Return Verdict.deny("x").
          Return Verdict.allow().
        """);
    var probes = ProbeGenerator.generate(facts, "decide", 512);
    // action 维度 [a, other] 为高位，vip 维度 [缺失, true, false] 为低位
    assertThat(probes).extracting(p -> p.action() + "/" + p.context().get("vip")).containsExactly(
        "a/null", "a/true", "a/false",
        "__aster_other__/null", "__aster_other__/true", "__aster_other__/false");
  }

  @Test
  void strideFillDocumentedExample() {
    // 维度：action = [a, b, other]（3），amount = [缺失, 9, 10, 11, 2^53]（5），其余维度大小均为 1；total = 15 > max = 10
    // 基准：(a, 10)；逐一改变单维：(b,10) (other,10) (a,缺失) (a,9) (a,11) (a,2^53) → 已取 7
    // 步长 = ceil(15 / (10 - 7)) = 5，序号 0 → (a,缺失) 重复跳过；5 → (b,缺失)；10 → (other,缺失)
    var facts = factsOf("""
        @id("R-5")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.action equals to "a" and req.context.amount greater than 10:
            Return Verdict.deny("x").
          If req.action equals to "b":
            Return Verdict.deny("y").
          Return Verdict.allow().
        """);
    var probes = ProbeGenerator.generate(facts, "decide", 10);
    assertThat(probes).extracting(p -> p.action() + "/" + p.context().get("amount")).containsExactly(
        "a/10", "b/10", "__aster_other__/10",
        "a/null", "a/9", "a/11", "a/" + OUT_OF_DOMAIN,
        "b/null", "__aster_other__/null");
  }

  @Test
  void baseAndSingleVariationsTruncatedWhenCapTiny() {
    var probes = ProbeGenerator.generate(wideFacts(), "decide", 3);
    assertThat(probes).hasSize(3);
    assertThat(probes.get(0).context()).containsEntry("k0", 10L).containsEntry("k7", 10L);
  }

  @Test
  void outOfDomainValueMarksProbeAndNeighboursStayInDomain() {
    // Aster 源码不支持负整数字面量，直接构造事实以覆盖下界
    var facts = new RuleFacts();
    facts.recordType("amount", ContextType.LONG);
    facts.recordLiteral("amount", -SAFE_MAX);
    var probes = ProbeGenerator.generate(facts, "decide", 512);
    assertThat(probes).extracting(p -> p.context().get("amount"))
        .containsExactly(null, -SAFE_MAX, -SAFE_MAX + 1, OUT_OF_DOMAIN);
    assertThat(probes).filteredOn(Probe::outOfDomain).extracting(p -> p.context().get("amount"))
        .containsExactly(OUT_OF_DOMAIN);
    assertThat(probes.get(0).toGuardArgs()).doesNotContainKey("outOfDomain");
  }

  @Test
  void upperEdgeNeighbourOmitted() {
    var facts = factsOf("""
        @id("R-7")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.context.amount equals to 9007199254740991L:
            Return Verdict.deny("edge").
          Return Verdict.allow().
        """);
    var probes = ProbeGenerator.generate(facts, "decide", 512);
    assertThat(probes).extracting(p -> p.context().get("amount"))
        .containsExactly(null, SAFE_MAX - 1, SAFE_MAX, OUT_OF_DOMAIN);
    assertThat(probes).filteredOn(p -> !p.outOfDomain()).allMatch(p -> !p.context().containsValue(OUT_OF_DOMAIN));
  }

  @Test
  void missingKeyIsAbsentAndContextImmutableInKeyOrder() {
    var facts = factsOf("""
        @id("R-8")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.context.tier equals to "gold" and req.context.vip:
            Return Verdict.allow().
          Return Verdict.deny("no").
        """);
    var probes = ProbeGenerator.generate(facts, "decide", 512);
    assertThat(probes).anyMatch(p -> p.context().isEmpty());
    assertThat(probes).anyMatch(p -> !p.context().containsKey("tier") && p.context().containsKey("vip"));
    assertThat(probes).extracting(p -> p.context().get("tier")).contains("gold", "__aster_other__");
    var full = probes.stream().filter(p -> p.context().size() == 2).findFirst().orElseThrow();
    assertThat(new ArrayList<>(full.context().keySet())).containsExactly("tier", "vip");
    assertThatThrownBy(() -> full.context().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void toGuardArgsMatchesFlattenKeyOrder() {
    var probe = ProbeGenerator.generate(factsOf("""
        @id("R-9")
        Rule decide given req as GuardRequest, produce Verdict:
          Return Verdict.allow().
        """), "decide", 512).get(0);
    Map<String, Object> args = probe.toGuardArgs();
    assertThat(new ArrayList<>(args.keySet())).containsExactly("principal_id", "principal_type", "principal_roles",
        "action", "resource_type", "resource_id", "context", "agent_model");
    assertThat(args).containsEntry("principal_id", "p-probe").containsEntry("principal_type", "agent")
        .containsEntry("principal_roles", List.of()).containsEntry("action", "decide")
        .containsEntry("resource_type", "res").containsEntry("resource_id", "r-probe")
        .containsEntry("context", Map.of()).containsEntry("agent_model", null);
    assertThat(probe.outOfDomain()).isFalse();
  }

  @Test
  void rolesDimensionAndAgentModel() {
    var facts = factsOf("""
        @id("R-10")
        Rule decide given req as GuardRequest, produce Verdict:
          If List.contains(req.principal_roles, "ops") or List.contains(req.principal_roles, "dev"):
            Return Verdict.allow().
          If req.agent_model equals to "m1":
            Return Verdict.escalate("m").
          Return Verdict.deny("no").
        """);
    var probes = ProbeGenerator.generate(facts, "decide", 512);
    assertThat(probes).extracting(Probe::roles).containsOnly(List.of(), List.of("ops"), List.of("dev"), List.of("ops", "dev"));
    assertThat(probes).extracting(Probe::agentModel).containsOnly(null, "m1", "__aster_other__");
    assertThat(probes).hasSize(4 * 3);
  }

  @Test
  void agentModelAlwaysNullWhenUnused() {
    assertThat(ProbeGenerator.generate(wideFacts(), "decide", 512)).allMatch(p -> Objects.isNull(p.agentModel()));
  }

  @Test
  void rejectsNonPositiveCap() {
    assertThatThrownBy(() -> ProbeGenerator.generate(wideFacts(), "decide", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RuleFacts factsOf(String rule) {
    var exp = CedarExporter.export(CedarTestSupport.lower("Module m.\n\n" + GuardShapeTest.GUARD + rule), "decide", "test");
    assertThat(exp.exportable()).as("exportable: %s", exp.diagnostics()).isTrue();
    return exp.facts();
  }

  // 8 个 LONG context 键，各 3 个字面量（10、20、30）
  private static RuleFacts wideFacts() {
    var src = new StringBuilder("@id(\"R-W\")\nRule decide given req as GuardRequest, produce Verdict:\n");
    for (int k = 0; k < 8; k++) {
      for (int lit = 10; lit <= 30; lit += 10) {
        src.append("  If req.context.k").append(k).append(" equals to ").append(lit).append(":\n")
            .append("    Return Verdict.deny(\"k").append(k).append("\").\n");
      }
    }
    src.append("  Return Verdict.allow().\n");
    return factsOf(src.toString());
  }
}
