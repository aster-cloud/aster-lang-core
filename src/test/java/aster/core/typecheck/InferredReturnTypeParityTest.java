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
  void explicitWrongProduceTypeStillReportsReturnMismatch() {
    var diags = check(DECLS
        + "Rule decide given score as Int, produce Text:\n"
        + "  Return Decision with approved set to true, reason set to \"ok\".");
    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH),
        "显式声明的错误返回类型仍须报 RETURN_TYPE_MISMATCH，实际: " + diags);
  }
}
