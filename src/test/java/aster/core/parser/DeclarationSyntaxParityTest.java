package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import aster.core.ast.Annotation;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Stmt;
import aster.core.lexicon.LexiconRegistry;
import aster.core.typecheck.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * aster-lang-ts#334：#328 之后余下的七处双引擎解析分歧（ADR 0056 修订 A3），与 ts declaration-syntax-parity.test.ts 对应。
 *
 * <ul>
 *   <li>大写字段名 {@code Define Order with Id as Text.}：core 此前接受，ts 报 E055。裁决两引擎都在字段名上报 E055。</li>
 *   <li>字段前置注解 {@code has @pii(L1, email) email as Text}：core 一直接受，这里锁住 Field.annotations 的顺序。</li>
 *   <li>大写规则名 {@code Rule Approve …}：core 此前接受，ts 报 E055。裁决两引擎都在规则名上报 E055。</li>
 *   <li>全大写导入关键词 {@code USE}：core 此前报 E063，ts 接受。裁决 core 接受（关键词大小写不敏感）。</li>
 *   <li>下划线开头的标识符 {@code _x}：core 此前接受，ts 词法报 E050。裁决两引擎都在 {@code _} 上报 E050。</li>
 *   <li>lambda 单行体 {@code function given x as Int, produce Int: Return x.}：core 文法本来就收，这里锁住。</li>
 *   <li>{@code given x as Pair of Int, y, produce …}：core 此前在 ANTLR 错误恢复里拒绝，首条诊断随预测缓存冷热而变；
 *       现在与 ts 一样在 {@code y} 上报 E055。</li>
 * </ul>
 */
class DeclarationSyntaxParityTest {

  private static ParseDiagnostics.Result parse(String source) {
    return parse(source, "en-US");
  }

