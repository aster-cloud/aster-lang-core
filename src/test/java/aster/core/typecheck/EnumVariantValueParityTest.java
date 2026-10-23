package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 裸枚举变体作为值的 Java/TS 一致性（生产 E2E 2026-10-23 G2）。
 *
 * <p>TS 检查器查不到符号时会在模块枚举里找同名变体并取枚举类型；Java 侧此前直接报
 * UNDEFINED_VARIABLE，导致 aster-api 一旦接入 TypeChecker，所有 `status set to Rejected`
 * 这类合法策略都被拒绝。本测试锁定两侧一致：合法用法无诊断，类型不符仍报 FIELD_TYPE_MISMATCH。
 */
class EnumVariantValueParityTest {

  private static List<Diagnostic> check(String body) {
    String src = "Module probe.\n\n" + body + "\n";
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var ast = new AstBuilder().visitModule(parser.module());
    var core = new CoreLowering().lowerModule(ast);
    return new TypeChecker().typecheckModule(core);
  }

  private static final String DECLS =
      "Define DecisionStatus as one of Approved, Rejected, Review.\n"
      + "Define RiskTier as one of Low, Medium, High.\n"
      + "Define LoanDecision has status as DecisionStatus, reason as Text, riskTier as RiskTier.\n\n";

  @Test
  void bareVariantAssignedToMatchingEnumFieldIsAccepted() {
    var diags = check(DECLS
        + "Rule decide given score as Int, produce LoanDecision:\n"
        + "  If score less than 580\n"
        + "    Return LoanDecision with status set to Rejected, reason set to \"low\", riskTier set to High.\n"
        + "  Return LoanDecision with status set to Approved, reason set to \"ok\", riskTier set to Low.");
    assertTrue(diags.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
        "合法的裸枚举变体赋值不得产生错误，实际: " + diags);
  }

  @Test
  void bareVariantReturnedDirectlyTakesEnumType() {
    var diags = check(DECLS
        + "Rule tier given score as Int, produce RiskTier:\n"
        + "  If score >= 750\n"
        + "    Return Low.\n"
        + "  Return Medium.");
    assertTrue(diags.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
        "直接返回裸枚举变体应推断为枚举类型，实际: " + diags);
  }

  @Test
  void variantOfWrongEnumStillReportsFieldTypeMismatch() {
    var diags = check(DECLS
        + "Rule decide given score as Int, produce LoanDecision:\n"
        + "  Return LoanDecision with status set to High, reason set to \"x\", riskTier set to Low.");
    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.FIELD_TYPE_MISMATCH),
        "把 RiskTier 变体赋给 DecisionStatus 字段必须报 FIELD_TYPE_MISMATCH，实际: " + diags);
    assertFalse(diags.stream().anyMatch(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE),
        "已声明的变体不得再被当作未定义变量");
  }

  @Test
  void unknownFieldOnTypedParameterIsReported() {
    // 字段名拼错（creditScor）是运行时 "无法访问成员" 的静态来源；TS 报 E011，Java 此前对带点路径一律放行
    var diags = check("Define LoanApplication has creditScore as Int, industry as Text.\n"
        + DECLS
        + "Rule decide given application as LoanApplication, produce LoanDecision:\n"
        + "  If application.creditScor less than 580\n"
        + "    Return LoanDecision with status set to Rejected, reason set to \"low\", riskTier set to High.\n"
        + "  Return LoanDecision with status set to Approved, reason set to \"ok\", riskTier set to Low.");
    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.UNKNOWN_FIELD
            && d.message().contains("creditScor") && d.message().contains("LoanApplication")),
        "带点路径里的未知字段必须报 UNKNOWN_FIELD，实际: " + diags);
  }

  @Test
  void knownFieldPathTypesFlowThrough() {
    var diags = check("Define LoanApplication has creditScore as Int, industry as Text.\n"
        + DECLS
        + "Rule decide given application as LoanApplication, produce LoanDecision:\n"
        + "  If application.creditScore less than 580\n"
        + "    Return LoanDecision with status set to Rejected, reason set to application.industry, riskTier set to High.\n"
        + "  Return LoanDecision with status set to Approved, reason set to \"ok\", riskTier set to Low.");
    assertTrue(diags.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
        "合法字段路径不得产生错误，实际: " + diags);
  }

  @Test
  void unknownBareNameIsStillUndefinedVariable() {
    var diags = check(DECLS
        + "Rule decide given score as Int, produce LoanDecision:\n"
        + "  Return LoanDecision with status set to Rejected, reason set to \"x\", riskTier set to Extreme.");
    assertEquals(1, diags.stream().filter(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE).count(),
        "未声明的裸名仍需报一次 UNDEFINED_VARIABLE，实际: " + diags);
  }
}
