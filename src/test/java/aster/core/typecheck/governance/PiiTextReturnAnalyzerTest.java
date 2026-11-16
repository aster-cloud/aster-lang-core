package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static aster.core.typecheck.governance.GovernanceTestSupport.count;
import static aster.core.typecheck.governance.GovernanceTestSupport.diagnose;
import static aster.core.typecheck.governance.GovernanceTestSupport.messages;
import static org.assertj.core.api.Assertions.assertThat;

/** W709：@pii 输入字段流入返回的 Text（aster-cloud#772 方案 3）。逐例行为由共享夹具守护，这里只验接线与文案。 */
class PiiTextReturnAnalyzerTest {
  private static final String APPLICANT =
      "Define Applicant has name as @pii(L2, name) Text, city as Text.\n\n";

  @Test void 完整类型检查通道给出W709警告且文案含字段与规则() {
    List<Diagnostic> diags = diagnose(APPLICANT
        + "Rule greet given applicant as Applicant, produce Text:\n  Return \"Hello, \" + applicant.name.");
    assertThat(count(diags, ErrorCode.GOV_PII_RETURNED_AS_TEXT)).isEqualTo(1);
    Diagnostic d = diags.stream().filter(x -> x.code() == ErrorCode.GOV_PII_RETURNED_AS_TEXT).findFirst().orElseThrow();
    assertThat(d.severity()).isEqualTo(Diagnostic.Severity.WARNING);
    assertThat(d.code().code()).isEqualTo("W709");
    assertThat(d.message()).isEqualTo(
        "Rule 'greet' returns Text built from @pii field 'applicant.name'; the execution output is stored and shown unmasked");
    assertThat(d.help()).isPresent();
  }

  @Test void 非PII字段不报() {
    List<Diagnostic> diags = diagnose(APPLICANT
        + "Rule where given applicant as Applicant, produce Text:\n  Return \"City: \" + applicant.city.");
    assertThat(messages(diags, ErrorCode.GOV_PII_RETURNED_AS_TEXT)).isEmpty();
  }

  @Test void 分析器只看声明类型_IR无标注时不报() {
    // 同一结构但字段未标注 @pii：分析器不按字段名猜测
    CoreModel.Module core = GovernanceTestSupport.lower(
        "Define Applicant has name as Text.\n\nRule greet given applicant as Applicant, produce Text:\n  Return applicant.name.");
    CoreModel.Func greet = core.decls.stream().filter(CoreModel.Func.class::isInstance).map(CoreModel.Func.class::cast)
        .findFirst().orElseThrow();
    assertThat(PiiTextReturnAnalyzer.analyze(core, greet)).isEmpty();
  }

  @Test void 无函数体或空模块不抛错() {
    CoreModel.Func f = new CoreModel.Func();
    f.name = "empty";
    CoreModel.Module m = new CoreModel.Module();
    m.decls = List.of(f);
    assertThat(PiiTextReturnAnalyzer.analyze(m, f)).isEmpty();
  }
}
