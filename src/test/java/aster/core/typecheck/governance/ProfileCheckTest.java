package aster.core.typecheck.governance;

import aster.core.typecheck.ErrorCode;
import org.junit.jupiter.api.Test;

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
    assertTrue(messages(diags, ErrorCode.GOV_PROFILE_UNKNOWN).contains("unknown profile 'nope' (controls registry 1.1.0)"));
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
}
