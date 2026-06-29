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
 * 等缩进多行表达式续行（ADR 0026）—— Java 引擎。续行（与语句起始行同缩进、运算符打头/结尾）
 * 解析到与单行**完全相同**的 Core IR（零新节点）；与 TS 引擎双引擎一致由 tier1-parity 锁。
 * 安全性（Codex 审查 019f157b）：nlOpt 只吞 NEWLINE，不碰 INDENT/DEDENT，块结构零风险。
 */
class MultilineContinuationTest {

  /** 经 Canonicalizer → ANTLR → AstBuilder → CoreLowering，返回 Core IR JSON（剥 origin 比较）。 */
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

  /** 取首个规则体首条语句的表达式（Return/Let）。 */
  private CoreModel.Expr firstExpr(CoreModel.Module m) {
    var func = (CoreModel.Func) m.decls.get(0);
    var stmt = func.body.statements.get(0);
    if (stmt instanceof CoreModel.Return r) return r.expr;
    if (stmt instanceof CoreModel.Let l) return l.expr;
    throw new IllegalStateException("unexpected stmt " + stmt.getClass());
  }

  /** 续行版与单行版的 Core IR 表达式结构一致（用 toString 作结构指纹，origin 不入）。 */
  private void assertSameIr(String multiline, String singleline) {
    // CoreModel 节点无 equals；用 jackson 序列化后剥 origin 比较结构。
    var ml = lower(multiline);
    var sl = lower(singleline);
    assertEquals(fingerprint(sl), fingerprint(ml), "续行版 IR 应与单行版一致");
  }

  /** 结构指纹：序列化 Core IR 并剥除 origin/span 行列（派生层）。 */
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

  @Test void equalIndentOpLeading() {
    // 行首运算符续行（加法）：1 plus 2 plus 3 跨行 ≡ 单行。
    assertSameIr(
        "Module t.\n\nRule r given a:\n  Return 1 plus 2\n  plus 3.",
        "Module t.\n\nRule r given a:\n  Return 1 plus 2 plus 3.");
  }

  @Test void equalIndentOpTrailing() {
    // 行尾运算符续行：1 plus\n 2 ≡ 单行。
    assertSameIr(
        "Module t.\n\nRule r given a:\n  Return 1 plus\n  2.",
        "Module t.\n\nRule r given a:\n  Return 1 plus 2.");
  }

  @Test void multiplicativeAndComparisonAndLogical() {
    assertSameIr(
        "Module t.\n\nRule r given a:\n  Return 6 times 2\n  times 3.",
        "Module t.\n\nRule r given a:\n  Return 6 times 2 times 3.");
    assertSameIr(
        "Module t.\n\nRule r given a:\n  Return a at least 1\n  and a at most 9.",
        "Module t.\n\nRule r given a:\n  Return a at least 1 and a at most 9.");
  }

  /** 解析并返回语法错误数（core 默认 error-recover 不抛异常，须用 listener 计数）。 */
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

  @Test void equalIndentNoSyntaxError() {
    // 等缩进续行：零语法错误（与单行一样干净）。
    assertEquals(0, syntaxErrors("Module t.\n\nRule r given a:\n  Return 1 plus 2\n  plus 3."));
  }

  @Test void deeperIndentRejected() {
    // 本批不支持更深缩进续行（Codex：nlOpt 只吞 NEWLINE 不碰 INDENT/DEDENT）。更深缩进的 `plus`
    // 前有未被吞的 INDENT → 语法错误（与 TS「Unknown statement」对应：两引擎都不接受深缩进续行）。
    assertTrue(syntaxErrors("Module t.\n\nRule r given a:\n  Return 1 plus 2\n    plus 3.") > 0,
        "更深缩进续行应产生语法错误（本批不支持）");
  }

  @Test void blockBoundaryIntact() {
    // 两条独立语句不被续行误并：let m be …. / Return m. 各自成句。
    var m = lower("Module t.\n\nRule r given a:\n  Let m be a plus 1.\n  Return m.");
    var func = (CoreModel.Func) m.decls.get(0);
    assertEquals(2, func.body.statements.size(), "应为两条独立语句");
  }
}
