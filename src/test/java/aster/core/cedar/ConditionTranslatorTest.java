package aster.core.cedar;

import aster.core.ir.CoreModel;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 单个条件的 T/F 翻译（ADR 0047 §3 缺失键语义）与不可翻译构造的诊断。 */
class ConditionTranslatorTest {

  private static final String HEAD = "Module m.\n\n" + GuardShapeTest.GUARD
      + "@id(\"R-1\")\nRule decide given req as GuardRequest, produce Verdict:\n";

  @Test void actionEquality() {
    var c = translate("req.action equals to \"delete\"");
    assertThat(c.t()).isEqualTo("(action == Aster::Action::\"delete\")");
    assertThat(c.f()).isEqualTo("!(action == Aster::Action::\"delete\")");
  }

  @Test void contextComparisonGuardsHas() {
    var c = translate("req.context.amount at least 1000");
    assertThat(c.t()).isEqualTo("(context has amount && (-9007199254740991 <= context.amount && context.amount <= 9007199254740991) && (context.amount >= 1000))");
    assertThat(c.f()).isEqualTo("(context has amount && (-9007199254740991 <= context.amount && context.amount <= 9007199254740991) && !(context.amount >= 1000))");
  }

  @Test void andShortCircuit() {
    var c = translate("req.context.a at least 1 and req.context.b at least 2");
    assertThat(c.t()).isEqualTo("((context has a && (-9007199254740991 <= context.a && context.a <= 9007199254740991) && (context.a >= 1)) && (context has b && (-9007199254740991 <= context.b && context.b <= 9007199254740991) && (context.b >= 2)))");
    assertThat(c.f()).isEqualTo("((context has a && (-9007199254740991 <= context.a && context.a <= 9007199254740991) && !(context.a >= 1)) || ((context has a && (-9007199254740991 <= context.a && context.a <= 9007199254740991) && (context.a >= 1)) && (context has b && (-9007199254740991 <= context.b && context.b <= 9007199254740991) && !(context.b >= 2))))");
  }

  @Test void orShortCircuit() {
    var c = translate("req.context.a at least 1 or req.context.b at least 2");
    assertThat(c.t()).isEqualTo("((context has a && (-9007199254740991 <= context.a && context.a <= 9007199254740991) && (context.a >= 1)) || ((context has a && (-9007199254740991 <= context.a && context.a <= 9007199254740991) && !(context.a >= 1)) && (context has b && (-9007199254740991 <= context.b && context.b <= 9007199254740991) && (context.b >= 2))))");
    assertThat(c.f()).isEqualTo("((context has a && (-9007199254740991 <= context.a && context.a <= 9007199254740991) && !(context.a >= 1)) && (context has b && (-9007199254740991 <= context.b && context.b <= 9007199254740991) && !(context.b >= 2)))");
  }

  @Test void notSwapsTAndF() {
    var c = translate("not req.context.vip");
    assertThat(c.t()).isEqualTo("(context has vip && !context.vip)");
    assertThat(c.f()).isEqualTo("(context has vip && context.vip)");
  }

  @Test void rolesContains() {
    assertThat(translate("List.contains(req.principal_roles, \"ops\")").t()).isEqualTo("(principal.roles.contains(\"ops\"))");
  }

  @Test void principalAndResourceAtoms() {
    assertThat(translate("req.principal_id equals to \"p\"").t()).isEqualTo("(principal == Aster::Principal::\"p\")");
    assertThat(translate("req.resource_id not equal to \"r\"").t()).isEqualTo("!(resource == Aster::Resource::\"r\")");
    assertThat(translate("req.principal_type equals to \"agent\"").t()).isEqualTo("(principal.type == \"agent\")");
    assertThat(translate("\"doc\" equals to req.resource_type").t()).isEqualTo("(resource.type == \"doc\")");
  }

  @Test void agentModelNullSafe() {
    var c = translate("req.agent_model not equal to \"m\"");
    assertThat(c.t()).isEqualTo("!(context has agent_model && context.agent_model == \"m\")");
    assertThat(c.f()).isEqualTo("(context has agent_model && context.agent_model == \"m\")");
  }

  @Test void integerArithmeticGuardsEveryKey() {
    var c = translate("req.context.a plus req.context.b times 2 less than 10");
    assertThat(c.t()).isEqualTo("(context has a && context has b && (-9007199254740991 <= (context.a + (context.b * 2)) && (context.a + (context.b * 2)) <= 9007199254740991) && ((context.a + (context.b * 2)) < 10))");
    assertThat(c.f()).isEqualTo("(context has a && context has b && (-9007199254740991 <= (context.a + (context.b * 2)) && (context.a + (context.b * 2)) <= 9007199254740991) && !((context.a + (context.b * 2)) < 10))");
  }

  @Test void textEqualityOnContext() {
    assertThat(translate("req.context.tier equals to \"gold\"").t()).isEqualTo("(context has tier && (context.tier == \"gold\"))");
  }

