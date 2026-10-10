package aster.core.typecheck.governance;

import aster.core.typecheck.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运算符调用（lower 后为 Call{Name(op)}）不得被当作未定义变量。 */
class OperatorCallTypeCheckTest {

  private static String rule(String type, String body) {
    return "Rule decide given amount as Int, produce " + type + ":\n  " + body + "\n";
  }

  @ParameterizedTest(name = "{1}")
  @CsvSource(delimiter = '|', value = {
    "Bool|Return amount at least 100.",
    "Bool|Return amount > 100.",
    "Bool|Return amount < 100.",
    "Bool|Return amount at most 100.",
    "Bool|Return amount equals to 100.",
    "Int|Return amount + 1.",
    "Int|Return amount - 1.",
    "Int|Return amount * 2.",
    "Int|Return amount divided by 2.",
    "Bool|Return amount at least 1 and amount at most 9.",
    "Bool|Return amount at least 1 or amount at most 9.",
    "Bool|Return not (amount at least 1).",
    "Bool|Return =(amount, 100).",
    "Bool|Return >=(amount, 100).",
    "Int|Return +(amount, 1)."
  })
  void operatorCallsProduceNoDiagnostics(String type, String body) {
    var diags = GovernanceTestSupport.diagnose(rule(type, body));
    assertTrue(diags.isEmpty(), () -> "应无诊断，实际: " + diags);
  }

  @Test
  void undefinedOperandReportedButNotOperator() {
    var diags = GovernanceTestSupport.diagnose(rule("Bool", "Return missing at least 1."));
    var undefined = diags.stream().filter(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE).toList();
    assertEquals(1, undefined.size(), () -> "诊断: " + diags);
    assertEquals("missing", undefined.get(0).data().get("name"));
  }
}
