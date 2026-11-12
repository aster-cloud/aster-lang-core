package aster.core.typecheck.governance;

import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static aster.core.typecheck.governance.GovernanceTestSupport.count;
import static aster.core.typecheck.governance.GovernanceTestSupport.diagnoseSource;
import static aster.core.typecheck.governance.GovernanceTestSupport.messages;
import static aster.core.typecheck.governance.GovernanceTestSupport.profiled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR 0046 §4：档案检查。 */
class ProfileCheckTest {
  @Test void 未知档案报E705() {
    var diags = diagnoseSource("Module m.\nProfile \"nope\".\n\nRule r produce Int:\n  Return 1.\n");
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_UNKNOWN), diags.toString());
    assertTrue(messages(diags, ErrorCode.GOV_PROFILE_UNKNOWN).contains("unknown profile 'nope' (controls registry 1.2.0)"));
  }

  @Test void 缺id报E706且W700照常() {
    var diags = diagnoseSource(profiled("governed", "Rule r produce Verdict:\n  Return Verdict.allow()."));
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_VIOLATION), diags.toString());
    assertEquals(1, count(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID));
    assertTrue(messages(diags, ErrorCode.GOV_PROFILE_VIOLATION).contains("Rule 'r' violates profile 'governed': missing @id"));
    var e706 = diags.stream().filter(d -> d.code() == ErrorCode.GOV_PROFILE_VIOLATION).findFirst().orElseThrow();
    assertTrue(e706.span().isPresent(), "E706 应定位到规则");
  }

  @Test void 未登记控制点报E706() {
    var diags = diagnoseSource(profiled("governed",
        "@id(\"R-1\")\n@control(\"ACME:ART1\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    assertTrue(messages(diags, ErrorCode.GOV_PROFILE_VIOLATION).contains("unregistered control ACME:ART1"), diags.toString());
    assertEquals(1, count(diags, ErrorCode.GOV_CONTROL_UNREGISTERED));
  }

  @Test void 重复控制点每条要求只报一次() {
    var diags = diagnoseSource(profiled("governed",
        "@id(\"R-1\")\n@control(\"ACME:ART1\")\n@control(\"ACME:ART1\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_VIOLATION), diags.toString());
  }

  @Test void 缺所需框架控制点报E706() {
    var diags = diagnoseSource(profiled("eu-ai-act-high-risk",
        "@id(\"R-1\")\n@control(\"GDPR:ART6\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    assertTrue(messages(diags, ErrorCode.GOV_PROFILE_VIOLATION).contains("no registered control from framework EU_AI_ACT"),
        diags.toString());
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_VIOLATION));
  }

  @Test void 合规规则零E70x() {
    var diags = diagnoseSource(profiled("eu-ai-act-high-risk",
        "@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_VIOLATION) + count(diags, ErrorCode.GOV_PROFILE_UNKNOWN),
        diags.toString());
  }

  @Test void 非Verdict规则不受档案约束() {
    var diags = diagnoseSource(profiled("governed", "Rule helper produce Int:\n  Return 1."));
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_VIOLATION), diags.toString());
  }

  @Test void 未声明档案的模块零E70x() {
    var diags = diagnoseSource("Module m.\n\nRule r produce Verdict:\n  Return Verdict.allow().\n");
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_VIOLATION) + count(diags, ErrorCode.GOV_PROFILE_UNKNOWN));
  }

  @Test void 未知档案下缺id的Verdict规则只报E705与W700() {
    var diags = diagnoseSource("Module m.\nProfile \"nope\".\n\nRule r produce Verdict:\n  Return Verdict.allow().\n");
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_UNKNOWN), diags.toString());
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_VIOLATION), diags.toString());
    assertEquals(1, count(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 高风险档案下未登记的同框架键同时违反两条要求() {
    var diags = diagnoseSource(profiled("eu-ai-act-high-risk",
        "@id(\"R-1\")\n@control(\"EU_AI_ACT:ART99\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    var e706 = messages(diags, ErrorCode.GOV_PROFILE_VIOLATION);
    assertTrue(e706.contains("unregistered control EU_AI_ACT:ART99"), diags.toString());
    assertTrue(e706.contains("no registered control from framework EU_AI_ACT"), diags.toString());
    assertEquals(1, count(diags, ErrorCode.GOV_CONTROL_UNREGISTERED), diags.toString());
  }
  // ---- ADR 0046 §11 D2 第 2 条：E708（注入要求 verifiedInputs 的注册表） ----

  private static ControlRegistry registry(boolean verifiedInputs) {
    return new ControlRegistry("t", Set.of("EU_AI_ACT:ART14"), Map.of("EU_AI_ACT:ART14", "EU_AI_ACT"),
        Map.of("hr", new ControlRegistry.Profile("hr", true, true, List.of("EU_AI_ACT"), verifiedInputs)));
  }

  private static final String DYNAMIC_RULE = "Define Req has action as Text, meta as Map Text to Text.\n\n"
      + "@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\nRule decide given req as Req, produce Verdict:\n"
      + "  If Map.get(req.meta, req.action) equals to \"x\":\n    Return Verdict.deny(\"d\").\n"
      + "  Return Verdict.allow().\n\n"
      + "@id(\"R-2\")\n@control(\"EU_AI_ACT:ART14\")\nRule fine given req as Req, produce Verdict:\n"
      + "  If req.action equals to \"x\":\n    Return Verdict.deny(\"d\").\n  Return Verdict.allow().";

  private static List<Diagnostic> check(String header, boolean verifiedInputs) {
    var core = GovernanceTestSupport.resolveSource("Module m.\n" + header + "\n" + DYNAMIC_RULE + "\n", null, null);
    return new GovernanceChecker(registry(verifiedInputs)).checkModule(core);
  }

  @Test void 要求verifiedInputs时动态键规则报E708() {
    var diags = check("Profile \"hr\".\n", true);
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE), diags.toString());
    var e708 = diags.stream().filter(d -> d.code() == ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE).findFirst().orElseThrow();
    assertEquals(Map.of("rule", "decide", "profile", "hr", "construct", "dynamic-map-key"), e708.data());
    assertEquals("Rule 'decide' reads inputs that cannot be resolved statically (dynamic-map-key); "
        + "profile 'hr' requires verified inputs", e708.message());
    assertEquals(Diagnostic.Severity.ERROR, e708.severity());
    assertTrue(e708.span().isPresent(), "E708 应定位到规则");
  }

  @Test void 未声明档案不报E708() {
    assertEquals(0, count(check("", true), ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE));
  }

  @Test void 未知档案只报E705不报E708() {
    var diags = check("Profile \"nope\".\n", true);
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_UNKNOWN), diags.toString());
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE));
  }

  @Test void 档案不要求verifiedInputs时不报E708() {
    assertEquals(0, count(check("Profile \"hr\".\n", false), ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE));
  }

  @Test void 默认注册表下高风险档案不报E708() {
    var core = GovernanceTestSupport.resolveSource(
        "Module m.\nProfile \"eu-ai-act-high-risk\".\n\n" + DYNAMIC_RULE + "\n", null, null);
    assertEquals(0, count(new GovernanceChecker().checkModule(core), ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE));
  }
}
