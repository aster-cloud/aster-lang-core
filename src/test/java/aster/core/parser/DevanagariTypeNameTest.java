package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Pattern;
import aster.core.ast.Stmt;
import aster.core.ast.Type;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import aster.core.resolve.DeclarationResolver;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 无大小写文字（天城文）标识符可出现在类型名位置（issue #220，裁决 R53）。
 *
 * <p>天城文没有大小写，词法上与 TS 一致归 IDENT（见 {@link DevanagariProductionLexerTest}）；
 * Java 语法此前在类型声明名、枚举值、类型引用、构造模式处只收 TYPE_IDENT，于是 TS 接受的
 * {@code Define राशि has …} 在 Java 上语法报错。修复只放宽语法（{@code typeIdent} 规则带无大小写文字谓词），
 * 不改词法器：天城文变量在表达式位置仍是普通名字引用。
 *
 * <p>R53 的边界：只放宽无大小写文字。小写拉丁类型名（{@code Define acct …}）仍是语法错误——
 * 拉丁文字的作者可以写成大写，这一分歧不在本裁决范围。
 */
@DisplayName("天城文类型名（R53）")
class DevanagariTypeNameTest {

  /** 解析结果：语法错误 + 构建的 AST（有语法错误时为 null）。 */
  private record Parsed(List<String> errors, Module module) {}

