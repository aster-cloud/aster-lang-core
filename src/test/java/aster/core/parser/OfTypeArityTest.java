package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import aster.core.ast.Decl;
import aster.core.ast.Type;
import aster.core.lexicon.LexiconRegistry;
import aster.core.typecheck.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code X of} 的元数按名字确定，与 aster-lang-ts parseTypePrimary 一致（aster-lang-core#256）。
 *
 * <p>修复前文法一律按可变参数贪婪匹配 {@code (AND | COMMA) annotatedType}，{@code result of list of Int and Text}
 * 的 {@code and Text} 被内层 list 吞掉，AstBuilder 又只取 list 的第一个参数，Text 被静默丢弃，得到 {@code result<List(Int)>}；
 * ts 得到 {@code Result(List(Int), Text)}。另外 core 把 result / option 建成 TypeApp，ts 建成 Result / Option 节点，
 * 两端 Core IR 的 kind 不同。
 *
 * <p>期望写法是 ts 的结果：list / option 恰一个参数，result 一个成功类型加可选的失败类型（缺省 Text），其余泛型不限，
 * {@code and} / {@code ,} 归最内层还能接参数的那个。
 */
class OfTypeArityTest {

  private static ParseDiagnostics.Result parse(String params) {
    String source = "Module m.\n\nRule f given " + params + ", produce Int:\n  Return 1.\n";
    return ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow("en-US"));
  }

  /** 形参表渲染为 {@code x: Result(List(Int), Text); y: Text}，与 aster-lang-ts 的节点名一致。 */
  private static String paramsOf(String params) {
    ParseDiagnostics.Result r = parse(params);
    assertEquals(List.of(), r.diagnostics(), params + " 应正常解析");
    Decl.Func f = (Decl.Func) r.module().decls().stream().filter(d -> d instanceof Decl.Func).findFirst().orElseThrow();
    return f.params().stream().map(p -> p.name() + ": " + show(p.type())).collect(Collectors.joining("; "));
  }

  private static String show(Type t) {
    return switch (t) {
      case Type.TypeName n -> n.name();
      case Type.TypeVar v -> v.name();
      case Type.List l -> "List(" + show(l.type()) + ")";
      case Type.Option o -> "Option(" + show(o.type()) + ")";
      case Type.Maybe m -> "Maybe(" + show(m.type()) + ")";
      case Type.Result r -> "Result(" + show(r.ok()) + ", " + show(r.err()) + ")";
      case Type.Map m -> "Map(" + show(m.key()) + ", " + show(m.val()) + ")";
      case Type.TypeApp a -> a.base() + "<" + a.args().stream().map(OfTypeArityTest::show).collect(Collectors.joining(", ")) + ">";
      case Type.FuncType f -> "(" + f.params().stream().map(OfTypeArityTest::show).collect(Collectors.joining(", ")) + ") -> "
          + show(f.ret());
    };
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      x as result of list of Int and Text                 | x: Result(List(Int), Text)
      x as result of option of Int and Text               | x: Result(Option(Int), Text)
      x as result of Int and list of Text                 | x: Result(Int, List(Text))
      x as result of result of Int and Text and Bool      | x: Result(Result(Int, Text), Bool)
      x as Result of Int and Text                         | x: Result(Int, Text)
      x as result of Int                                  | x: Result(Int, Text)
      x as option of Int                                  | x: Option(Int)
      x as List of Int                                    | x: List(Int)
      x as list of Int?                                   | x: List(Maybe(Int))
      x as Map Text to list of Int                        | x: Map(Text, List(Int))
      x as Map list of Int to Text                        | x: Map(List(Int), Text)
      x as list of Int and y as Text                      | x: List(Int); y: Text
      x as option of Int and y as Text                    | x: Option(Int); y: Text
      x as result of Int and y as Text                    | x: Result(Int, Text); y: Text
      x as Pair of Int and Text and Bool                  | x: Pair<Int, Text, Bool>
      x as Pair of list of Int and Text                   | x: Pair<List(Int), Text>
      x as Pair of Pair of Int and Text                   | x: Pair<Pair<Int, Text>>
      x as Pair of Int and y as Text                      | x: Pair<Int>; y: Text
      x as (Int)                                          | x: Int
      x as (Int, Text) -> Bool                            | x: (Int, Text) -> Bool
      x as result of Int, Text                            | x: Result(Int, Text)
      x as result of Int, Text and y as Text              | x: Result(Int, Text); y: Text
      x as Pair of result of Int, Text                    | x: Pair<Result(Int, Text)>
      x as Pair of result of Int and Text, Bool           | x: Pair<Result(Int, Text), Bool>
      g as (list of Int, Text) -> Bool                    | g: (List(Int), Text) -> Bool
      g as (result of Int and Text) -> Bool               | g: (Result(Int, Text)) -> Bool
      g as ((Pair of Int), Text) -> Bool                  | g: (Pair<Int>, Text) -> Bool
      """)
  void ofTypesMatchTsArity(String params, String expected) {
    assertEquals(expected, paramsOf(params));
  }

  /**
   * 类型内预测切到 SLL（#254）后，类型末尾的 `,` / `and` 仍须按外层上下文判归属：后面是 `y as …` 时是形参分隔符。
   * 同一源码在调用方为 LL（core 各入口、truffle）与 SLL（aster-api）时都必须无语法错误且得到同一棵树。
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "x as Pair of Int, y as Text",
      "x as Pair of Int and Text, y as Int",
      "x as Pair of Int, Text, y as Int",
      "x as result of Int, y as Text",
      "x as result of Int and Text, y as Int",
      "x as list of Int, y as Text",
      "x as Map Text to Pair of Int, y as Text",
      "x as (Int) -> Pair of Int, y as Text",
  })
  void separatorAfterTypeKeepsOuterMeaningUnderBothModes(String params) {
    String source = "Module m.\n\nRule f given " + params + ", produce Int:\n  Return 1.\n";
    assertEquals(treeOf(source, PredictionMode.LL), treeOf(source, PredictionMode.SLL), params);
    assertEquals(List.of(), parse(params).diagnostics(), params);
  }

  /** 以指定预测模式解析；有语法错误即失败，返回参数表的语法树文本。 */
  private static String treeOf(String source, PredictionMode mode) {
    String canonical = new aster.core.canonicalizer.Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), null)
        .canonicalize(source);
    AsterParser parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical))));
    parser.removeErrorListeners();
    List<String> errors = new java.util.ArrayList<>();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object symbol, int line, int col, String msg, RecognitionException e) {
        errors.add(line + ":" + col + " " + msg);
      }
    });
    parser.getInterpreter().setPredictionMode(mode);
    String tree = parser.module().toStringTree(parser);
    assertEquals(List.of(), errors, mode + " 下不应有语法错误");
    return tree;
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      x as (Int, Text)              | token.functionParameterTypes          | Expected '->' after function parameter types
      x as (@pii(L1, email) Text)   | token.annotatedFunctionParameterType  | Expected '->' after annotated function parameter type
      """)
  void parenTypeWithoutArrowIsE056LikeTs(String params, String after, String message) {
    // 函数类型与括号类型合为一个分支（#254）后，`(` 内是类型表、`)` 后无 `->` 的写法由 AstBuilder 拒绝，码与文案同 ts
    List<ParseDiagnostic> diags = parse(params).diagnostics();
    assertEquals(1, diags.size(), params + ": " + diags);
    assertEquals(ErrorCode.PARSE_EXPECTED_AFTER, diags.get(0).code());
    assertEquals(Map.of("token", "'->'", "after", after), diags.get(0).params());
    assertEquals(message, diags.get(0).message());
    assertEquals(3, diags.get(0).line());
  }

  /**
   * 括号类型表里，`X of A, B` 的逗号既可以是 X 的下一个参数，也可以是类型表的下一项（parity 清扫 1.0.48）。
   * 文法按 SLL 贪婪归 X，ts 此前归类型表，两端 Core IR 不同；裁决为真歧义、两引擎都报 E064（写 `and` 或给 X 加括号）。
   * 元数已满的 X（list / option / 已有失败类型的 result）不歧义，见上面的正例。
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "g as (result of Int, Text) -> Bool",
      "g as (Pair of Int, Text) -> Bool",
      "g as (Map Text to Pair of Int, Text) -> Bool",
      "g as ((Int) -> Pair of Int, Text) -> Bool",
  })
  void commaInsideTypeListAfterOpenGenericIsAmbiguousE064(String params) {
    List<ParseDiagnostic> diags = parse(params).diagnostics();
    assertEquals(1, diags.size(), params + ": " + diags);
    assertEquals(ErrorCode.PARSE_UNEXPECTED_CONSTRUCT, diags.get(0).code());
    assertEquals(Map.of("construct", "','"), diags.get(0).params());
    assertEquals(3, diags.get(0).line());
  }
}
