package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * "Unknown call target: Driver" 失败。期望在解析期给出**恰好一条**诊断。
 *
 * <p>诊断列号为 ANTLR 的 0 起算列；TS 为 1 起算，故 TS 报告的列 = 此处列 + 1。
 */
class ConstructWithSetToParityTest {

  private static final String MSG = "Expected 'set to' in construction";

  /** 一条语法诊断：行、0 起算列、出错记号文本、消息。 */
  private record Diag(int line, int col, String token, String msg) {
    static Diag setTo(int line, int col, String token) {
      return new Diag(line, col, token, MSG);
    }
  }

  /** 一次解析的结果：按报告顺序的诊断与成功时的 AST。 */
  private record Parsed(List<Diag> diags, Module module) {}

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
    List<Diag> diags = new ArrayList<>();
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg,
          RecognitionException e) {
        diags.add(new Diag(line, col, sym instanceof Token t ? t.getText() : String.valueOf(sym), msg));
      }
    });
    var moduleCtx = parser.module();
    Module module = diags.isEmpty() ? new AstBuilder().visitModule(moduleCtx) : null;
    return new Parsed(diags, module);
  }

  /** 英文样例：构造语句位于第 6 行。 */
  private static String en(String body) {
    return "Module t.\n\nDefine Driver with age as Int and rank as Text.\n\n"
        + "Rule r given person, produce:\n  " + body + "\n";
  }

  private static Expr returned(Parsed p) {
    var func = (Decl.Func) p.module().decls().get(p.module().decls().size() - 1);
    Stmt first = func.body().statements().get(0);
    return assertInstanceOf(Stmt.Return.class, first).expr();
  }

  private static void assertAccepted(Parsed p) {
    assertTrue(p.diags().isEmpty(), "应无诊断: " + p.diags());
  }

  // ---------- English：畸形构造恰好一条诊断（与 TS 同位置、同文案） ----------

  @Test
  void equalsFieldsYieldExactlyOneDiagnostic() {
    // TS: 1 条，6:26 "Expected 'set to' in construction"
    var p = parse(en("Return Driver with age = person.age, rank = Low."), "en-US");
    assertEquals(List.of(Diag.setTo(6, 25, "=")), p.diags());
  }

  @Test
  void equalsWithoutValueYieldsExactlyOneDiagnostic() {
    // TS: 1 条，6:26。畸形分支吞到语句句点为止，不再级联 "no viable alternative"。
    var p = parse(en("Return Driver with age =."), "en-US");
    assertEquals(List.of(Diag.setTo(6, 25, "=")), p.diags());
  }

  @Test
  void laterFieldWithEqualsIsRejectedAtThatField() {
    // TS: 1 条，6:41
    var p = parse(en("Return Driver with age set to 1, rank = 2."), "en-US");
    assertEquals(List.of(Diag.setTo(6, 40, "=")), p.diags());
  }

  @Test
  void fieldWithoutAnythingIsRejected() {
    // TS: 1 条，6:25（指向句点）
    var p = parse(en("Return Driver with age."), "en-US");
    assertEquals(List.of(Diag.setTo(6, 24, ".")), p.diags());
  }

  @Test
  void bareWithArgumentsIsRejected_noWithCallForm() {
    // TS: 1 条，6:21
    var p = parse(en("Return calc with x, x."), "en-US");
    assertEquals(List.of(Diag.setTo(6, 20, ",")), p.diags());
  }

  @Test
  void lowercaseHeadWithEqualsIsRejected() {
    var p = parse(en("Return driver with age = x."), "en-US");
    assertEquals(List.of(Diag.setTo(6, 25, "=")), p.diags());
  }

  @Test
  void laterUnrelatedErrorIsStillReported() {
    // 畸形构造（第 6 行）不得吞掉后面无关语句的错误（第 10 行）。TS: 6:28 + 10:16。
    var p = parse("Module t.\n\nDefine Driver with age as Int and rank as Text.\n\n"
        + "Rule r given person, produce:\n  Let d be Driver with age = 1.\n  Return d.\n\n"
        + "Rule s given x, produce:\n  Return x plus.\n", "en-US");
    assertEquals(2, p.diags().size(), p.diags().toString());
    assertEquals(Diag.setTo(6, 27, "="), p.diags().get(0));
    var later = p.diags().get(1);
    assertEquals(List.of(10, 15, "."), List.of(later.line(), later.col(), later.token()));
    assertTrue(later.msg().startsWith("mismatched input '.'"), later.msg());
  }

  // ---------- English：良构构造之后的错误按普通表达式错误报告，不误报 set to ----------

  @Test
  void missingValueAfterSetToIsAnOrdinaryError() {
    // TS: 1 条 "Unexpected expression" 6:32；Java 1 条，同位置，ANTLR 文案。
    var p = parse(en("Return Driver with age set to."), "en-US");
    assertEquals(1, p.diags().size(), p.diags().toString());
    var d = p.diags().get(0);
    assertEquals(List.of(6, 31, "."), List.of(d.line(), d.col(), d.token()));
    assertTrue(d.msg().startsWith("mismatched input '.'"), d.msg());
  }

  @Test
  void trailingJunkAfterSetToValueIsAnOrdinaryError() {
    // TS: 1 条 "Expected '.' at end of statement" 6:35（指向 extra）。
    // Java: 2 条，均在句点处——这是表达式层 `1 extra` 的既有恢复行为（`Return 1 extra.`
    // 同样如此），与构造无关。只钉条数与位置，ANTLR 原始文案不钉；关键是不得误报 set to。
    var p = parse(en("Return Driver with age set to 1 extra."), "en-US");
    assertEquals(2, p.diags().size(), p.diags().toString());
    for (Diag d : p.diags()) {
      assertEquals(List.of(6, 39, "."), List.of(d.line(), d.col(), d.token()), d.toString());
      assertTrue(!MSG.equals(d.msg()), "不得报构造 set to 错误: " + d);
    }
  }

  // ---------- English：接受的形式 ----------

  @Test
  void setToConstructionIsAccepted() {
    var p = parse(en("Return Driver with age set to person.age and rank set to \"Low\"."), "en-US");
    assertAccepted(p);
    var c = assertInstanceOf(Expr.Construct.class, returned(p));
    assertEquals("Driver", c.typeName());
    assertEquals(List.of("age", "rank"), c.fields().stream().map(Expr.Construct.ConstructField::name).toList());
  }

  @Test
  void lowercaseHeadSetToConstructionIsAccepted() {
    // TS 把后随 with 的小写名也当构造类型名。
    var p = parse(en("Return driver with age set to 1."), "en-US");
    assertAccepted(p);
    assertEquals("driver", assertInstanceOf(Expr.Construct.class, returned(p)).typeName());
  }

  @Test
  void parenCallIsAccepted() {
    var p = parse(en("Return calc(person, person)."), "en-US");
    assertAccepted(p);
    var call = assertInstanceOf(Expr.Call.class, returned(p));
    assertEquals("calc", assertInstanceOf(Expr.Name.class, call.target()).name());
    assertEquals(2, call.args().size());
  }

  @Test
  void hasIntroducedConstructionFollowsTheSameFieldRule() {
    // `has` 仍可引导构造（zh 包含 的规范化结果在 has/with 间不确定，见 AsterParser.g4
    // constructExpr 注释）；其字段规则与 with 相同。
    assertEquals(List.of(Diag.setTo(6, 24, "=")), parse(en("Return Driver has age = 1."), "en-US").diags());
    assertAccepted(parse(en("Return Driver has age set to 1."), "en-US"));
  }

  @Test
  void sllPredictionModeBehavesTheSame() {
    // aster-api 的 InProcessCnlParser 以 SLL 预测模式解析，结论须与默认 LL 一致。
    assertEquals(List.of(Diag.setTo(6, 25, "=")),
        parse(en("Return Driver with age = person.age, rank = Low."), "en-US", true).diags());
    assertEquals(List.of(Diag.setTo(6, 25, "=")), parse(en("Return Driver with age =."), "en-US", true).diags());
    assertEquals(List.of(Diag.setTo(6, 20, ",")), parse(en("Return calc with x, x."), "en-US", true).diags());
    var ok = parse(en("Return Driver with age set to 1 and rank set to \"Low\"."), "en-US", true);
    assertAccepted(ok);
    assertInstanceOf(Expr.Construct.class, returned(ok));
    assertInstanceOf(Expr.Call.class, returned(parse(en("Return calc(person)."), "en-US", true)));
  }

  @Test
  void astBuilderFailsFastWithoutErrorListener() {
    // 未挂错误监听器的调用方也不得拿到一棵把 `=` 字段当成别的东西的树。
    for (String body : new String[] {"Return Driver with age = 1.", "Return Driver with age set to 1, rank = 2."}) {
      var tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(
          new Canonicalizer().canonicalize(en(body)))));
      tokens.fill();
      tokens.seek(0);
      var parser = new AsterParser(tokens);
      parser.removeErrorListeners();
      var moduleCtx = parser.module();
      var ex = assertThrows(IllegalStateException.class, () -> new AstBuilder().visitModule(moduleCtx));
      assertTrue(ex.getMessage().startsWith(MSG), ex.getMessage());
    }
  }

  // ---------- Deutsch ----------

  private static String de(String body) {
    return "Modul t.\n\nDefiniere Fahrer mit alter als Ganzzahl.\n\n"
        + "Regel r gegeben x als Ganzzahl, liefert:\n  " + body + "\n";
  }

  @Test
  void germanEqualsFieldsAreRejected() {
    // 规范化后为 `Return Fahrer with alter = 35.`；TS: "Expected 'setze auf' in construction"
    var p = parse(de("gib zurueck Fahrer mit alter = 35."), "de-DE");
    assertEquals(List.of(Diag.setTo(6, 27, "=")), p.diags());
  }

  @Test
  void germanSetzeAufConstructionIsAccepted() {
    var p = parse(de("gib zurueck Fahrer mit alter setze auf 35."), "de-DE");
    assertAccepted(p);
    var c = assertInstanceOf(Expr.Construct.class, returned(p));
    assertEquals("Fahrer", c.typeName());
    assertEquals("alter", c.fields().get(0).name());
  }

  @Test
  void germanBareMitArgumentsIsRejected() {
    // 规范化后为 `Return rechne with x, x.`
    var p = parse(de("gib zurueck rechne mit x, x."), "de-DE");
    assertEquals(List.of(Diag.setTo(6, 22, ",")), p.diags());
  }

  // ---------- 中文 ----------

  private static String zh(String body) {
    return "模块 t。\n\n定义 决定 包含 批准，理由。\n\n规则 r 给定 x，产出：\n  " + body + "\n";
  }

  @Test
  void chineseEqualsFieldsAreRejected() {
    // 包含 规范化为 has 或 with（随进程不定），两者列号不同，故只钉行、记号与文案、条数。
    var p = parse(zh("返回 决定 包含 批准 = 真值，理由 = 「好」。"), "zh-CN");
    assertEquals(1, p.diags().size(), p.diags().toString());
    var d = p.diags().get(0);
    assertEquals(List.of(6, "=", MSG), List.of(d.line(), d.token(), d.msg()));
  }

  @Test
  void chineseSetToConstructionIsAccepted() {
    var p = parse(zh("返回 决定 包含 批准 将 设为 真值，理由 将 设为 「好」。"), "zh-CN");
    assertAccepted(p);
    var c = assertInstanceOf(Expr.Construct.class, returned(p));
    assertEquals("决定", c.typeName());
    assertEquals(List.of("批准", "理由"), c.fields().stream().map(Expr.Construct.ConstructField::name).toList());
  }

  // ---------- हिन्दी ----------

  private static String hi(String body) {
    return "मॉड्यूल t।\n\nपरिभाषित Driver सहित आयु रूप में पूर्णांक।\n\nनियम r दिया गया x, उत्पन्न:\n  "
        + body + "\n";
  }

  @Test
  void hindiEqualsFieldIsRejected() {
    // 规范化后为 `Return Driver with आयु = 35.`；TS: 6:26 "Expected 'निर्धारित को' in construction"
    var p = parse(hi("लौटाएं Driver सहित आयु = 35।"), "hi-IN");
    assertEquals(List.of(Diag.setTo(6, 25, "=")), p.diags());
  }

  @Test
  void hindiSetToConstructionIsAccepted() {
    var p = parse(hi("लौटाएं Driver सहित आयु निर्धारित को 35।"), "hi-IN");
    assertAccepted(p);
    var c = assertInstanceOf(Expr.Construct.class, returned(p));
    assertEquals("Driver", c.typeName());
    assertEquals("आयु", c.fields().get(0).name());
  }
}
