package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

/**
 * 用户规则 / 函数值调用的实参检查（aster-cloud/aster-lang-core#229，与 aster-lang-ts#273 对齐）。
 *
 * <p>原则：类型检查不对能正确运行的代码报错，拿不准时放行。
 * <ul>
 *   <li>数值拓宽放行：Int → Long / Float / Double / Decimal，Long → Float / Double / Decimal，
 *       Float ↔ Double；容器类型参数（List / Option / Maybe / Result / Map）同样适用。</li>
 *   <li>可空：T → T?、None → T?、T? ↔ Option of T、Some(n) → Option of Int 放行。</li>
 *   <li>只按名字推断出的类型（未写类型的形参 / lambda 形参、推断失败的返回类型占位）
 *       在实参比较与调用目标检查中视为未知。</li>
 *   <li>泛型规则的类型参数（`of T`）不按字面类型名比较，与 TS 放行含类型变量的形参一致。</li>
 *   <li>#268：调用类型已知且不是函数的局部名报 E001「expected a function」，与 TS 同文案。</li>
 * </ul>
 */
class RuleCallArgumentCheckTest {

  private static final String INC = "Rule inc given x as Int, produce Int:\n  Return x plus 1.\n";
  private static final String HALF = "Rule half given x as Float, produce Float:\n  Return x.\n";
  private static final String TWICE = "Rule twice given f as (Int) -> Int, n as Int, produce Int:\n  Return f(f(n)).\n";

