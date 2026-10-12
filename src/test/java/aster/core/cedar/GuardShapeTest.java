package aster.core.cedar;

import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardShapeTest {

  static final String GUARD = "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.\n\n";

  @Test
  void acceptsGuardShape() {
    var m = CedarTestSupport.lower("Module m.\n\n" + GUARD
        + "@id(\"R-1\")\nRule decide given req as GuardRequest, produce Verdict:\n  Return Verdict.allow().\n");
    var out = new ArrayList<Diagnostic>();
    assertThat(GuardShape.check(m, CedarTestSupport.func(m, "decide"), out)).contains("req");
    assertThat(out).isEmpty();
  }

  @Test
  void rejectsMissingId() {
    assertE720("Rule decide given req as GuardRequest, produce Verdict:\n  Return Verdict.allow().\n", "missing @id");
  }

  @Test
  void rejectsWrongFields() {
    var m = CedarTestSupport.lower("Module m.\n\nDefine Other has a, b.\n\n"
        + "@id(\"R-1\")\nRule decide given req as Other, produce Verdict:\n  Return Verdict.allow().\n");
    assertSingleE720(m, "parameter type Other is not the guard request shape");
  }

  @Test
  void rejectsNonVerdict() {
    assertE720("@id(\"R-1\")\nRule decide given req as GuardRequest, produce Bool:\n  Return true.\n", "result is not Verdict");
  }

  @Test
  void rejectsTwoParams() {
    assertE720("@id(\"R-1\")\nRule decide given req as GuardRequest, x as Int, produce Verdict:\n  Return Verdict.allow().\n",
        "expects exactly one parameter");
  }

  @Test
  void rejectsUndeclaredParamType() {
    var m = CedarTestSupport.lower("Module m.\n\n"
        + "@id(\"R-1\")\nRule decide given req as Missing, produce Verdict:\n  Return Verdict.allow().\n");
    assertSingleE720(m, "parameter type Missing is not the guard request shape");
  }

  private static void assertE720(String body, String reason) {
    assertSingleE720(CedarTestSupport.lower("Module m.\n\n" + GUARD + body), reason);
  }

  private static void assertSingleE720(aster.core.ir.CoreModel.Module m, String reason) {
    var fn = CedarTestSupport.func(m, "decide");
    List<Diagnostic> out = new ArrayList<>();
    assertThat(GuardShape.check(m, fn, out)).isEmpty();
    assertThat(out).hasSize(1);
    Diagnostic d = out.get(0);
    assertThat(d.code()).isEqualTo(ErrorCode.CEDAR_NOT_EXPORTABLE_SHAPE);
    assertThat(d.severity()).isEqualTo(Diagnostic.Severity.ERROR);
    assertThat(d.data()).containsEntry("reason", reason).containsEntry("rule", "decide");
    assertThat(d.message()).isEqualTo("Rule 'decide' cannot be exported to Cedar: " + reason);
    assertThat(d.span()).isEqualTo(java.util.Optional.ofNullable(fn.origin));
  }
}
