package aster.core.typecheck.governance;

import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.Test;

import static aster.core.typecheck.governance.GovernanceTestSupport.diagnose;
import static aster.core.typecheck.governance.GovernanceTestSupport.has;
import static org.junit.jupiter.api.Assertions.*;

/** Verdict 内置模块的类型检查（ADR 0039 §2）。 */
class VerdictTypeCheckTest {

  @Test void produceVerdict不被当成类型参数() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(has(diags, ErrorCode.RETURN_TYPE_MISMATCH), diags.toString());
    assertFalse(diags.stream().anyMatch(x -> x.severity() == Diagnostic.Severity.ERROR), diags.toString());
  }

  @Test void produceBool返回Verdict报类型不匹配() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Bool:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.RETURN_TYPE_MISMATCH), diags.toString());
  }

  @Test void 两个分支类型不一致报错() {
    var diags = diagnose("@id(\"R-1\")\nRule main given x, produce Verdict:\n  If x equals to 1:\n    Return Verdict.allow().\n  Otherwise:\n    Return true.");
    // 两个引擎（Java/TS）都在 If 分支合并处报类型不一致（IF_BRANCH_MISMATCH），合并结果取 then 分支类型，
    // 因此函数级不一定再报 RETURN_TYPE_MISMATCH；本用例约束的是「必须报类型错误，Unknown 不得放行」。
    assertTrue(has(diags, ErrorCode.IF_BRANCH_MISMATCH) || has(diags, ErrorCode.RETURN_TYPE_MISMATCH), diags.toString());
  }

  @Test void deny缺参数报E703() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.deny().");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_CALL_ARITY), diags.toString());
  }

  @Test void allow多参数报E703() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow(\"x\").");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_CALL_ARITY), diags.toString());
  }

  @Test void deny非Text参数报类型不匹配() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.deny(42).");
    assertTrue(has(diags, ErrorCode.TYPE_MISMATCH), diags.toString());
  }

  @Test void requireApproval两个Text通过() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.require_approval(\"Senior Underwriter\", \"amount over cap\").");
    assertFalse(diags.stream().anyMatch(x -> x.severity() == Diagnostic.Severity.ERROR), diags.toString());
  }

  // ===== Verdict 不可当布尔用（ADR 0039 §2.2，评审 C1）=====

  @Test void If条件为Verdict报类型不匹配() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Text:\n  Let v be Verdict.deny(\"x\").\n  If v:\n    Return \"yes\".\n  Return \"no\".");
    assertTrue(has(diags, ErrorCode.TYPE_MISMATCH), diags.toString());
  }

  @Test void not作用于Verdict报类型不匹配() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Bool:\n  Let v be Verdict.deny(\"x\").\n  Return not v.");
    assertTrue(has(diags, ErrorCode.TYPE_MISMATCH), diags.toString());
  }

  @Test void Verdict与true相等比较报类型不匹配() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Bool:\n  Let v be Verdict.deny(\"x\").\n  Return v equals to true.");
    assertTrue(has(diags, ErrorCode.TYPE_MISMATCH), diags.toString());
  }

  // ===== Text 形参可作 reason/role（评审 I1）=====

  @Test void Text形参作reason通过() {
    var diags = diagnose("@id(\"R-1\")\nRule main given reason as Text, produce Verdict:\n  Return Verdict.deny(reason).");
    assertFalse(diags.stream().anyMatch(x -> x.severity() == Diagnostic.Severity.ERROR), diags.toString());
  }

  @Test void Text形参作role与reason通过() {
    var diags = diagnose("@id(\"R-1\")\nRule main given role as Text, reason as Text, produce Verdict:\n  Return Verdict.require_approval(role, reason).");
    assertFalse(diags.stream().anyMatch(x -> x.severity() == Diagnostic.Severity.ERROR), diags.toString());
  }

  @Test void 个数不符时仍检查参数内未定义名() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow(missing_name).");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_CALL_ARITY), diags.toString());
    assertTrue(has(diags, ErrorCode.UNDEFINED_VARIABLE), diags.toString());
  }

  // ===== Verdict 字段 outcome/role/reason 为 Text（评审 I2）=====

  @Test void 读取outcome得到Text() {
    var diags = diagnose("Rule main produce Text:\n  Let d be Verdict.deny(\"x\").\n  Return d.outcome.");
    assertFalse(diags.stream().anyMatch(x -> x.severity() == Diagnostic.Severity.ERROR), diags.toString());
  }

  @Test void Verdict字段类型为Text而非Unknown() {
    var diags = diagnose("Rule main produce Int:\n  Let d be Verdict.require_approval(\"r\", \"x\").\n  Return d.role.");
    assertTrue(has(diags, ErrorCode.RETURN_TYPE_MISMATCH), diags.toString());
  }
}
