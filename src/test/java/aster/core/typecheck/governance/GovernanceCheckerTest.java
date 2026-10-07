package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static aster.core.typecheck.governance.GovernanceTestSupport.diagnose;
import static aster.core.typecheck.governance.GovernanceTestSupport.has;
import static org.junit.jupiter.api.Assertions.*;

/** 治理检查器：规则元数据注解与 Verdict 规则身份（ADR 0039 §3/§4）。 */
class GovernanceCheckerTest {

  @Test void 返回Verdict且无id报W700() {
    var diags = diagnose("Rule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 未声明返回类型但返回Verdict调用也报W700() {
    var diags = diagnose("Rule main produce:\n  Return Verdict.deny(\"no\").");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 有id不报W700() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 返回Bool无id不报W700() {
    var diags = diagnose("Rule main produce Bool:\n  Return true.");
    assertFalse(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 重复id报E701() {
    var diags = diagnose("@id(\"R-1\")\nRule a produce Verdict:\n  Return Verdict.allow().\n\n@id(\"R-1\")\nRule b produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_DUPLICATE_RULE_ID), diags.toString());
  }

  @Test void id非字符串报E702() {
    var diags = diagnose("@id(42)\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_ANNOTATION_ARG_INVALID), diags.toString());
  }

  @Test void id命名参数也报E702() {
    var diags = diagnose("@id(value: \"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_ANNOTATION_ARG_INVALID), diags.toString());
  }

  @Test void control可重复() {
    var diags = diagnose("@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\n@control(\"GDPR:ART6\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(diags.stream().anyMatch(x -> x.code().category() == ErrorCode.Category.GOVERNANCE), diags.toString());
  }

  @Test void 用户定义Verdict类型报重复符号() {
    var diags = diagnose("Define Verdict has approved.\n\nRule main produce Bool:\n  Return true.");
    assertTrue(has(diags, ErrorCode.DUPLICATE_SYMBOL), diags.toString());
  }

  @Test void 静态工具读取元数据() {
    // 直接构造 Func：annotations = [id("R-9"), control("A"), control("B")]
    var f = new CoreModel.Func();
    var id = new CoreModel.Annotation(); id.name = "id"; id.params = Map.of("$0", "R-9");
    var c1 = new CoreModel.Annotation(); c1.name = "control"; c1.params = Map.of("$0", "A");
    var c2 = new CoreModel.Annotation(); c2.name = "control"; c2.params = Map.of("$0", "B");
    f.annotations = List.of(id, c1, c2);
    assertEquals(Optional.of("R-9"), GovernanceChecker.ruleId(f));
    assertEquals(List.of("A", "B"), GovernanceChecker.controls(f));
  }
}
