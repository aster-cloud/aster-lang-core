package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import aster.core.ast.Decl;
import aster.core.lexicon.LexiconRegistry;
import aster.core.typecheck.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 规则签名与类型别名的双引擎语法边界（aster-lang-ts#328，1.0.48 parity 清扫遗留）。
 *
 * <ul>
 *   <li>尖括号泛型 {@code Pair<Int, Text>}：core 此前接受，ts 报 E056。裁决两引擎都拒绝，在 {@code <} 上报 E056，
 *       正式写法是 {@code Pair of Int and Text}。</li>
 *   <li>大写形参名 {@code given Customer as Text}：core 此前接受（nameIdent 含 TYPE_IDENT），{@code list of Int and Text}
 *       的 Text 还会被读成无类型形参；ts 报 E055。裁决两引擎都报 E055。</li>
 *   <li>{@code result of A or B}：core 一直拒绝（{@code or} 不是失败类型分隔符），ts 此前接受；这里锁住 core 侧。</li>
 *   <li>首字母大写的类型别名关键词 {@code Type Amount as X.}：core 此前拒绝，ts 接受。关键词大小写在 Define / Rule / Use
 *       等处两引擎都不敏感，裁决 core 接受；{@code Type} 不升为硬关键词，作类型名时照旧可用。</li>
 * </ul>
 */
class SignatureSyntaxParityTest {

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

