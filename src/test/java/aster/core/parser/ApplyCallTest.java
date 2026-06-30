package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 无括号单参调用 `apply <fn> to <arg>`（ADR 0027）—— Java 引擎。lower 成与 `fn(arg)`
 * **完全相同**的 Core IR（Call(Name,[arg])，零新节点）；与 TS 引擎双引擎一致由 tier1-parity 锁。
 * 软关键词（Codex 设计审查 019f1614）：APPLY 在 structKeywordName 放行，故 `Rule apply given …`
 * （函数名叫 apply）与 `apply(x)` 后缀调用不破——applyExpr 仅 `apply <名> to` 形态触发。
 */
class ApplyCallTest {

  /** 经 Canonicalizer → ANTLR → AstBuilder → CoreLowering，返回 Core IR。 */
  private CoreModel.Module lower(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var moduleCtx = parser.module();
    assertNotNull(moduleCtx, "解析 null: " + src);
    var ast = new AstBuilder().visitModule(moduleCtx);
    assertNotNull(ast, "AstBuilder null: " + src);
    return new CoreLowering().lowerModule(ast);
  }

  /** `apply …` 版与括号版的 Core IR 结构一致（origin/span 不入指纹）。 */
  private void assertSameIr(String applyForm, String parenForm) {
    assertEquals(fingerprint(lower(parenForm)), fingerprint(lower(applyForm)),
        "apply 版 IR 应与括号调用版完全一致");
  }

