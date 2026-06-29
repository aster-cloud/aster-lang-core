package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * Decimal↔Double 混算编译期拦截（ADR 0025，M1 后续；与 TS decimal-mixing-typecheck 对齐）。
 *
 * <p>runtime 只能 catch 非整数 Double（2.5）；整数值 Double（2.0）与 Int（2）在底层数值
 * 不可分——必须靠 typechecker 按 AST/Core IR 节点 kind（Double vs Decimal）在编译期拦截。
 * Int/Long↔Decimal 精确提升放行。错误码 E031 DECIMAL_DOUBLE_MIXING。
 */
class DecimalMixingTypeCheckTest {

  private boolean hasMixingError(String body) {
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
    var diags = new TypeChecker().typecheckModule(core);
    return diags.stream().anyMatch(d -> d.code() == ErrorCode.DECIMAL_DOUBLE_MIXING);
  }

  @Test void mixingProducesError() {
    // 整数值 Double（runtime 抓不到）+ 非整数 Double + 嵌套传播
    assertTrue(hasMixingError("Rule main produce Decimal:\n  Return 1.08m plus 2.0."));
    assertTrue(hasMixingError("Rule main produce Decimal:\n  Return 2.0 times 3m."));
    assertTrue(hasMixingError("Rule main produce Decimal:\n  Return 1.08m minus 2.5."));
    // 嵌套：(100.00m times 1.08m) 结果 Decimal，与 0.5(Double) 混算 → error
    assertTrue(hasMixingError("Rule main produce Decimal:\n  Return 100.00m times 1.08m plus 0.5."));
  }

  @Test void legalCombinationsNoError() {
    // Int/Long→Decimal 精确提升 + 纯 Decimal + 纯 Double 都合法
    assertFalse(hasMixingError("Rule main produce Decimal:\n  Return 1.08m plus 2."));
    assertFalse(hasMixingError("Rule main produce Decimal:\n  Return 1.08m plus 2.50m."));
    assertFalse(hasMixingError("Rule main produce Double:\n  Return 1.08 plus 2.5."));
    assertFalse(hasMixingError("Rule main produce Int:\n  Return 1 plus 2."));
  }

  @Test void comparisonAndBuiltinArgMixing() {
    // Codex 审查 P0：比较运算符混算（整数值 Double 不可 runtime catch）
    assertTrue(hasMixingError("Rule main produce Bool:\n  Return 1.0m equals to 1.0."));
    assertTrue(hasMixingError("Rule main produce Bool:\n  Return 1.0m at most 2.0."));
    assertTrue(hasMixingError("Rule main produce Bool:\n  Return 2.0 greater than 1.0m."));
    // Decimal.round/divide 的 Decimal 参数位混 Double
    assertTrue(hasMixingError("Rule main produce Decimal:\n  Return Decimal.divide(1m, 2.0, 2, \"HALF_UP\")."));
    assertTrue(hasMixingError("Rule main produce Decimal:\n  Return Decimal.round(2.0, 1, \"HALF_UP\")."));
  }

  @Test void comparisonAndBuiltinLegalNoError() {
    assertFalse(hasMixingError("Rule main produce Bool:\n  Return 1.0m equals to 1.00m."));
    assertFalse(hasMixingError("Rule main produce Bool:\n  Return 1.0m at most 2."));
    assertFalse(hasMixingError("Rule main produce Decimal:\n  Return Decimal.divide(1m, 2m, 2, \"HALF_UP\")."));
    assertFalse(hasMixingError("Rule main produce Bool:\n  Return 1.0 equals to 2.0."));
  }
}
