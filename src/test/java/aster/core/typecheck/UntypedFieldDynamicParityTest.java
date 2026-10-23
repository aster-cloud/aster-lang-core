package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 未声明类型的 data 字段在成员访问时按动态类型处理（与 aster-lang-ts #227 对齐）。
 *
 * <p>Action Guard 的 `context` 按设计是无类型 map（ADR 0040 §2.2），`req.context.amount` 必须通过
 * 类型检查；显式声明类型的字段仍保留 E011；标记不进 IR JSON，读回后退回按 type 检查（两侧同样的已知局限）。
 */
class UntypedFieldDynamicParityTest {

  private static CoreModel.Module lower(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    return new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
  }

  private static List<Diagnostic> check(String src) {
    return new TypeChecker().typecheckModule(lower(src));
  }

  private static List<Diagnostic> errors(List<Diagnostic> diags) {
    return diags.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
  }

  private static final String GUARD_SAMPLE = """
      Module guard.cedar.sample.

      Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.

      @id("GCS-001")
      @control("EU_AI_ACT:ART14")
      Rule decide given req as GuardRequest, produce Verdict:
        Let large be req.context.amount at least 10000.
        When req.action equals to "delete_customer_record", require approval by "Data Protection Officer" because "customer record deletion".
        If List.contains(req.principal_roles, "suspended"):
          Return Verdict.deny("suspended principal").
        If large and req.action equals to "refund":
          Return Verdict.escalate("large refund").
        Otherwise allow.
      """;

  @Test
  void guardCedarSampleHasNoErrors() {
    var diags = errors(check(GUARD_SAMPLE));
    assertTrue(diags.isEmpty(), "guard 样本不得有错误级诊断，实际: " + diags);
  }

  @Test
  void typedRecordUnknownFieldStillReported() {
    var diags = check("Module m.\n\nDefine Req has action as Text, amount as Int.\n\n"
        + "Rule decide given req as Req, produce Bool:\n  Return req.nickname equals to \"x\".");
    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.UNKNOWN_FIELD), "显式类型记录的未知字段仍报 E011，实际: " + diags);
  }

  @Test
  void explicitTextFieldMemberAccessStillReported() {
    var diags = check("Module m.\n\nDefine Req has context as Text.\n\n"
        + "Rule decide given req as Req, produce Bool:\n  Return req.context.amount at least 10000.");
    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.UNKNOWN_FIELD), "显式 Text 字段上的成员访问仍报 E011，实际: " + diags);
  }

  @Test
  void untypedFieldAsIntReturnIsNotMismatch() {
    var diags = errors(check("Module m.\n\nDefine Req has action, context.\n\n"
        + "Rule decide given req as Req, produce Int:\n  Return req.action."));
    assertTrue(diags.isEmpty(), "无类型字段作为 Int 返回值不报 E003，实际: " + diags);
  }

  @Test
  void untypedMemberAsConditionIsAccepted() {
    var diags = errors(check("Module m.\n\nDefine Req has action, context.\n\n"
        + "Rule decide given req as Req, produce Bool:\n  If req.context.flag:\n    Return true.\n  Return false."));
    assertTrue(diags.isEmpty(), "无类型字段的成员访问作为 If 条件不报错，实际: " + diags);
  }

  @Test
  void markerStaysOutOfIrJsonAndRoundTripFallsBackToDeclaredType() throws Exception {
    var module = lower(GUARD_SAMPLE);
    var mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(module);
    assertFalse(json.contains("typeInferred"), "标记不得进入 IR JSON");
    var back = mapper.readValue(json, CoreModel.Module.class);
    var diags = new TypeChecker().typecheckModule(back);
    // 读回后标记丢失，context 按推断类型 Text 检查 → E011（与 TS 相同的已知局限）
    assertEquals(1L, diags.stream().filter(d -> d.code() == ErrorCode.UNKNOWN_FIELD).count(), "实际: " + diags);
  }
}
