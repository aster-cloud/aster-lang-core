package aster.core.cedar;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端黄金测试：src/test/resources/cedar/ 下每个 .aster 导出后与 .cedar / .cedarschema.json 逐字比较。
 * 黄金文件经人工核对：分支序号、每条路径的 T/F、Let 的已定义要求与注解顺序均已逐行检查。
 */
class CedarExporterGoldenTest {

  @ParameterizedTest
  @ValueSource(strings = {"probe", "when-sugar", "nested-otherwise"})
  void matchesGolden(String name) throws Exception {
    var exp = CedarExporter.export(CedarTestSupport.lower(resource(name + ".aster")), "decide", "test");
    assertThat(exp.diagnostics()).isEmpty();
    assertThat(exp.exportable()).isTrue();
    assertThat(exp.policies()).isEqualTo(resource(name + ".cedar"));
    JSONAssert.assertEquals(resource(name + ".cedarschema.json"), exp.schemaJson(), true);
  }

  @Test void missingKeyInEarlierBranchNeverReachesLaterPermit() {
    var exp = export("""
        @id("R-9")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.context.amount at least 10:
            Return Verdict.deny("big").
          Return Verdict.allow().
        """);
    var permit = exp.emitted().stream().filter(p -> p.effect().equals("permit")).findFirst().orElseThrow();
    assertThat(permit.condition()).isEqualTo("(context has amount && (-9007199254740991 <= context.amount && context.amount <= 9007199254740991) && !(context.amount >= 10))");
  }

  @Test void exportIsAllOrNothing() {
    var exp = export("""
        @id("R-8")
        Rule decide given req as GuardRequest, produce Verdict:
          If Text.length(req.action) at least 1:
            Return Verdict.deny("x").
          Match req.action:
            When other, Return Verdict.allow().
          Return Verdict.allow().
        """);
    assertThat(exp.exportable()).isFalse();
    assertThat(exp.policies()).isNull();
    assertThat(exp.schemaJson()).isNull();
    assertThat(exp.emitted()).isEmpty();
    assertThat(exp.diagnostics()).extracting(d -> d.code().code()).containsExactly("E721", "E721");
  }

  @Test void missingFunctionIsE720() {
    var exp = CedarExporter.export(CedarTestSupport.lower("Module m.\n"), "decide", "test");
    assertThat(exp.exportable()).isFalse();
    assertThat(exp.diagnostics()).extracting(d -> d.code().code()).containsExactly("E720");
    assertThat(exp.diagnostics().get(0).data()).containsEntry("reason", "function decide not found");
  }

  @Test void branchWithoutVerdictRejected() {
    var exp = export("""
        @id("R-7")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.action equals to "a":
            Return Verdict.deny("x").
        """);
    assertThat(exp.diagnostics()).extracting(d -> d.data().get("construct"))
        .containsExactly("branch that can end without a Verdict");
  }

  @Test void nonLiteralVerdictArgumentRejected() {
    var exp = export("""
        @id("R-6")
        Rule decide given req as GuardRequest, produce Verdict:
          Return Verdict.deny(req.action).
        """);
    assertThat(exp.diagnostics()).extracting(d -> d.data().get("construct"))
        .containsExactly("non-literal verdict argument");
  }

  @Test void keylessLetAddsNothingAndFactsAreCollected() {
    var exp = export("""
        @id("R-5")
        Rule decide given req as GuardRequest, produce Verdict:
          Let ops be List.contains(req.principal_roles, "ops").
          If ops and req.context.tier equals to "gold":
            Return Verdict.allow().
          Return Verdict.deny("no").
        """);
    assertThat(exp.emitted().get(0).condition())
        .isEqualTo("((principal.roles.contains(\"ops\")) && (context has tier && (context.tier == \"gold\")))");
    assertThat(exp.facts().roles()).containsExactly("ops");
    assertThat(exp.facts().contextTypes()).containsEntry("tier", ContextType.STRING);
    assertThat(exp.facts().contextLiterals().get("tier")).containsExactly("gold");
    assertThat(exp.schemaJson()).contains("\"decide\"");
  }

  @Test void arithmeticComparisonRecordsZeroThenLiteralForEveryKey() {
    var exp = export("""
        @id("R-6")
        Rule decide given req as GuardRequest, produce Verdict:
          If req.context.amount plus req.context.fee greater than 5000:
            Return Verdict.deny("sum").
          If req.context.qty times req.context.price at least 100:
            Return Verdict.deny("product").
          Return Verdict.allow().
        """);
    // 0 在前：基准探针把其余操作数固定为 0；乘法额外记录 1
    assertThat(exp.facts().contextLiterals()).containsExactly(
        java.util.Map.entry("amount", java.util.List.of(0L, 5000L)),
        java.util.Map.entry("fee", java.util.List.of(0L, 5000L)),
        java.util.Map.entry("qty", java.util.List.of(0L, 100L, 1L)),
        java.util.Map.entry("price", java.util.List.of(0L, 100L, 1L)));
    assertThat(exp.facts().contextTypes()).containsOnlyKeys("amount", "fee", "qty", "price")
        .allSatisfy((k, t) -> assertThat(t).isEqualTo(ContextType.LONG));
  }

  private static CedarExport export(String rule) {
    return CedarExporter.export(CedarTestSupport.lower("Module m.\n\n" + GuardShapeTest.GUARD + rule), "decide", "test");
  }

  private static String resource(String file) throws IOException {
    try (InputStream in = CedarExporterGoldenTest.class.getResourceAsStream("/cedar/" + file)) {
      if (in == null) throw new IOException("missing golden resource " + file);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