  private static Parsed parse(String src, String lexiconId) {
    var lexicon = LexiconRegistry.getInstance().getOrThrow(lexiconId);
    String canonical = new Canonicalizer(lexicon, null).canonicalize(src);
    List<String> errors = new ArrayList<>();
    BaseErrorListener collect = new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        errors.add("L" + line + ":" + col + " " + msg);
      }
    };
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    lexer.removeErrorListeners();
    lexer.addErrorListener(collect);
    var parser = new AsterParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    parser.addErrorListener(collect);
    var tree = parser.module();
    return new Parsed(errors, errors.isEmpty() ? new AstBuilder().visitModule(tree) : null);
  }

  private static Module parseOk(String src) {
    return parseOk(src, "en-US");
  }

  private static Module parseOk(String src, String lexiconId) {
    Parsed p = parse(src, lexiconId);
    assertEquals(List.of(), p.errors(), "不应有语法错误:\n" + src);
    return p.module();
  }

  private static <T extends Decl> T decl(Module m, int i, Class<T> kind) {
    return assertInstanceOf(kind, m.decls().get(i), "decls[" + i + "]");
  }

  private static Type.TypeName typeName(Type t) {
    return assertInstanceOf(Type.TypeName.class, t);
  }

  @Test
  void dataDeclarationNameAndParameterType() {
    Module m = parseOk("Module p.\n\nDefine राशि has x as Int.\n\n"
        + "Rule r given a as राशि, produce Int:\n  Return a.x.\n");
    assertEquals("राशि", decl(m, 0, Decl.Data.class).name());
    var func = decl(m, 1, Decl.Func.class);
    assertEquals("राशि", typeName(func.params().get(0).type()).name());
  }

  @Test
  void dataDeclarationWithoutDefineKeyword() {
    Module m = parseOk("Module p.\n\nराशि has x as Int.\n");
    assertEquals("राशि", decl(m, 0, Decl.Data.class).name());
  }

  @Test
  void enumDeclarationNameAndVariants() {
    Module m = parseOk("Module p.\n\nDefine रंग as one of लाल, हरा or नीला.\n");
    var e = decl(m, 0, Decl.Enum.class);
    assertEquals("रंग", e.name());
    assertEquals(List.of("लाल", "हरा", "नीला"), e.variants());
  }

  @Test
  void mixedCaseScriptsInOneVariantList() {
    var e = decl(parseOk("Module p.\n\nDefine Farbe as one of Rot, हरा.\n"), 0, Decl.Enum.class);
    assertEquals(List.of("Rot", "हरा"), e.variants());
  }

  @Test
  void typeReferencesInsideGenericOptionAndMapPositions() {
    Module m = parseOk("Module p.\n\nDefine राशि has x as Int.\n\n"
        + "Rule r given xs as List of राशि, m as Map Text to राशि, produce राशि?:\n  Return null.\n");
    var func = decl(m, 1, Decl.Func.class);
    var list = assertInstanceOf(Type.List.class, func.params().get(0).type());
    assertEquals("राशि", typeName(list.type()).name());
    var map = assertInstanceOf(Type.Map.class, func.params().get(1).type());
    assertEquals("राशि", typeName(map.val()).name());
    var maybe = assertInstanceOf(Type.Maybe.class, func.retType());
    assertEquals("राशि", typeName(maybe.type()).name());
  }

  @Test
  void bareVariantPatternStaysPatternNameAndCtorPatternDestructures() {
    Module m = parseOk("Module p.\n\nDefine रंग as one of लाल, हरा.\n\nDefine राशि has x as Int.\n\n"
        + "Rule r given c as रंग, produce Int:\n  Match c:\n    When लाल, Return 1.\n    When हरा, Return 2.\n\n"
        + "Rule s given a as राशि, produce Int:\n  Match a:\n    When राशि(v), Return v.\n");
    var match = assertInstanceOf(Stmt.Match.class, decl(m, 2, Decl.Func.class).body().statements().get(0));
    // 与 TS 一致：无括号的 IDENT 是 PatternName（按位置解析为枚举成员），不是无参构造模式
    assertEquals("लाल", assertInstanceOf(Pattern.PatternName.class, match.cases().get(0).pattern()).name());
    var ctorMatch = assertInstanceOf(Stmt.Match.class, decl(m, 3, Decl.Func.class).body().statements().get(0));
    var ctor = assertInstanceOf(Pattern.PatternCtor.class, ctorMatch.cases().get(0).pattern());
    assertEquals("राशि", ctor.typeName());
    assertEquals(List.of("v"), ctor.names());
  }

  @Test
  void constructionWithDevanagariTypeName() {
    Module m = parseOk("Module p.\n\nDefine राशि has x as Int.\n\n"
        + "Rule r, produce राशि:\n  Return राशि with x set to 1.\n");
    var ret = assertInstanceOf(Stmt.Return.class, decl(m, 1, Decl.Func.class).body().statements().get(0));
    assertEquals("राशि", assertInstanceOf(Expr.Construct.class, ret.expr()).typeName());
  }

  @Test
  void devanagariVariablesAndMembersAreStillPlainNames() {
    // 反向护栏：不改词法器——天城文在表达式位置仍是 Name，不会变成构造器/类型引用。
    Module m = parseOk("Module p.\n\nDefine Acct has राशि as Int.\n\n"
        + "Rule जाँच given खाता as Acct, produce Int:\n  Let मान be खाता.राशि.\n  Return मान.\n");
    assertEquals("राशि", decl(m, 0, Decl.Data.class).fields().get(0).name());
    var func = decl(m, 1, Decl.Func.class);
    assertEquals("जाँच", func.name());
    assertEquals("खाता", func.params().get(0).name());
    Block body = func.body();
    var let = assertInstanceOf(Stmt.Let.class, body.statements().get(0));
    assertEquals("खाता.राशि", assertInstanceOf(Expr.Name.class, let.expr()).name());
    var ret = assertInstanceOf(Stmt.Return.class, body.statements().get(1));
    assertEquals("मान", assertInstanceOf(Expr.Name.class, ret.expr()).name());
  }

  @Test
  void lexerStillProducesIdentForDevanagari() {
    var lexer = new AsterCustomLexer(CharStreams.fromString("राशि"));
    Token t = lexer.nextToken();
    assertEquals(AsterLexer.IDENT, t.getType(), "R53 只放宽语法，天城文仍词法为 IDENT（与 TS 一致）");
  }

  @Test
  void lowercaseLatinTypeNameIsStillRejected() {
    // R53 只覆盖无大小写文字；小写拉丁类型名不在放宽范围内。
    assertFalse(parse("Module p.\n\nDefine acct has x as Int.\n", "en-US").errors().isEmpty());
    assertFalse(parse("Module p.\n\nDefine choice as one of red, blue.\n", "en-US").errors().isEmpty());
  }

  @Test
  void undeclaredDevanagariTypeReportsE800InsteadOfSyntaxError() {
    Module m = parseOk("Module p.\n\nRule r given a as राशि, produce Int:\n  Return 1.\n");
    var r = DeclarationResolver.resolve(m, ResolutionContext.none());
    assertEquals(List.of(ErrorCode.UNRESOLVED_TYPE), r.diagnostics().stream().map(d -> d.code()).toList());
    assertTrue(r.diagnostics().get(0).message().contains("राशि"), r.diagnostics().get(0).message());
  }

  @Test
  void hindiKeywordsWithDandaTerminators() {
    Module m = parseOk("मॉड्यूल बीमा।\n\nपरिभाषित स्तर इनमें से एक उच्च, निम्न।\n\n"
        + "परिभाषित ग्राहक रखता है श्रेणी रूप में स्तर, आयु रूप में पूर्णांक।\n\n"
        + "नियम आयु_देखें दिया गया c रूप में ग्राहक, उत्पन्न पूर्णांक:\n  लौटाएं c.आयु।\n", "hi-IN");
    var level = decl(m, 0, Decl.Enum.class);
    assertEquals("स्तर", level.name());
    assertEquals(List.of("उच्च", "निम्न"), level.variants());
    var customer = decl(m, 1, Decl.Data.class);
    assertEquals("ग्राहक", customer.name());
    assertEquals("स्तर", typeName(customer.fields().get(0).type()).name());
    assertEquals("ग्राहक", typeName(decl(m, 2, Decl.Func.class).params().get(0).type()).name());
  }
}
