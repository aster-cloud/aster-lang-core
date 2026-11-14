package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Span;
import aster.core.ast.Stmt;
import aster.core.ast.Type;
import aster.core.canonicalizer.Canonicalizer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR 0056 §3.1 / §3.3 / §4.3（V1 返回类型 + V4 origin）：
 * <ul>
 *   <li>源码没写返回类型的 Func / Lambda，返回类型固定为占位 {@code Unknown}，不按规则名猜；</li>
 *   <li>占位锚定在 {@code produce} 关键词上；省略 {@code produce} 时取形参列表及其后可选逗号之后的第一个 token；</li>
 *   <li>推断的形参 / 字段类型锚定在名字 token 上。</li>
 * </ul>
 * 与 ts {@code decl-parser.ts} / {@code expr-stmt-parser.ts} 的锚点一致（parity 门只比较行号，列号也在此锁定）。
 */
class InferredTypeAnchorTest {

  private static aster.core.ast.Module parse(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    return new AstBuilder().visitModule(parser.module());
  }

  private static Decl.Func func(String src) {
    return (Decl.Func) parse(src).decls().stream().filter(d -> d instanceof Decl.Func).findFirst().orElseThrow();
  }

  private static void assertSpan(Span span, int line, int startCol, int endLine, int endCol) {
    assertEquals(line, span.start().line(), "start.line " + span);
    assertEquals(startCol, span.start().col(), "start.col " + span);
    assertEquals(endLine, span.end().line(), "end.line " + span);
    assertEquals(endCol, span.end().col(), "end.col " + span);
  }

  @ParameterizedTest
  @ValueSource(strings = {"generateQuote", "calculateRate", "isEligible", "hashText", "fetch_data", "findUser", "decide"})
  void inferredReturnTypeIsAlwaysUnknownPlaceholder(String name) {
    var f = func("Module m.\n\nRule " + name + " given x as Int, produce:\n  Return x.\n");
    assertTrue(f.retTypeInferred());
    var ret = (Type.TypeName) f.retType();
    assertEquals("Unknown", ret.name());
  }

  @Test
  void explicitUnknownIsNotInferred() {
    var f = func("Module m.\n\nRule f given x as Int, produce Unknown:\n  Return x.\n");
    assertFalse(f.retTypeInferred());
    assertEquals("Unknown", ((Type.TypeName) f.retType()).name());
  }

  @Test
  void inferredReturnTypeAnchorsOnProduceKeyword() {
    // 第 3 行：`Rule decide given x as Int, produce:`，produce 位于第 29–35 列
    var f = func("Module m.\n\nRule decide given x as Int, produce:\n  Let y be x.\n  Return y.\n");
    assertSpan(f.retType().span(), 3, 29, 3, 36);
  }

  @Test
  void omittedProduceAnchorsOnFirstTokenAfterParams() {
    // `Rule greet given name as Text:` 省略 produce：锚定在形参之后的 `:`（第 31 列）
    var withColon = func("Module m.\n\nRule greet given name as Text:\n  Return name.\n");
    assertTrue(withColon.retTypeInferred());
    assertSpan(withColon.retType().span(), 3, 30, 3, 31);
    // `Rule greet given name as Text. It performs io:`：锚定在形参之后的 `.`
    var withEffects = func("Module m.\n\nRule greet given name as Text. It performs io:\n  Return name.\n");
    assertSpan(withEffects.retType().span(), 3, 30, 3, 31);
    // 形参后的可选逗号被跳过：`Rule greet given name as Text, produce:` 的锚点是 produce（第 32–38 列）
    var withComma = func("Module m.\n\nRule greet given name as Text, produce:\n  Return name.\n");
    assertSpan(withComma.retType().span(), 3, 32, 3, 39);
  }

  @Test
  void inferredLambdaReturnTypeAnchorsOnProduceKeyword() {
    var f = func("Module m.\n\nRule f given xs as List of Int, produce List of Int:\n"
        + "  Return List.map(xs, function with x as Int, produce:\n"
        + "    Let y be x plus 1.\n"
        + "    Return y.\n"
        + "  ).\n");
    var ret = (Stmt.Return) f.body().statements().get(0);
    var call = (Expr.Call) ret.expr();
    var lambda = (Expr.Lambda) call.args().get(1);
    var lambdaRet = (Type.TypeName) lambda.retType();
    assertEquals("Unknown", lambdaRet.name());
    // 第 4 行 `  Return List.map(xs, function with x as Int, produce:`，produce 位于第 47–53 列
    assertSpan(lambdaRet.span(), 4, 47, 4, 54);
  }

  @Test
  void inferredParamAndFieldTypesAnchorOnName() {
    // 带约束时锚点仍只是名字 token，不含其后的约束
    var module = parse("Module m.\n\nDefine Applicant has creditScore, approved, age required.\n\n"
        + "Rule f given applicant, amount between 1 and 10, produce Int:\n  Return 1.\n");
    var data = (Decl.Data) module.decls().get(0);
    // `Define Applicant has creditScore, approved, age required.`：creditScore 22–32、approved 35–42、age 45–47 列
    assertSpan(data.fields().get(0).type().span(), 3, 22, 3, 33);
    assertSpan(data.fields().get(1).type().span(), 3, 35, 3, 43);
    assertSpan(data.fields().get(2).type().span(), 3, 45, 3, 48);
    var f = (Decl.Func) module.decls().get(1);
    // `Rule f given applicant, amount between 1 and 10, produce Int:`：applicant 14–22、amount 25–30 列
    assertSpan(f.params().get(0).type().span(), 5, 14, 5, 23);
    assertSpan(f.params().get(1).type().span(), 5, 25, 5, 31);
  }
}
