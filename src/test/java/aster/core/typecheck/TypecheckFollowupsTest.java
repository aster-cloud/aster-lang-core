package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * 类型检查跟进（#232 #233 #234，与 aster-lang-ts 同名分支同步）。
 *
 * <p>原则（用户裁决）：能正确运行的代码不报错；按名字推断出的类型视为未知；数值拓宽在所有赋值类位置
 * （实参、字段、Set、Return）放行，收窄照常报错；TS 不比 Java 严。
 * <ul>
 *   <li>#232：未写类型的 data 字段不按推断类型检查构造值（E012）；按名字推断类型的值放进字段、Set、Return、
 *       If 条件时不作约束；Return 位置按 {@link TypeSystem#isSubtype} 比较（拓宽、T → T?、None → T?）。</li>
 *   <li>#233：{@code When Some(v)} 不再重复登记 v（E104）；未声明的单段 builtin 名（含别名）调用不报 E101；
 *       PII 实参先展开内层别名再比较，不再在 E073 之外多报 E001。</li>
 *   <li>#236：workflow step / compensate 体各有独立作用域（与 truffle#163 一致）。</li>
 *   <li>#234：truffle 归一化的单词别名（equals / is / over / under / divide …，不区分大小写）与
 *       单段 builtin 一样不算「调用非函数局部名」。</li>
 * </ul>
 */
class TypecheckFollowupsTest {

  private static Module parse(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical))));
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        throw new IllegalStateException("line " + line + ":" + col + " " + msg);
      }
    });
    return new AstBuilder().visitModule(parser.module());
  }

  private static List<String> errors(String body) {
    String src = "Module probe.\n\n" + body;
    CoreModel.Module core = new CoreLowering().lowerModule(assertDoesNotThrow(() -> parse(src), src));
    return new TypeChecker().typecheckModule(core).stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .map(d -> d.code().code() + " " + d.message())
      .toList();
  }

  private static void assertClean(String body) {
    assertEquals(List.of(), errors(body), body);
  }

  private static void assertReports(String code, String body) {
    var errs = errors(body);
    assertTrue(errs.stream().anyMatch(e -> e.startsWith(code + " ")), body + " 应报 " + code + "，实际 " + errs);
  }

  // ========== #232 按名字推断的类型视为未知 ==========

  @Test
  void untypedDataFieldsAreNotCheckedAgainstNameInferredTypes() {
    assertClean("""
      Define Decision has approved, collateralRequired, approvedLimit, creditLine.

      Rule main given n as Int, produce Decision:
        Return Decision with approved set to true, collateralRequired set to 1, approvedLimit set to 2, creditLine set to 3.
      """);
  }

  @Test
  void nameInferredLocalIntoTypedFieldIsNotChecked() {
    assertClean("Define P has total as Int.\n\nRule main given amount, produce P:\n  Return P with total set to amount.\n");
  }

  @Test
  void returnOfNameInferredLocalIsNotChecked() {
    assertClean("Rule main given amount, produce Int:\n  Return amount.\n");
    assertClean("Rule main given amount, produce Int:\n  Let y be amount.\n  Return y.\n");
  }

  @Test
  void setFromNameInferredLocalIsNotChecked() {
    assertClean("Rule main given amount, produce Int:\n  Let y be 1.\n  Set y to amount.\n  Return y.\n");
  }

  @Test
  void ifConditionFromNameInferredLocalIsNotChecked() {
    assertClean("Rule main given approved_status, produce Int:\n  If approved_status:\n    Return 1.\n  Return 0.\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "Rule main given n as Int, produce Float:\n  Return 1.\n",
    "Rule main given n as Int, produce Long:\n  Return n.\n",
    "Rule main given n as Int, produce Decimal:\n  Return n.\n",
    "Rule main given n as Long, produce Float:\n  Return n.\n",
    "Rule main given n as Int, produce Int?:\n  Return n.\n",
    "Rule main given n as Int, produce Int?:\n  Return None.\n",
    "Rule main given n as Int, produce Float:\n  Let g be function with x as Int, produce Float:\n    Return x.\n  Return g(n).\n",
  })
  void returnWidensLikeArguments(String body) {
    assertClean(body);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "Rule main given n as Float, produce Int:\n  Return n.\n",
    "Rule main given n as Long, produce Int:\n  Return n.\n",
    "Rule main given t as Text, produce Int:\n  Return t.\n",
    "Rule main given n as Int?, produce Int:\n  Return n.\n",
    "Rule main given d as Double, produce Decimal:\n  Return d.\n",
    "Rule main given d as Decimal, produce Float:\n  Return d.\n",
    "Rule main given n as Float, produce Int:\n  Let g be function with x as Float, produce Int:\n    Return x.\n  Return g(n).\n",
  })
  void returnNarrowingStillReported(String body) {
    assertReports("E003", body);
  }

  @Test
  void typedFieldSetAndIfNarrowingStillReported() {
    assertReports("E012", "Define P has total as Int.\n\nRule main given t as Text, produce P:\n  Return P with total set to t.\n");
    assertReports("E002", "Rule main given f as Float, produce Int:\n  Let y be 1.\n  Set y to f.\n  Return 0.\n");
    assertReports("E001", "Rule main given t as Text, produce Int:\n  If t:\n    Return 1.\n  Return 0.\n");
  }

  // ========== #233 既有 Java / TS 差异 ==========

  @Test
  void someBindingIsDefinedOnce() {
    assertClean("Rule main given o as Int?, produce Int:\n  Match o:\n    When Some(v), Return v.\n    When None, Return 0.\n");
    assertClean("Rule main given o as Option of Int, produce Int:\n  Match o:\n    When None, Return 0.\n    When Some(v), Return v.\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {"add(1, 2)", "gt(2, 1)", "divide(10, 2)", "over(3, 2)", "equals(2, 2)"})
  void undeclaredBuiltinCallIsNotUndefined(String call) {
    var errs = errors("Rule main given n as Int, produce Int:\n  Let r be " + call + ".\n  Return n.\n");
    assertEquals(List.of(), errs, call);
  }

  @Test
  void undeclaredNonBuiltinCallIsStillUndefined() {
    assertReports("E101", "Rule main given n as Int, produce Int:\n  Return foo(1, 2).\n");
  }

  @Test
  void piiArgumentReportsOnlyPiiMismatch() {
    var errs = errors("Rule f given x as Text, produce Int:\n  Return 1.\n\n"
      + "Rule main given e as @pii(L2, email) Text, produce Int:\n  Return f(e).\n");
    assertEquals(List.of("E073"), errs.stream().map(e -> e.substring(0, 4)).toList(), errs.toString());
  }

  // ========== #234 truffle 归一化的单词别名 ==========

  @ParameterizedTest
  @ValueSource(strings = {"over", "divide", "under", "equals", "is"})
  void aliasNamedLocalCallIsNotNonCallable(String alias) {
    assertClean("Rule main given " + alias + " as Int, produce Int:\n  Let r be " + alias + "(3, 2).\n  Return 0.\n");
  }

  @Test
  void aliasesAreCaseInsensitiveRegisteredNamesAreNot() {
    assertTrue(CallTargetBuiltins.isCallTarget("Over"));
    assertTrue(CallTargetBuiltins.isCallTarget("DIVIDE"));
    assertTrue(CallTargetBuiltins.isCallTarget("gt"));
    assertTrue(!CallTargetBuiltins.isCallTarget("GT"));
    assertTrue(!CallTargetBuiltins.isCallTarget("score"));
  }

  @Test
  void nonAliasLocalStillReported() {
    assertReports("E001", "Rule score given a as Int, produce Int:\n  Return a.\n\n"
      + "Rule main given n as Int, produce Int:\n  Let score be 3.\n  Return score(n).\n");
  }

  // ========== #236 workflow step / compensate 体的作用域（与 truffle#163 一致） ==========

  private static final String STEPS = """
    Rule runSteps given n as Int, produce Int. It performs io:
      Let x be n.
      workflow:
        step first:
          Let x be n plus 1.
          Let y be x times 2.
          Return ok of y.
        compensate:
          Let x be n minus 1.
          Return ok of x.

        step second:
          Let y be n plus 10.
          Return ok of y.

        step third:
          Set x to n plus 100.
          Return ok of x.
      .
      Return x.
    """;

  @Test
  void sameNamedLetsInDifferentStepsAreNotDuplicates() {
    assertClean(STEPS);
  }

  @Test
  void readingAnotherStepsLetIsUndefined() {
    assertReports("E101", """
      Rule runSteps given n as Int, produce Int. It performs io:
        workflow:
          step first:
            Let y be n plus 1.
            Return ok of y.

          step second:
            Return ok of y.
        .
        Return n.
      """);
  }
}