  private static ParseDiagnostics.Result parse(String source, String lexicon) {
    return ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow(lexicon));
  }

  private static ParseDiagnostic onlyDiagnostic(String source) {
    List<ParseDiagnostic> diags = parse(source).diagnostics();
    assertEquals(1, diags.size(), source + "\n" + diags);
    return diags.get(0);
  }

  private static void assertAccepted(String source) {
    assertEquals(List.of(), parse(source).diagnostics(), source);
  }

  // ---------------------------------------------------------------- 1. 大写字段名

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      Define Order with Id as Text.                       | 3 | 19
      Define Order has id as Text, Amount as Int.         | 3 | 30
      Define Order has id as Text and Amount.             | 3 | 33
      Define Order has Id.                                | 3 | 18
      Define Order has @pii(L1, email) Email as Text.     | 3 | 34
      Define Order has\\n  id as Text,\\n  Amount as Int. | 5 | 3
      """)
  void capitalizedFieldNameIsE055(String decl, int line, int column) {
    ParseDiagnostic d = onlyDiagnostic("Module m.\n\n" + decl.replace("\\n", "\n") + "\n");
    assertEquals(ErrorCode.PARSE_EXPECTED, d.code());
    assertEquals(Map.of("token", "token.fieldName"), d.params());
    assertEquals("Expected field name", d.message());
    assertEquals(line, d.line());
    assertEquals(column, d.column());
  }

  /** 只看首字符是否为 ASCII 大写，与 ts 词法的 TYPE_IDENT 判据一致：Latin 扩展大写、天城文、中文字段名照常接受。 */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"customer", "Ölwert", "राशि", "a_b"})
  void nonAsciiUppercaseFieldNamesStayAccepted(String name) {
    assertAccepted("Module m.\n\nDefine Box has " + name + " as Text.\n");
  }

  @Test
  void chineseFieldNameStaysAccepted() {
    assertEquals(List.of(), parse("模块 m。\n\n定义 客户 包含 姓名 作为 文本。\n", "zh-CN").diagnostics());
  }

  @Test
  void capitalizedLetNameIsOutOfScope() {
    // 本轮只裁决字段名与规则名；Let 名的大小写不受影响
    assertAccepted("Module m.\n\nRule f given x as Int, produce Int:\n  Let Low be x.\n  Return Low.\n");
  }

  // ---------------------------------------------------------------- 2. 字段前置注解

  @Test
  void fieldPrefixAnnotationsComeBeforeTypeAnnotations() {
    var r = parse("Module m.\n\nDefine C has @audit @tag(k: 1) name as @pii(L1, name) Text, plain as Text.\n");
    assertEquals(List.of(), r.diagnostics());
    var fields = assertInstanceOf(Decl.Data.class, r.module().decls().get(0)).fields();
    assertEquals(List.of("audit", "tag", "pii"), fields.get(0).annotations().stream().map(Annotation::name).toList());
    assertEquals(List.of(), fields.get(1).annotations());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "Define B has x as Pair of Int, @pii(L1, email) y as Text.",
      "Define B has x as Pair of Int and @audit y as Text.",
      "Define B has @tag(name: \"k\", 3, true) note.",
      "Define B has @source(form: \"loan\") amount as Int between 1 and 100.",
      "Define B has\\n  @pii(L1, email) contact as Text,\\n  @audit score as Int.",
  })
  void fieldPrefixAnnotationsAreAccepted(String decl) {
    assertAccepted("Module m.\n\nDefine Pair has a as Int.\n\n" + decl.replace("\\n", "\n") + "\n");
  }

  // ---------------------------------------------------------------- 3. 大写规则名

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      Rule Approve given x as Int, produce Int:\\n  Return x.   | 3 | 6
      Rule Approve, produce Int:\\n  Return 1.                  | 3 | 6
      @entry\\nRule Approve, produce Int:\\n  Return 1.          | 4 | 6
      """)
  void capitalizedRuleNameIsE055(String decl, int line, int column) {
    ParseDiagnostic d = onlyDiagnostic("Module m.\n\n" + decl.replace("\\n", "\n") + "\n");
    assertEquals(ErrorCode.PARSE_EXPECTED, d.code());
    assertEquals(Map.of("token", "token.identifier"), d.params());
    assertEquals(line, d.line());
    assertEquals(column, d.column());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"approve", "ölwert", "Ölwert", "राशि"})
  void lowercaseAndCaselessRuleNamesStayAccepted(String name) {
    assertAccepted("Module m.\n\nRule " + name + ", produce Int:\n  Return 1.\n");
  }

  @Test
  void chineseRuleNameStaysAccepted() {
    assertEquals(List.of(), parse("模块 m。\n\n规则 审批，产出 整数：\n  返回 1。\n", "zh-CN").diagnostics());
  }

  // ---------------------------------------------------------------- 4. 导入关键词大小写

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"Use", "use", "USE", "uSe", "USe"})
  void useKeywordIsCaseInsensitive(String keyword) {
    var r = parse("Module m.\n\n" + keyword + " lib.status as Status.\n");
    assertEquals(List.of(), r.diagnostics(), keyword);
    Decl.Import imp = assertInstanceOf(Decl.Import.class, r.module().decls().get(0));
    assertEquals("lib.status", imp.path());
    assertEquals("Status", imp.alias());
  }

  @Test
  void useRemainsUsableAsName() {
    // `USE` 没有升为硬关键词：作 Data 名、形参类型照旧可用（ts 同样接受）
    assertAccepted("Module m.\n\nDefine USE has x as Int.\n\nRule pick given u as USE, produce Int:\n  Return u.x.\n");
  }

  // ---------------------------------------------------------------- 5. 下划线开头的标识符

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      Rule f given _x as Int, produce Int:\\n  Return 1.           | 3 | 14
      Rule f given x as Int, produce Int:\\n  Let _y be x.\\n  Return x. | 4 | 7
      Rule f given x as Int, produce Int:\\n  Let _ be x.\\n  Return x.  | 4 | 7
      Rule f given x as Int, produce Int:\\n  Return x plus _.         | 4 | 17
      Define _Box has a as Int.                                     | 3 | 8
      """)
  void leadingUnderscoreIsE050(String decl, int line, int column) {
    List<ParseDiagnostic> diags = parse("Module m.\n\n" + decl.replace("\\n", "\n") + "\n").diagnostics();
    ParseDiagnostic d = diags.get(0);
    assertEquals(ErrorCode.PARSE_UNEXPECTED_CHARACTER, d.code(), diags.toString());
    assertEquals(Map.of("char", "'_'"), d.params());
    assertEquals(line, d.line());
    assertEquals(column, d.column());
  }

  @Test
  void innerAndTrailingUnderscoresStayAccepted() {
    assertAccepted("Module m.\n\nDefine Box_A has a_b as Int, c_ as Int.\n\n"
        + "Rule f_g given x_1 as Int, produce Int:\n  Let y_ be x_1.\n  Return y_.\n");
  }

  /**
   * 冠词与下划线、数字相邻时是标识符的一段，不剥（与 ts 的 {@code \b…\b} 一致）。此前 `_` 属于 \p{Punct}，
   * {@code a_b} 被剥成 {@code _b}、{@code Box_A has} 被剥成 {@code Box_has}；下划线开头改为 E050 之后它们会直接报错。
   */
  @Test
  void articlesInsideIdentifiersAreKept() {
    var r = parse("Module m.\n\nDefine Box_A has a_b as Int, the_total as Int, x_an as Int, n1a as Int.\n\n"
        + "Rule f given a_x as Box_A, produce Int:\n  Let the_y be a_x.a_b.\n  Return the_y.\n");
    assertEquals(List.of(), r.diagnostics());
    Decl.Data box = assertInstanceOf(Decl.Data.class, r.module().decls().get(0));
    assertEquals("Box_A", box.name());
    assertEquals(List.of("a_b", "the_total", "x_an", "n1a"), box.fields().stream().map(Decl.Field::name).toList());
    Decl.Func f = assertInstanceOf(Decl.Func.class, r.module().decls().get(1));
    assertEquals("a_x", f.params().get(0).name());
    assertEquals("the_y", assertInstanceOf(Stmt.Let.class, f.body().statements().get(0)).name());
  }

  @Test
  void standaloneArticlesAreStillRemoved() {
    var r = parse("Module m.\n\nRule f given x as Int, produce Int:\n  Let y be the x.\n  Return y.\n");
    assertEquals(List.of(), r.diagnostics());
  }

  // ---------------------------------------------------------------- 6. lambda 单行体

  @Test
  void inlineLambdaBodyIsOneReturnBlock() {
    var r = parse("Module m.\n\nRule f given x as Int, produce Int:\n"
        + "  Let g be function given y as Int, produce Int: Return y plus 1.\n  Return g(x).\n");
    assertEquals(List.of(), r.diagnostics());
    Decl.Func f = assertInstanceOf(Decl.Func.class, r.module().decls().get(0));
    Stmt.Let let = assertInstanceOf(Stmt.Let.class, f.body().statements().get(0));
    Expr.Lambda lambda = assertInstanceOf(Expr.Lambda.class, let.expr());
    assertEquals(1, lambda.body().statements().size());
    assertInstanceOf(Stmt.Return.class, lambda.body().statements().get(0));
    assertEquals(lambda.span(), lambda.body().span());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "Let g be function with y as Int, produce: Return y.\\n  Return g(x).",
      "Let g be function, produce Int: Return 7.\\n  Return g().",
      "Return List.length(List.map([x], function given a as Int, produce Int: Return a.)).",
  })
  void inlineLambdaFormsAreAccepted(String body) {
    assertAccepted("Module m.\n\nRule f given x as Int, produce Int:\n  " + body.replace("\\n", "\n") + "\n");
  }

  // ---------------------------------------------------------------- 7. X of A 之后的裸小写名字

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      Rule f given x as Pair of Int, y, produce Int:\\n  Return 1.            | 3 | 32 | y
      Rule f given x as Pair of Int and y, produce Int:\\n  Return 1.        | 3 | 35 | y
      Rule f given x as Pair of Int, y:\\n  Return 1.                         | 3 | 32 | y
      Rule f given x as Pair of Int, @audit y, produce Int:\\n  Return 1.     | 3 | 39 | y
      Rule f given x as result of Int, y, produce Int:\\n  Return 1.          | 3 | 34 | y
      Rule f given x as Pair of Int, y required, produce Int:\\n  Return 1.   | 3 | 32 | y
      Define B has x as Pair of Int, y.                                       | 3 | 32 | y
      Rule f given x as Int, produce Int:\\n  Let g be function given a as Pair of Int, b, produce Int:\\n    Return 1.\\n  Return x. | 4 | 45 | b
      """)
  void bareLowercaseNameAfterOfTypeArgsIsE055(String decl, int line, int column, String name) {
    ParseDiagnostic d = onlyDiagnostic("Module m.\n\nDefine Pair has a as Int.\n\n" + decl.replace("\\n", "\n") + "\n");
    assertEquals(ErrorCode.PARSE_EXPECTED, d.code());
    assertEquals(Map.of("token", "token.typeName"), d.params());
    assertEquals("Expected type name: '" + name + "' would be read as another type argument; write '" + name
        + " as <Type>' or wrap the generic type in parentheses", d.message());
    assertEquals(line + 2, d.line());
    assertEquals(column, d.column());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "given x as (Pair of Int), y, produce Int",
      "given x as Pair of Int, y as Text, produce Int",
      "given x as Pair of Int, @pii(L1, email) y as Text, produce Int",
      "given x as Pair of Int, Text, produce Int",
      "given x as Pair of Int, @pii(L1, email) Text, produce Int",
      "given x as Pair of Int, maybe Int, produce Int",
      "given x as Pair of Int, list of Int, produce Int",
      "given x as list of Int, y, produce Int",
      "given x as option of Int, y, produce Int",
  })
  void unambiguousFormsStayAccepted(String params) {
    assertAccepted("Module m.\n\nDefine Pair has a as Int.\n\nRule f " + params + ":\n  Return 1.\n");
  }
}