  private static Module parse(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        throw new IllegalStateException("line " + line + ":" + col + " " + msg);
      }
    });
    return new AstBuilder().visitModule(parser.module());
  }

  private static List<Diagnostic> errors(String... decls) {
    String src = "Module probe.\n\n" + String.join("\n", decls) + "\n";
    CoreModel.Module core = new CoreLowering().lowerModule(assertDoesNotThrow(() -> parse(src), src));
    return new TypeChecker().typecheckModule(core).stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
  }

  private static List<String> codes(List<Diagnostic> diagnostics) {
    return diagnostics.stream().map(d -> d.code().code() + " " + d.message()).toList();
  }

  private static void assertClean(String... decls) {
    assertEquals(List.of(), codes(errors(decls)));
  }

  private static void assertMismatch(String expected, String actual, String... decls) {
    var diags = errors(decls);
    assertEquals(List.of("E001 Type mismatch: expected " + expected + ", got " + actual), codes(diags));
  }

  // ========== 既有行为：显式类型形参照常比较 ==========

  @Test
  void explicitParamTypeStillChecked() {
    assertMismatch("Int", "String", INC, "Rule main given n as Int, produce Int:\n  Return inc(\"a\").");
  }

  @Test
  void floatToIntStillRejected() {
    assertMismatch("Int", "Float", INC, "Rule main given f as Float, produce Int:\n  Return inc(f).");
  }

  @Test
  void textListToIntListStillRejected() {
    assertMismatch("List<Int>", "List<String>",
      "Rule total given xs as List of Int, produce Int:\n  Return 1.",
      "Rule main given t as Text, produce Int:\n  Return total([t]).");
  }

  @Test
  void maybeToPlainStillRejected() {
    assertMismatch("Int", "Int?", INC, "Rule main given n as Int?, produce Int:\n  Return inc(n).");
  }

  @Test
  void longToIntStillRejected() {
    assertMismatch("Int", "Long", INC, "Rule main given m as Long, produce Int:\n  Return inc(m).");
  }

  @Test
  void optionToPlainStillRejected() {
    assertMismatch("Int", "option<Int>", INC, "Rule main given o as Option of Int, produce Int:\n  Return inc(o).");
  }

  @Test
  void doubleAndDecimalDoNotMix() {
    // ADR 0025：Double ↔ Decimal 不做隐式转换（变量与字面量都拒绝）
    String money = "Rule money given x as Decimal, produce Decimal:\n  Return x.";
    String real = "Rule real given x as Double, produce Double:\n  Return x.";
    assertMismatch("Decimal", "Double", money, "Rule main given d as Double, produce Decimal:\n  Return money(d).");
    assertMismatch("Decimal", "Double", money, "Rule main given n as Int, produce Decimal:\n  Return money(1.5).");
    assertMismatch("Double", "Decimal", real, "Rule main given m as Decimal, produce Double:\n  Return real(m).");
  }

  @Test
  void constructFieldNarrowingStillRejected() {
    var diags = errors("Define Box has count as Int, label as Text.",
      "Rule main given f as Float, t as Text?, produce Box:\n  Return Box with count set to f, label set to t.");
    assertEquals(List.of("E012 Field 'count' expects Int, got Float", "E012 Field 'label' expects String, got String?"),
      codes(diags).stream().sorted().toList());
  }

  @Test
  void setNarrowingStillRejected() {
    var diags = errors("Rule main given f as Float, o as Int?, produce Int:\n  Let x be 1.\n  Set x to f.\n  Set x to o.\n  Return x.");
    assertEquals(List.of("E002", "E002"), diags.stream().map(d -> d.code().code()).toList());
  }

  @Test
  void mismatchMessageShowsOriginalTypes() {
    // 比较时擦除的部分（泛型类型参数、按名字推断）不出现在诊断文本里
    assertMismatch("List<T>", "String",
      "Rule size of T given xs as List of T, produce Int:\n  Return 1.",
      "Rule main given t as Text, produce Int:\n  Return size(t).");
    assertMismatch("Int", "Option<Float>", INC,
      "Rule main given amount, produce Int:\n  Return inc(Some(amount)).");
  }

  // ========== 数值拓宽 ==========

  @Test
  void intWidensToFloat() {
    assertClean(HALF, "Rule main given n as Int, produce Float:\n  Return half(n).");
  }

  @Test
  void intLiteralWidensToFloat() {
    assertClean(HALF, "Rule main given n as Int, produce Float:\n  Return half(2).");
  }

  @Test
  void doubleLiteralFitsFloat() {
    assertClean(HALF, "Rule main given n as Int, produce Float:\n  Return half(1.5).");
  }

  @Test
  void intWidensToLongIncludingLiteral() {
    assertClean("Rule big given x as Long, produce Long:\n  Return x.",
      "Rule main given n as Int, produce Long:\n  Let a be big(n).\n  Return big(5).");
  }

  @Test
  void longWidensToFloat() {
    assertClean(HALF, "Rule main given m as Long, produce Float:\n  Return half(m).");
  }

  @Test
  void intWidensToDecimal() {
    assertClean("Rule money given x as Decimal, produce Decimal:\n  Return x.",
      "Rule main given n as Int, produce Decimal:\n  Return money(n).");
  }

  @Test
  void widensInsideContainers() {
    assertClean(
      "Rule total given xs as List of Float, produce Int:\n  Return 1.",
      "Rule pick given o as Option of Float, produce Int:\n  Return 1.",
      "Rule settle given r as Result of Float and Text, produce Int:\n  Return 1.",
      "Rule main given xs as List of Int, n as Int, r as Result of Int and Text, produce Int:\n"
        + "  Let a be total(xs).\n  Let b be total([1, 2]).\n  Let c be pick(Some(n)).\n  Return settle(r).");
  }

  @Test
  void widensInsideFunctionTypes() {
    // 期望 (Int) -> Float 时传 (Int) -> Int：返回位协变
    assertClean(INC,
      "Rule apply given f as (Int) -> Float, n as Int, produce Float:\n  Return f(n).",
      "Rule main given n as Int, produce Float:\n  Return apply(inc, n).");
  }

  @Test
  void constructFieldWidens() {
    assertClean("Define Box has price as Float.",
      "Rule main given n as Int, produce Box:\n  Return Box with price set to n.");
  }

  // ========== Maybe / Option ==========

  @Test
  void plainFitsMaybe() {
    assertClean("Rule show given x as Text?, produce Int:\n  Return 1.",
      "Rule main given t as Text, produce Int:\n  Return show(t).");
  }

  @Test
  void noneFitsMaybe() {
    assertClean("Rule show given x as Text?, produce Int:\n  Return 1.",
      "Rule main given t as Text, produce Int:\n  Return show(None).");
  }

  @Test
  void maybeAndOptionInterchange() {
    assertClean(
      "Rule show given x as Text?, produce Int:\n  Return 1.",
      "Rule pick given o as Option of Text, produce Int:\n  Return 1.",
      "Rule main given t as Text?, o as Option of Text, produce Int:\n  Let a be pick(t).\n  Return show(o).");
  }

  @Test
  void someFitsOption() {
    assertClean("Rule pick given o as Option of Int, produce Int:\n  Return 1.",
      "Rule main given n as Int, produce Int:\n  Return pick(Some(n)).");
  }

  @Test
  void someOfWrongTypeStillRejected() {
    var diags = errors("Rule pick given o as Option of Int, produce Int:\n  Return 1.",
      "Rule main given t as Text, produce Int:\n  Return pick(Some(t)).");
    assertEquals(List.of("E001"), diags.stream().map(d -> d.code().code()).toList());
  }

  // ========== 按名字推断的类型视为未知 ==========

  @Test
  void untypedParamNotUsedAsCallConstraint() {
    assertClean("Rule label given amount, produce Text:\n  Return \"x\".",
      "Rule main given n as Int, produce Text:\n  Return label(\"a\").");
  }

  @Test
  void untypedLambdaParamNotUsedAsCallConstraint() {
    assertClean("Rule main given flag as Bool, produce Int:\n  Let f be function with x, produce:\n    Return 1.\n  Return f(flag).");
  }

  @Test
  void nameInferredArgumentNotCompared() {
    assertClean(INC, "Rule main given amount, produce Int:\n  Return inc(amount).");
  }

  @Test
  void nameInferredArgumentThroughLetNotCompared() {
    assertClean(INC, "Rule main given amount, produce Int:\n  Let y be amount.\n  Return inc(y).");
  }

  @Test
  void placeholderReturnTypeNotCompared() {
    // produce: 推断失败时返回类型只是按规则名猜的占位
    assertClean(INC, "Rule mk given x as Int, produce:\n  Return x.",
      "Rule main given n as Int, produce Int:\n  Return inc(mk(n)).");
  }

  @Test
  void functionValueWithUntypedParamAndPlaceholderReturn() {
    assertClean(TWICE, "Rule bump given amount, produce:\n  Return amount.",
      "Rule main given n as Int, produce Int:\n  Return twice(bump, n).");
  }

  @Test
  void callingNameInferredLocalIsNotReported() {
    assertClean(INC, "Rule ap given f, n as Int, produce Int:\n  Return f(n).",
      "Rule main given n as Int, produce Int:\n  Return ap(inc, n).");
  }

  // ========== 泛型规则：类型参数不按字面类型名比较 ==========

  private static final String ID = "Rule id of T given x as T, produce T:\n  Return x.\n";

  @Test
  void genericRuleCallAndResultAsArgument() {
    assertClean(ID, INC, "Rule main given n as Int, produce Int:\n  Return inc(id(n)).");
  }

  @Test
  void genericRuleWithContainerParam() {
    assertClean("Rule unwrap of T given o as Option of T, d as T, produce T:\n  Return d.",
      INC, "Rule main given n as Int, produce Int:\n  Return inc(unwrap(Some(n), n)).");
  }

  @Test
  void genericArgumentsAreNotUnified() {
    // 已知放过的真实错误（两引擎一致）：类型参数之间不做合一，`inc(id(t))` 与 `pick(t, 1)` 都不报
    assertClean(ID, INC, "Rule pick of T given a as T, b as T, produce T:\n  Return a.",
      "Rule main given t as Text, produce Int:\n  Let p be pick(t, 1).\n  Return inc(id(t)).");
  }

  @Test
  void genericRuleAsFunctionValue() {
    assertClean(ID, TWICE, "Rule main given n as Int, produce Int:\n  Return twice(id, n).");
  }

  // ========== #268：调用非函数局部名 ==========

  @Test
  void callingNonFunctionParamShadowingRule() {
    var diags = errors("Rule score given a as Int, produce Int:\n  Return a.",
      "Rule main given score as Int, produce Int:\n  Return score(1, 2).");
    assertEquals(List.of("E001 Type mismatch: expected a function, got 'score' of type Int"), codes(diags));
    assertEquals(Map.of("expected", "a function", "actual", "'score' of type Int"), diags.get(0).data());
  }

  @Test
  void callingNonFunctionParam() {
    assertMismatch("a function", "'n' of type Int", "Rule main given n as Int, produce Int:\n  Return n(1).");
  }

  @Test
  void callingNonFunctionLet() {
    assertMismatch("a function", "'score' of type Int",
      "Rule main given n as Int, produce Int:\n  Let score be 1.\n  Return score(n).");
  }

  @Test
  void localNamedLikeBuiltinIsNotReported() {
    // truffle 在调用位置先按 builtin 分派（add / sub / … 不看局部名），这类程序能正常运行
    assertClean("Rule main given add as Int, produce Int:\n  Return add(1, 2).");
    assertClean("Rule main given n as Int, produce Int:\n  Let sub be 3.\n  Return sub(5, n).");
  }

  @Test
  void callTargetBuiltinsMirrorTruffle() {
    assertEquals(java.util.Set.of("add", "sub", "mul", "div", "intdiv", "mod", "eq", "ne", "lt", "lte",
        "gt", "gte", "and", "or", "not", "await"), CallTargetBuiltins.NAMES);
  }

  @Test
  void functionTypedLocalShadowingRuleStillWorks() {
    assertClean(INC,
      "Rule main given inc as (Int) -> Int, n as Int, produce Int:\n  Return inc(n).",
      "Rule dec given x as Int, produce Int:\n  Return x minus 1.",
      "Rule run given n as Int, produce Int:\n  Return main(dec, n).");
  }
}
