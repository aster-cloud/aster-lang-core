package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Stmt;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.junit.jupiter.api.Test;

/**
 * 构造表达式只接受 `set to` 字段，且不存在 `f with x` 调用形式——与 TS 解析器一致
 * （aster-lang-ts#244）。
 *
 * <p>缺陷形态：`Driver with age = x, rank = Low` 曾被 ANTLR 回退成 TypeIdentExpr +
 * WithCallSuffix，得到 `Call(Driver, [age = x, rank = Low])`，编译通过、运行期才以
 * "Unknown call target: Driver" 失败。期望在解析期就给出诊断。
 */
class ConstructWithSetToParityTest {

  private static final String MSG = "Expected 'set to' in construction";

  /** 一次解析的结果：语法错误（含出错记号文本）与成功时的 AST。 */
  private record Parsed(List<String> errors, List<String> offending, Module module) {}

  private static Parsed parse(String src, String locale) {
    return parse(src, locale, false);
  }

  private static Parsed parse(String src, String locale, boolean sll) {
    var lexicon = LexiconRegistry.getInstance().getOrThrow(locale);
    String canonical = new Canonicalizer(lexicon).canonicalize(src);
    var tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical)));
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    if (sll) {
      parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
    }
    List<String> errors = new ArrayList<>();
    List<String> offending = new ArrayList<>();
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg,
          RecognitionException e) {
        errors.add(msg);
        offending.add(sym instanceof Token t ? t.getText() : String.valueOf(sym));
      }
    });
    var moduleCtx = parser.module();
    Module module = errors.isEmpty() ? new AstBuilder().visitModule(moduleCtx) : null;
    return new Parsed(errors, offending, module);
  }

  private static String en(String body) {
    return "Module t.\n\nDefine Driver with age as Int and rank as Text.\n\n"
        + "Rule r given x as Int, produce:\n  " + body + "\n";
  }

  private static Expr returned(Parsed p) {
    var func = (Decl.Func) p.module().decls().get(p.module().decls().size() - 1);
    Stmt first = func.body().statements().get(0);
    return assertInstanceOf(Stmt.Return.class, first).expr();
  }

  // ---------- English ----------

  @Test
  void equalsFieldsAreRejectedAtParseTime() {
    var p = parse(en("Return Driver with age = x, rank = \"Low\"."), "en-US");
    assertFalse(p.errors().isEmpty(), "`=` 构造字段必须在解析期报错");
    assertEquals(MSG, p.errors().get(0));
    assertEquals("=", p.offending().get(0), "与 TS 一致，错误定位在应出现 `set` 的位置");
  }

  @Test
  void lowercaseHeadWithEqualsIsRejected() {
    var p = parse(en("Return driver with age = x."), "en-US");
    assertEquals(List.of(MSG), p.errors().subList(0, 1));
  }

  @Test
  void bareWithArgumentsIsRejected_noWithCallForm() {
    var p = parse(en("Return calc with x, x."), "en-US");
    assertFalse(p.errors().isEmpty(), "`calc with x, x` 不是调用，须报错");
    assertEquals(MSG, p.errors().get(0));
    assertEquals(",", p.offending().get(0));
  }

  @Test
  void qualifiedTargetWithArgumentsIsRejected() {
    var p = parse(en("Return T.upper with x."), "en-US");
    assertFalse(p.errors().isEmpty(), "`T.upper with x` 不是调用，须报错");
  }

  @Test
  void hasDoesNotIntroduceConstruction() {
    // TS 只用 `with` 引导构造；`has` 仅用于类型定义。
    var p = parse(en("Return Driver has age set to x."), "en-US");
    assertFalse(p.errors().isEmpty(), "`X has f set to v` 两引擎都应拒绝");
  }

  @Test
  void setToConstructionIsAccepted() {
    var p = parse(en("Return Driver with age set to x and rank set to \"Low\"."), "en-US");
    assertTrue(p.errors().isEmpty(), "set to 构造应通过: " + p.errors());
    var c = assertInstanceOf(Expr.Construct.class, returned(p));
    assertEquals("Driver", c.typeName());
    assertEquals(List.of("age", "rank"), c.fields().stream().map(Expr.Construct.ConstructField::name).toList());
  }

  @Test
  void lowercaseHeadSetToConstructionIsAccepted() {
    // TS 把后随 with 的小写名也当构造类型名。
    var p = parse(en("Return driver with age set to x."), "en-US");
    assertTrue(p.errors().isEmpty(), p.errors().toString());
    assertEquals("driver", assertInstanceOf(Expr.Construct.class, returned(p)).typeName());
  }

  @Test
  void parenCallIsAccepted() {
    var p = parse(en("Return calc(x, x)."), "en-US");
    assertTrue(p.errors().isEmpty(), p.errors().toString());
    var call = assertInstanceOf(Expr.Call.class, returned(p));
    assertEquals("calc", assertInstanceOf(Expr.Name.class, call.target()).name());
    assertEquals(2, call.args().size());
  }

  @Test
  void sllPredictionModeBehavesTheSame() {
    // aster-api 的 InProcessCnlParser 以 SLL 预测模式解析，结论须与默认 LL 一致。
    var bad = parse(en("Return Driver with age = x, rank = \"Low\"."), "en-US", true);
    assertEquals(MSG, bad.errors().get(0));
    assertEquals("=", bad.offending().get(0));
    assertEquals(MSG, parse(en("Return calc with x, x."), "en-US", true).errors().get(0));
    var ok = parse(en("Return Driver with age set to x and rank set to \"Low\"."), "en-US", true);
    assertTrue(ok.errors().isEmpty(), ok.errors().toString());
    assertInstanceOf(Expr.Construct.class, returned(ok));
    var call = parse(en("Return calc(x, x)."), "en-US", true);
    assertInstanceOf(Expr.Call.class, returned(call));
  }

  @Test
  void astBuilderFailsFastWithoutErrorListener() {
    // 未挂错误监听器的调用方也不得拿到一棵把 `=` 字段当成别的东西的树。
    var tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(
        new Canonicalizer().canonicalize(en("Return Driver with age = x.")))));
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var moduleCtx = parser.module();
    var ex = assertThrows(IllegalStateException.class, () -> new AstBuilder().visitModule(moduleCtx));
    assertTrue(ex.getMessage().startsWith(MSG), ex.getMessage());
  }

  // ---------- Deutsch ----------

  private static String de(String body) {
    return "Modul t.\n\nDefiniere Fahrer mit alter als Ganzzahl.\n\n"
        + "Regel r gegeben x als Ganzzahl, liefert:\n  " + body + "\n";
  }

  @Test
  void germanEqualsFieldsAreRejected() {
    var p = parse(de("gib zurueck Fahrer mit alter = 35."), "de-DE");
    assertFalse(p.errors().isEmpty(), "de `=` 构造字段必须在解析期报错");
    assertEquals(MSG, p.errors().get(0));
  }

  @Test
  void germanSetzeAufConstructionIsAccepted() {
    var p = parse(de("gib zurueck Fahrer mit alter setze auf 35."), "de-DE");
    assertTrue(p.errors().isEmpty(), p.errors().toString());
    var c = assertInstanceOf(Expr.Construct.class, returned(p));
    assertEquals("Fahrer", c.typeName());
    assertEquals("alter", c.fields().get(0).name());
  }

  @Test
  void germanBareMitArgumentsIsRejected() {
    var p = parse(de("gib zurueck rechne mit x, x."), "de-DE");
    assertFalse(p.errors().isEmpty(), "de `rechne mit x, x` 不是调用，须报错");
    assertEquals(MSG, p.errors().get(0));
  }

  // ---------- 中文 ----------

  private static String zh(String body) {
    return "模块 t。\n\n定义 决定 包含 批准，理由。\n\n规则 r 给定 x，产出：\n  " + body + "\n";
  }

  @Test
  void chineseEqualsFieldsAreRejected() {
    var p = parse(zh("返回 决定 包含 批准 = 真值，理由 = 「好」。"), "zh-CN");
    assertFalse(p.errors().isEmpty(), "zh `=` 构造字段必须在解析期报错");
    assertEquals(MSG, p.errors().get(0));
  }

  @Test
  void chineseSetToConstructionIsAccepted() {
    var p = parse(zh("返回 决定 包含 批准 将 设为 真值，理由 将 设为 「好」。"), "zh-CN");
    assertTrue(p.errors().isEmpty(), p.errors().toString());
    var c = assertInstanceOf(Expr.Construct.class, returned(p));
    assertEquals("决定", c.typeName());
    assertEquals(List.of("批准", "理由"), c.fields().stream().map(Expr.Construct.ConstructField::name).toList());
  }
}