  private String fingerprint(CoreModel.Module m) {
    try {
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      var tree = mapper.valueToTree(m);
      stripOrigin(tree);
      return mapper.writeValueAsString(tree);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void stripOrigin(com.fasterxml.jackson.databind.JsonNode node) {
    if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
      obj.remove("origin");
      obj.remove("span");
      obj.fields().forEachRemaining(e -> stripOrigin(e.getValue()));
    } else if (node.isArray()) {
      node.forEach(this::stripOrigin);
    }
  }

  /** 解析并返回语法错误数（core 默认 error-recover 不抛，须用 listener 计数）。 */
  private int syntaxErrors(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var count = new int[]{0};
    parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener() {
      @Override public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> r, Object sym,
          int line, int pos, String msg, org.antlr.v4.runtime.RecognitionException e) {
        count[0]++;
      }
    });
    parser.module();
    return count[0];
  }

  @Test void bareNameSameAsParenCall() {
    // `apply double to x` ≡ `double(x)`。
    assertSameIr(
        "Module t.\n\nRule r given x:\n  Return apply double to x.",
        "Module t.\n\nRule r given x:\n  Return double(x).");
  }

  @Test void greedyArgEqualsParenthesizedExpr() {
    // arg 取顶层 expr（贪婪）：`apply f to a plus 2` ≡ `f(a plus 2)`（不是 `f(a) plus 2`）。
    assertSameIr(
        "Module t.\n\nRule r given a:\n  Return apply f to a plus 2.",
        "Module t.\n\nRule r given a:\n  Return f(a plus 2).");
  }

  @Test void recursiveApplyEqualsParenCall() {
    // 递归调用（诗 demo 的核心场景）：`apply gather to stars less 1` ≡ `gather(stars less 1)`。
    assertSameIr(
        "Module t.\n\nRule gather given stars:\n  Return apply gather to stars less 1.",
        "Module t.\n\nRule gather given stars:\n  Return gather(stars less 1).");
  }

  @Test void qualifiedTargetSameAsParenCall() {
    // 限定名点链 target：`apply Math.abs to x` ≡ `Math.abs(x)`。
    assertSameIr(
        "Module t.\n\nRule r given x:\n  Return apply Math.abs to x.",
        "Module t.\n\nRule r given x:\n  Return Math.abs(x).");
  }

  @Test void wrapperConstructorSameAsParenCall() {
    // Codex 审查 019f1639 #2：apply 须复用普通调用路径（createCallExpression 把 Some/Ok
    // 规范成 Expr.Some/Expr.Ok）。`apply Some to x` ≡ `Some(x)`（同为 Expr.Some），否则
    // apply 版是裸 Call、括号版是 Expr.Some → Java 内部破不变式。
    assertSameIr(
        "Module t.\n\nRule r given x:\n  Return apply Some to x.",
        "Module t.\n\nRule r given x:\n  Return Some(x).");
    assertSameIr(
        "Module t.\n\nRule r given x:\n  Return apply Ok to x.",
        "Module t.\n\nRule r given x:\n  Return Ok(x).");
  }

  @Test void mapQualifierAnySegmentSameAsParenCall() {
    // Codex 审查 019f1639 #1：MAP 须在 callTarget **任意段**放行（TS 处处把 Map 当
    // TYPE_IDENT）。`apply Map.get to m` ≡ `Map.get(m)`；`apply Foo.Map to x` 不报错。
    assertSameIr(
        "Module t.\n\nRule r given m:\n  Return apply Map.get to m.",
        "Module t.\n\nRule r given m:\n  Return Map.get(m).");
    assertEquals(0, syntaxErrors("Module t.\n\nRule r given x:\n  Return apply Foo.Map to x."),
        "`apply Foo.Map to x`（Map 作后续段）应零语法错误");
  }

  @Test void greedyArgInBinaryRightOperand() {
    // Codex 审查 019f1639 #3：apply 在二元右操作数（`a plus apply f to b plus c`），arg 贪婪
    // 取顶层 expr → `a plus f(b plus c)`，两引擎一致（parity 样本另锁）。这里只验单引擎结构稳定。
    assertSameIr(
        "Module t.\n\nRule r given a, b, c:\n  Return a plus apply f to b plus c.",
        "Module t.\n\nRule r given a, b, c:\n  Return a plus f(b plus c).");
  }

  @Test void applyAsFunctionNameNotBroken() {
    // 软关键词铁律：函数名叫 apply 不破（applyExpr 仅 `apply <名> to` 形态触发）。
    assertEquals(0, syntaxErrors("Module t.\n\nRule apply given x:\n  Return x plus 1."),
        "`Rule apply given …`（函数名 apply）应零语法错误");
  }

  @Test void applyParenCallNotBroken() {
    // 软关键词：`apply(x)` 后缀调用形态（apply 后是 `(` 非 `名 to`）仍当普通调用。
    assertEquals(0, syntaxErrors("Module t.\n\nRule apply given x:\n  Return x.\n\nRule r given y:\n  Return apply(y)."),
        "`apply(y)` 后缀调用应零语法错误");
  }

  @Test void setToNotBroken() {
    // 复用 TO_WORD 不与 `Set x to y` 冲突（apply 起头消歧）。
    assertEquals(0, syntaxErrors("Module t.\n\nRule r given x:\n  Let m be x.\n  Set m to x plus 1.\n  Return m."),
        "`Set m to …` 应零语法错误");
  }

  @Test void softKeywordTargetAccepted() {
    // Codex 审查 019f1639 #3：软关键词（structKeywordName 成员）可作 callTarget 名——
    // `apply if to x` / `apply let to x` / `apply return to x` 两引擎都接受。
    assertEquals(0, syntaxErrors("Module t.\n\nRule r given x:\n  Return apply if to x."),
        "`apply if to x`（软关键词 if 作目标）应零语法错误");
    assertEquals(0, syntaxErrors("Module t.\n\nRule r given x:\n  Return apply return to x."),
        "`apply return to x`（软关键词 return 作目标）应零语法错误");
  }

  @Test void hardKeywordTargetRejected() {
    // Codex 审查 019f1639 #3：硬关键词（and/or/not/with/given/produce/set）**不**可作
    // callTarget 名——TS 已对齐拒绝，两引擎一致（否则 TS 收/Java 拒 = parse 分歧）。
    assertTrue(syntaxErrors("Module t.\n\nRule r given x:\n  Return apply and to x.") > 0,
        "`apply and to x`（硬关键词 and 作目标）应报语法错误");
    assertTrue(syntaxErrors("Module t.\n\nRule r given x:\n  Return apply with to x.") > 0,
        "`apply with to x`（硬关键词 with 作目标）应报语法错误");
  }

  @Test void missingToIsError() {
    // 缺 `to`：`apply f x`（无 to）应产生语法错误。
    assertTrue(syntaxErrors("Module t.\n\nRule r given x:\n  Return apply f x.") > 0,
        "缺 `to` 的 apply 应产生语法错误");
  }
}
