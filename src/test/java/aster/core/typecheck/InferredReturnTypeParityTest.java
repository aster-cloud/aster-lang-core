package aster.core.typecheck;

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
 * 省略返回类型（`produce:`）时的 Java/TS 一致性（生产 E2E 2026-10-23 G2）。
 *
 * <p>TS 用 retTypeInferred 跳过返回类型比对；Java 的 AstBuilder 则按函数名猜一个占位类型，
 * 此前类型检查仍拿占位类型比对，对返回记录的合法规则报假 RETURN_TYPE_MISMATCH。
 */
class InferredReturnTypeParityTest {

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
      "Define Decision has approved as Bool, reason as Text.\n\n";

  @Test
  void omittedProduceTypeDoesNotReportReturnMismatch() {
    var diags = check(DECLS
        + "Rule decide given score as Int, produce:\n"
        + "  If score less than 580\n"
        + "    Return Decision with approved set to false, reason set to \"low\".\n"
        + "  Return Decision with approved set to true, reason set to \"ok\".");
    assertFalse(diags.stream().anyMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH),
        "省略返回类型时不得报 RETURN_TYPE_MISMATCH，实际: " + diags);
  }

  @Test
  void callerSeesInferredReturnTypeOfUntypedRule() {
    // TS 把函数体推断出的返回类型登记进签名；此前 Java 登记的是按函数名猜的 Text，
    // 调用方 `Return mk(x).` 会得到假 E003 "expected Decision, got Text"
    var diags = check(DECLS
        + "Rule mk given x as Int, produce:\n"
        + "  Return Decision with approved set to true, reason set to \"ok\".\n\n"
        + "Rule wrap given x as Int, produce Decision:\n"
        + "  Return mk(x).");
    assertFalse(diags.stream().anyMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH),
        "调用省略返回类型的规则不得报 RETURN_TYPE_MISMATCH，实际: " + diags);
  }

  @Test
  void callerSeesFieldPathAndArithmeticAndVerdictInferredReturns() {
    // Name（按字段类型表）、算术、Verdict.* 三类返回表达式都要进签名，调用方按推断类型检查
    var src = "Define Loan has amount as Int, note as Text.\n\n"
        + "Rule amountOf given loan as Loan, produce:\n  Return loan.amount.\n\n"
        + "Rule doubled given loan as Loan, produce:\n  Return loan.amount times 2.\n\n"
        + "Rule gate given loan as Loan, produce:\n  Return Verdict.allow().\n\n"
        + "Rule useAmount given loan as Loan, produce Int:\n  Return amountOf(loan).\n\n"
        + "Rule useDoubled given loan as Loan, produce Int:\n  Return doubled(loan).\n\n"
        + "Rule useGate given loan as Loan, produce Verdict:\n  Return gate(loan).";
    var diags = check(src);
    assertFalse(diags.stream().anyMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH),
        "推断返回类型应覆盖字段路径/算术/Verdict 调用，实际: " + diags);
  }

  @Test
  void conflictingTopLevelReturnsFallBackToPlaceholderWithoutFalseError() {
    // 顶层 Return 类型不一致 → 推断 Unknown → 退回占位，但 retTypeInferred 仍跳过本函数的返回比对
    var diags = check(DECLS
        + "Rule odd given x as Int, produce:\n"
        + "  If x less than 0\n"
        + "    Return \"neg\".\n"
        + "  Return 1.");
    assertFalse(diags.stream().anyMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH),
        "省略返回类型的函数自身不报 RETURN_TYPE_MISMATCH，实际: " + diags);
  }

  @Test
  void irJsonRoundTripPreservesInferredSignatureBehaviour() throws Exception {
    // 落库的 IR JSON（含 retTypeInferred:true）读回后，签名推断与直接检查结果一致
    String src = "Module probe.\n\n" + DECLS
        + "Rule mk given x as Int, produce:\n"
        + "  Return Decision with approved set to true, reason set to \"ok\".\n\n"
        + "Rule wrap given x as Int, produce Decision:\n  Return mk(x).\n";
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    String json = mapper.writeValueAsString(core);
    assertTrue(json.contains("\"retTypeInferred\":true"), json);
    var back = mapper.readValue(json, aster.core.ir.CoreModel.Module.class);
    var diags = new TypeChecker().typecheckModule(back);
    assertFalse(diags.stream().anyMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH),
        "读回的 IR 仍应把推断返回类型登记进签名，实际: " + diags);
  }

  @Test
  void explicitWrongProduceTypeStillReportsReturnMismatch() {
    var diags = check(DECLS
        + "Rule decide given score as Int, produce Text:\n"
        + "  Return Decision with approved set to true, reason set to \"ok\".");
    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH),
        "显式声明的错误返回类型仍须报 RETURN_TYPE_MISMATCH，实际: " + diags);
  }
}