  // ---------------------------------------------------------------- 尖括号泛型

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      Rule f given x as Pair<Int, Text>, produce Int:\\n  Return 1.                | 3 | 23
      Rule f given x as Int, produce List<Int>:\\n  Return [x].                  | 3 | 36
      Rule f given x as list of Pair<Int>, produce Int:\\n  Return 1.              | 3 | 31
      Rule f given g as (Pair<Int>) -> Bool, produce Int:\\n  Return 1.            | 3 | 24
      Define Box has p as Pair<Int, Text>.                                           | 3 | 25
      type Amount as Pair<Int>.                                                      | 3 | 20
      Rule f given x as pair<Int>, produce Int:\\n  Return 1.                       | 3 | 23
      Rule f given x as Int, produce Int:\\n  Let g be function given y as Box<Int>, produce Int:\\n    Return 1.\\n  Return x. | 4 | 35
      """)
  void angleBracketGenericIsE056(String body, int line, int column) {
    ParseDiagnostic d = onlyDiagnostic("Module m.\n\n" + body.replace("\\n", "\n") + "\n");
    assertEquals(ErrorCode.PARSE_EXPECTED_AFTER, d.code());
    assertEquals(Map.of("token", "'of'", "after", "token.typeName"), d.params());
    assertEquals(line, d.line());
    assertEquals(column, d.column());
  }

  @Test
  void ofGenericIsTheOfficialForm() {
    var r = parse("Module m.\n\nRule f given x as Pair of Int and Text, produce Int:\n  Return 1.\n");
    assertEquals(List.of(), r.diagnostics());
  }

  // ---------------------------------------------------------------- 大写形参名

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      given Customer as Text                | 14
      given x as Int and Customer as Text   | 27
      given x as Int, Customer              | 24
      given x as list of Int and Text       | 35
      given Text as Int                     | 14
      """)
  void capitalizedParameterNameIsE055(String params, int column) {
    ParseDiagnostic d = onlyDiagnostic("Module m.\n\nRule f " + params + ", produce Int:\n  Return 1.\n");
    assertEquals(ErrorCode.PARSE_EXPECTED, d.code());
    assertEquals(Map.of("token", "token.identifier"), d.params());
    assertEquals(3, d.line());
    assertEquals(column, d.column());
  }

  @Test
  void capitalizedLambdaParameterNameIsE055() {
    ParseDiagnostic d = onlyDiagnostic("Module m.\n\nRule f given x as Int, produce Int:\n"
        + "  Let g be function given Y as Int, produce Int:\n    Return 1.\n  Return x.\n");
    assertEquals(ErrorCode.PARSE_EXPECTED, d.code());
    assertEquals(4, d.line());
  }

  /** 只看首字符是否为 ASCII 大写，与 ts 词法的 TYPE_IDENT 判据一致：Latin 扩展大写、天城文、中文名字照常接受。 */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"customer", "Ölwert", "राशि"})
  void nonAsciiUppercaseParameterNamesStayAccepted(String name) {
    var r = parse("Module m.\n\nRule f given " + name + " as Text, produce Text:\n  Return " + name + ".\n");
    assertEquals(List.of(), r.diagnostics(), name);
  }

  @Test
  void chineseParameterNameStaysAccepted() {
    var r = parse("模块 m。\n\n规则 f 给定 客户 作为 文本，产出 文本：\n  返回 客户。\n", "zh-CN");
    assertEquals(List.of(), r.diagnostics());
  }

  @Test
  void capitalizedFieldAndLetNamesAreOutOfScope() {
    // 本裁决只针对形参；字段名 / Let 名的大小写另行处理，这里锁住「不受影响」
    var r = parse("Module m.\n\nDefine Box has Code as Text.\n\nRule f given x as Int, produce Int:\n"
        + "  Let Low be x.\n  Return Low.\n");
    assertEquals(List.of(), r.diagnostics());
  }

  // ---------------------------------------------------------------- result of … or

  @Test
  void resultOrIsRejected() {
    List<ParseDiagnostic> diags =
        parse("Module m.\n\nRule f given x as result of Int or Text, produce Int:\n  Return 1.\n").diagnostics();
    assertEquals(ErrorCode.PARSE_UNEXPECTED_EXPECTING, diags.get(0).code());
    assertEquals(3, diags.get(0).line());
  }

  // ---------------------------------------------------------------- 类型别名关键词大小写

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"type", "Type", "TYPE"})
  void typeAliasKeywordIsCaseInsensitive(String keyword) {
    var r = parse("Module m.\n\n" + keyword + " Amount as Int.\n\nRule f given a as Amount, produce Int:\n  Return 1.\n");
    assertEquals(List.of(), r.diagnostics(), keyword);
    Decl.TypeAlias alias = assertInstanceOf(Decl.TypeAlias.class, r.module().decls().get(0));
    assertEquals("Amount", alias.name());
  }

  @Test
  void annotatedCapitalizedTypeAlias() {
    var r = parse("Module m.\n\n@pii Type Email as Text.\n");
    assertEquals(List.of(), r.diagnostics());
    assertEquals(List.of("pii"), assertInstanceOf(Decl.TypeAlias.class, r.module().decls().get(0)).annotations());
  }

  @Test
  void typeRemainsUsableAsTypeName() {
    // `Type` 没有升为硬关键词：作 Data 名、字段类型、形参类型都照旧可用（ts 同样接受）
    var r = parse("Module m.\n\nDefine Type has code as Text.\n\nDefine Box has kind as Type.\n\n"
        + "Rule f given t as Type, produce Text:\n  Return t.code.\n");
    assertEquals(List.of(), r.diagnostics());
  }

  @Test
  void otherCapitalizedWordIsStillNotATypeAlias() {
    List<ParseDiagnostic> diags = parse("Module m.\n\nKind Amount as Int.\n").diagnostics();
    assertEquals(3, diags.get(0).line());
  }

  // ---------------------------------------------------------------- 前缀可空 maybe

  /** {@code maybe T} 是 lexicon 的类型构造关键词（MAYBE，与 option of / result of 同类），≡ {@code T?}；此前 core 文法没有这条规则。 */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      maybe Text                    | Maybe(Text)
      Maybe Text                    | Maybe(Text)
      Maybe @pii(L1, name) Text     | Maybe(@pii Text)
      maybe list of Int             | Maybe(List)
      maybe Text?                   | Maybe(Maybe(Text))
      """)
  void prefixMaybeIsAccepted(String type, String expected) {
    var r = parse("Module m.\n\nDefine U has n as " + type + ".\n");
    assertEquals(List.of(), r.diagnostics(), type);
    var field = ((Decl.Data) r.module().decls().get(0)).fields().get(0);
    assertEquals(expected, showMaybe(field.type()));
  }

  private static String showMaybe(aster.core.ast.Type t) {
    return switch (t) {
      case aster.core.ast.Type.Maybe m -> "Maybe(" + showMaybe(m.type()) + ")";
      case aster.core.ast.Type.TypeName n -> (n.annotations().isEmpty() ? "" : "@pii ") + n.name();
      case aster.core.ast.Type.List l -> "List";
      default -> t.getClass().getSimpleName();
    };
  }

  @Test
  void prefixMaybeInChinese() {
    var r = parse("模块 m。\n\n定义 用户 包含 名字 作为 可选 文本。\n", "zh-CN");
    assertEquals(List.of(), r.diagnostics());
    assertInstanceOf(aster.core.ast.Type.Maybe.class, ((Decl.Data) r.module().decls().get(0)).fields().get(0).type());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"maybe of Text", "Maybe of Text"})
  void maybeOfIsE061(String type) {
    ParseDiagnostic d = onlyDiagnostic("Module m.\n\nDefine U has n as " + type + ".\n");
    assertEquals(ErrorCode.PARSE_UNEXPECTED_TOKEN, d.code());
    assertEquals(Map.of("token", "'of'"), d.params());
    assertEquals(3, d.line());
    assertEquals(25, d.column());
  }

  @Test
  void maybeRemainsUsableAsName() {
    var r = parse("Module m.\n\nDefine U has maybe as Int.\n\nRule f given maybe as Int, produce Int:\n  Return maybe.\n");
    assertEquals(List.of(), r.diagnostics());
  }
}
