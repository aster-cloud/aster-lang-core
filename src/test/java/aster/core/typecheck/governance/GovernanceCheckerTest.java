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
    // 规则名不用 a/b：规范化器会删除冠词 a，导致 `Rule a produce …` 被解析成名为 produceVerdict 的规则
    var diags = diagnose("@id(\"R-1\")\nRule alpha produce Verdict:\n  Return Verdict.allow().\n\n@id(\"R-1\")\nRule beta produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_DUPLICATE_RULE_ID), diags.toString());
    var e701 = diags.stream().filter(x -> x.code() == ErrorCode.GOV_DUPLICATE_RULE_ID).toList();
    assertEquals(1, e701.size(), diags.toString());
    assertEquals("alpha, beta", e701.get(0).data().get("rules"));
  }

  @Test void 空白id报E702且仍报W700() {
    var diags = diagnose("@id(\"\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_ANNOTATION_ARG_INVALID), diags.toString());
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 同一规则多个id报E702() {
    var diags = diagnose("@id(\"A\")\n@id(\"B\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_ANNOTATION_ARG_INVALID), diags.toString());
  }

  @Test void reason非字符串报E702() {
    var diags = diagnose("@id(\"R-1\")\n@reason(42)\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_ANNOTATION_ARG_INVALID), diags.toString());
  }

  @Test void Scope内的Verdict返回也被识别() {
    // 手工构造 Func：body = Block[ Scope[ Return Verdict.deny("no") ] ]，无声明返回类型
    var name = new CoreModel.Name(); name.name = "Verdict.deny";
    var reason = new CoreModel.StringE(); reason.value = "no";
    var call = new CoreModel.Call(); call.target = name; call.args = List.of(reason);
    var ret = new CoreModel.Return(); ret.expr = call;
    var scope = new CoreModel.Scope(); scope.statements = List.of(ret);
    var body = new CoreModel.Block(); body.statements = List.of(scope);
    var f = new CoreModel.Func(); f.name = "main"; f.body = body;
    assertTrue(GovernanceChecker.returnsVerdict(f));
  }

  @Test void 注解名为null不抛异常() {
    var a = new CoreModel.Annotation();
    var f = new CoreModel.Func(); f.name = "main"; f.annotations = List.of(a);
    var m = new CoreModel.Module(); m.decls = List.of(f);
    assertDoesNotThrow(() -> new GovernanceChecker().checkModule(m));
    assertEquals(Optional.empty(), GovernanceChecker.ruleId(f));
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

  @Test void 已登记控制点无W704() {
    var diags = diagnose("@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(has(diags, ErrorCode.GOV_CONTROL_UNREGISTERED), diags.toString());
  }

  @Test void 未登记与形态非法都报W704且同键只报一次() {
    var diags = diagnose("@id(\"R-1\")\n@control(\"ACME:ART1\")\n@control(\"ACME:ART1\")\n@control(\"eu_ai_act:art14\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertEquals(2, GovernanceTestSupport.count(diags, ErrorCode.GOV_CONTROL_UNREGISTERED), diags.toString());
  }

  @Test void 注入空注册表时全部报W704() {
    var empty = new ControlRegistry("0.0.0", java.util.Set.of());
    var core = GovernanceTestSupport.lower("@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    var diags = new GovernanceChecker(empty).checkModule(core);
    assertTrue(has(diags, ErrorCode.GOV_CONTROL_UNREGISTERED), diags.toString());
  }

  /** 与 aster-lang-test tier3 type-checker/governance_control_unregistered.aster 同源（去掉 Module 行），两侧守同一组码（ADR 0045 §3）。 */
  @Test void 诊断黄金同源用例恰好一条W704() {
    var diags = diagnose("""
        @id("GC-001")
        @control("EU_AI_ACT:ART14")
        @control("ACME:ART1")
        Rule decide given amount, produce Verdict:
          If amount at least 100:
            Return Verdict.require_approval("Reviewer", "large").
          Return Verdict.allow().""");
    var w704 = diags.stream().filter(d -> d.code() == ErrorCode.GOV_CONTROL_UNREGISTERED).toList();
    assertEquals(1, w704.size(), diags.toString());
    assertEquals("Control 'ACME:ART1' on rule 'decide' is not registered (controls registry 1.1.0)", w704.get(0).message());
  }
}
