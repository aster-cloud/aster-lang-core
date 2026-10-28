package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
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
 * 限定枚举成员 {@code E.M}（ADR 0051）：类型检查器把它视为与裸成员 {@code M} 相同的枚举类型。
 * 名称解析保持写法形态（{@code Stufe.Hoch → Level.High}），E804/E806 的提示也引导写 {@code E.M}，
 * 因此限定写法必须通过类型检查。用例与 TS qualified-enum-member.test.ts 同名同输入。
 */
class QualifiedEnumMemberTest {

  private static CoreModel.Module coreOf(String source) {
    String canonical = new Canonicalizer().canonicalize(source);
    var tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical)));
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    return new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
  }

  private static List<Diagnostic> errors(CoreModel.Module core) {
    return new TypeChecker().typecheckModule(core).stream()
        .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
        .toList();
  }

  private static final String TIER = "Module m.\n\nDefine Tier as one of Gold, Silver.\n\n";

  private static final String QUALIFIED = TIER
      + "Rule pick given t as Tier, produce Tier:\n"
      + "  If t equals to Tier.Gold:\n"
      + "    Return Tier.Silver.\n"
      + "  Return Tier.Gold.\n";

  private static final String BARE = TIER
      + "Rule pick given t as Tier, produce Tier:\n"
      + "  If t equals to Gold:\n"
      + "    Return Silver.\n"
      + "  Return Gold.\n";

  @Test
  void qualifiedMemberTypechecksLikeBareMember() {
    assertEquals(List.of(), errors(coreOf(QUALIFIED)));
    assertEquals(List.of(), errors(coreOf(BARE)));
  }

  @Test
  void qualifiedMemberHasEnumTypeSoMismatchStillReported() {
    String src = TIER + "Rule f given x as Int, produce Int:\n  Return Tier.Gold.\n";
    assertEquals(1, errors(coreOf(src)).size(), errors(coreOf(src)).toString());
  }

  @Test
  void missingMemberAndNonEnumQualifierStillUndefined() {
    String missing = TIER + "Rule f given x as Int, produce Tier:\n  Return Tier.Bronze.\n";
    assertEquals(1, errors(coreOf(missing)).size(), errors(coreOf(missing)).toString());
    String notEnum = TIER + "Rule f given x as Int, produce Tier:\n  Return Rank.Gold.\n";
    assertEquals(1, errors(coreOf(notEnum)).size(), errors(coreOf(notEnum)).toString());
  }
}