  @Test void typedContextKeysMayBeCompared() {
    var c = translate("req.context.a at least 1 and req.context.a less than req.context.b and req.context.b at least 0");
    assertThat(c.t()).contains("(context has a && context has b && (-9007199254740991 <= context.a && context.a <= 9007199254740991) && (-9007199254740991 <= context.b && context.b <= 9007199254740991) && (context.a < context.b))");
  }

  @Test void longComparisonGuardsSafeIntegerRange() {
    var c = translate("req.context.n greater than 5");
    assertThat(c.t()).isEqualTo("(context has n && (-9007199254740991 <= context.n && context.n <= 9007199254740991) && (context.n > 5))");
    assertThat(c.f()).isEqualTo("(context has n && (-9007199254740991 <= context.n && context.n <= 9007199254740991) && !(context.n > 5))");
  }

  @Test void textAndBoolComparisonsNeedNoRangeGuard() {
    assertThat(translate("req.context.vip equals to true").t()).isEqualTo("(context has vip && (context.vip == true))");
  }

  @Test void literalOnlyArithmeticIsRangeGuarded() {
    var exp = CedarExporter.export(CedarTestSupport.lower(HEAD
        + "  Let x be 9007199254740991L plus 1.\n  Let y be 9007199254740991L plus 2.\n"
        + "  If x equals to y:\n    Return Verdict.deny(\"collapsed\").\n  Return Verdict.allow().\n"), "decide", "test");
    String guards = "(-9007199254740991 <= (9007199254740991 + 1) && (9007199254740991 + 1) <= 9007199254740991)"
        + " && (-9007199254740991 <= (9007199254740991 + 2) && (9007199254740991 + 2) <= 9007199254740991)";
    assertThat(exp.diagnostics()).isEmpty();
    assertThat(exp.emitted().get(0).condition()).isEqualTo("(" + guards + " && ((9007199254740991 + 1) == (9007199254740991 + 2)))");
    assertThat(exp.emitted().get(1).condition()).isEqualTo("(" + guards + " && !((9007199254740991 + 1) == (9007199254740991 + 2)))");
  }

  @Test void literalComparisonStaysBare() {
    assertThat(translate("1 less than 2").t()).isEqualTo("(1 < 2)");
  }

  @Test void bigLiteralRejected()        { assertE721("req.context.n at least 9007199254740992L", "integer literal outside ±2^53"); }
  @Test void negativeBigLiteralRejected() { assertE721("req.context.n at least 0L minus 9007199254740993L", "integer literal outside ±2^53"); }
  @Test void largestSafeLiteralAccepted() {
    assertThat(translate("req.context.n at least 9007199254740991L").t()).endsWith("&& (context.n >= 9007199254740991))");
  }
  @Test void textOrderingRejected()      { assertE721("req.context.name less than \"b\"", "ordering on text"); }
  @Test void conflictingTypesRejected()  { assertE722("req.context.k at least 1 or req.context.k equals to \"x\"", "k", "number", "text"); }
  @Test void otherCallRejected()         { assertE721("Text.length(req.action) at least 1", "call to 'Text.length'"); }
  @Test void nestedContextRejected()     { assertE721("req.context.a.b at least 1", "nested context key 'a.b'"); }
  @Test void divisionRejected()          { assertE721("req.context.a divided by 2 at least 1", "division"); }
  @Test void decimalRejected()           { assertE721("req.context.a at least 1.5", "decimal value"); }
  @Test void untypedKeyComparisonRejected() { assertE721("req.context.a equals to req.context.b", "comparison between untyped context keys"); }
  @Test void unknownReferenceRejected()  { assertE721("other.flag", "reference 'other.flag'"); }

  private static Cond translate(String cond) {
    List<Diagnostic> out = new ArrayList<>();
    Cond c = run(cond, out);
    assertThat(out).isEmpty();
    return c;
  }

  private static Cond run(String cond, List<Diagnostic> out) {
    var m = CedarTestSupport.lower(HEAD + "  If " + cond + ":\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n");
    var fn = CedarTestSupport.func(m, "decide");
    var expr = ((CoreModel.If) fn.body.statements.get(0)).cond;
    return ConditionTranslator.translateAlone("req", fn.name, expr, new RuleFacts(), out);
  }

  private static void assertE721(String cond, String construct) {
    List<Diagnostic> out = new ArrayList<>();
    run(cond, out);
    assertThat(out).extracting(d -> d.code().code()).containsExactly("E721");
    assertThat(out.get(0).data()).containsEntry("construct", construct).containsEntry("rule", "decide");
  }

  private static void assertE722(String cond, String key, String first, String second) {
    List<Diagnostic> out = new ArrayList<>();
    run(cond, out);
    assertThat(out).extracting(d -> d.code().code()).containsExactly("E722");
    assertThat(out.get(0).data()).containsEntry("key", key).containsEntry("first", first).containsEntry("second", second);
  }
}
