package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.lexicon.LexiconRegistry;
import aster.core.typecheck.ErrorCode;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * 类型嵌套的词法期守卫（aster-lang-core#250）：计数单位与 aster-lang-ts {@code parseType} 相同，
 * 每种形态都在「上限」放行、「上限 + 1」报 E054。
 */
class TypeNestingGuardTest {

  private static final int LIMIT = AsterCustomLexer.MAX_TYPE_NESTING_DEPTH;

  private static List<ParseDiagnostic> diagnostics(String source) {
    return ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow("en-US")).diagnostics();
  }

  private static void assertParses(String source, String what) {
    assertEquals(List.of(), diagnostics(source), what + " 应正常解析");
  }

  private static void assertE054(String source, String what) {
    List<ParseDiagnostic> diags = diagnostics(source);
    assertTrue(!diags.isEmpty(), what + " 应报错");
    assertEquals(ErrorCode.PARSE_NESTING_TOO_DEEP, diags.get(diags.size() - 1).code(), what + " 应为 E054，实际: " + diags);
    assertEquals(String.valueOf(LIMIT), diags.get(diags.size() - 1).params().get("limit"));
  }

  @ParameterizedTest(name = "{0}：深度 = 上限放行")
  @ValueSource(strings = {"list", "option", "mapval", "mapkey", "mapalt", "resultok", "resulterr", "func"})
  void depthAtLimitParses(String form) {
    assertParses(TypeNestingShapes.source(form, LIMIT), form + " 深度 " + LIMIT);
  }

  @ParameterizedTest(name = "{0}：深度 = 上限 + 1 报 E054")
  @ValueSource(strings = {"list", "option", "mapval", "mapkey", "mapalt", "resultok", "resulterr", "func"})
  void depthOverLimitIsE054(String form) {
    assertE054(TypeNestingShapes.source(form, LIMIT + 1), form + " 深度 " + (LIMIT + 1));
  }

  @Test
  void mapKeyAndValueBothCount() {
    // 键和值各自都是一次类型解析：深度上限的类型放在键里或值里，都不能再多套一层
    String deep = TypeNestingShapes.type("list", LIMIT - 1);
    assertParses(src("Map " + deep + " to Int"), "键为上限 - 1 的 Map");
    assertParses(src("Map Int to " + deep), "值为上限 - 1 的 Map");
    String over = TypeNestingShapes.type("list", LIMIT);
    assertE054(src("Map " + over + " to Int"), "键超限的 Map");
    assertE054(src("Map Int to " + over), "值超限的 Map");
  }

  @Test
  void eachResultComponentCounts() {
    // 填充用 Map 值链而非 list 链：core 文法里 `list of A and B` 的 and 归最内层 list（OfGenericType 贪婪），
    // 用 list 填成功分量会让后面的 and 落进更深一层；Map 值链没有这个歧义，两引擎归属一致
    String deep = TypeNestingShapes.type("mapval", LIMIT - 1);
    String over = TypeNestingShapes.type("mapval", LIMIT);
    assertParses(src("result of " + deep + " and Text"), "成功分量为上限 - 1");
    assertParses(src("result of Int and " + deep), "失败分量为上限 - 1");
    assertE054(src("result of " + over + " and Text"), "成功分量超限");
    assertE054(src("result of Int and " + over), "失败分量超限");
  }

  @Test
  void siblingsDoNotAccumulate() {
    // 层数是嵌套深度，不是类型个数：并列的参数、键值、结果分量各自从同一层开始
    String deep = TypeNestingShapes.type("mapval", LIMIT - 1);
    assertParses(src("Map " + deep + " to " + deep), "键值都为上限 - 1");
    assertParses(src("result of " + deep + " and " + deep), "两个分量都为上限 - 1");
    String full = TypeNestingShapes.type("list", LIMIT);
    assertParses("Module probe.\n\nRule f given x as " + full + ", y as " + full + ", produce " + full
        + ":\n  Return x.\n", "多个参数与返回类型各自为上限");
  }

  @Test
  void fieldAndReturnPositionsAreGuarded() {
    String over = TypeNestingShapes.type("list", LIMIT + 1);
    assertE054("Module probe.\n\nDefine P has a as " + over + ".\n", "字段类型超限");
    assertE054("Module probe.\n\nRule f, produce " + over + ":\n  Return 1.\n", "返回类型超限");
    assertParses("Module probe.\n\nDefine P has a as " + TypeNestingShapes.type("list", LIMIT) + ".\n",
        "字段类型为上限");
  }

  @Test
  void annotationDoesNotAddLevels() {
    // 注解属于它所修饰的那次类型解析，不另占一层
    assertParses(src("list of ".repeat(LIMIT - 1) + "@pii(L2, email) Text"), "带注解、深度为上限");
    assertE054(src("list of ".repeat(LIMIT) + "@pii(L2, email) Text"), "带注解、深度为上限 + 1");
  }

  @Test
  void expressionsAreNotCountedAsTypes() {
    // 表达式里的 `X of` 构造与 Map 标识符不进类型计数（它们归表达式 / 括号守卫管）
    String src = "Module probe.\n\nRule f given x as Int, produce Int:\n"
        + "  Let m be " + "Ok of ".repeat(LIMIT + 5) + "x.\n  Return x.\n";
    List<ParseDiagnostic> diags = diagnostics(src);
    assertTrue(diags.stream().noneMatch(d -> String.valueOf(LIMIT).equals(d.params().get("limit"))
            && d.code() == ErrorCode.PARSE_NESTING_TOO_DEEP && d.message().contains("类型")),
        "表达式不应触发类型守卫: " + diags);
  }

  private static String src(String type) {
    return "Module probe.\n\nRule f given x as " + type + ", produce Int:\n  Return 1.\n";
  }

  @Test
  void defineAndStartStatementsTakeExpressionsNotTypes() {
    // defineStmt / startStmt 的 as 后面是表达式（`ok of …` 是 wrapExpr），不进类型计数；字段 / 参数的 as 照常计数。
    // 源码层面这类表达式同时受表达式守卫约束，难以单独区分，故直接喂 token 序列给计数器。
    for (int head : new int[] {AsterParser.DEFINE, AsterParser.START}) {
      TypeNestingTracker t = new TypeNestingTracker(LIMIT);
      boolean exceeded = feed(t, head, AsterParser.IDENT, AsterParser.AS);
      for (int i = 0; i < LIMIT + 5; i++) {
        exceeded |= feed(t, AsterParser.IDENT, AsterParser.OF);
      }
      exceeded |= feed(t, AsterParser.IDENT, AsterParser.DOT);
      assertTrue(!exceeded, AsterParser.VOCABULARY.getSymbolicName(head) + " x as <表达式> 不应计入类型深度");
    }
    TypeNestingTracker field = new TypeNestingTracker(LIMIT);
    boolean exceeded = feed(field, AsterParser.HAS, AsterParser.IDENT, AsterParser.AS);
    for (int i = 0; i < LIMIT + 5; i++) {
      exceeded |= feed(field, AsterParser.IDENT, AsterParser.OF);
    }
    assertTrue(exceeded, "字段的 as 后面是类型，超限必须报");
  }

  private static boolean feed(TypeNestingTracker t, int... types) {
    boolean exceeded = false;
    for (int type : types) {
      exceeded |= t.onToken(type);
    }
    return exceeded;
  }

  @Test
  void directTypeParsingIsGuarded() {
    // 直接以 parser.annotatedType() 解析类型文本（不经 as / produce）同样受守卫
    String over = TypeNestingShapes.type("mapval", LIMIT + 1);
    var parser = new AsterParser(new org.antlr.v4.runtime.CommonTokenStream(
        new AsterCustomLexer(org.antlr.v4.runtime.CharStreams.fromString(over))));
    parser.removeErrorListeners();
    ParseDiagnosticException ex = org.junit.jupiter.api.Assertions.assertThrows(ParseDiagnosticException.class,
        parser::annotatedType);
    assertEquals(ErrorCode.PARSE_NESTING_TOO_DEEP, ex.code());
    var ok = new AsterParser(new org.antlr.v4.runtime.CommonTokenStream(
        new AsterCustomLexer(org.antlr.v4.runtime.CharStreams.fromString(TypeNestingShapes.type("mapval", LIMIT)))));
    ok.removeErrorListeners();
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(ok::annotatedType);
  }

  @Test
  void lambdaTypesCountOnlyTypeDepth() {
    // lambda 的参数 / 返回类型从零数起，不受所在表达式深度影响（ts 侧同口径）
    String lambda = "Module probe.\n\nRule f given x as Int, produce Int:\n  Let g be function with y as %s, produce Int:\n"
        + "    Return 1.\n  Return x.\n";
    assertParses(String.format(lambda, TypeNestingShapes.type("mapval", LIMIT)), "lambda 参数类型为上限");
    assertE054(String.format(lambda, TypeNestingShapes.type("mapval", LIMIT + 1)), "lambda 参数类型为上限 + 1");
  }
}
