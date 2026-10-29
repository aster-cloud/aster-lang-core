package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.ast.Stmt;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

/**
 * Match 分支体可以是 {@code When pat,} 后换行的缩进块（与 TS 一致，产出 Block 分支体）。
 *
 * <p>根因与 #218 同类：{@code matchCase} 曾要求 COMMA 后紧跟 INDENT，而词法器在行尾先发 NEWLINE；
 * 且块体以 {@code NEWLINE* DEDENT} 收尾后下一个 {@code When} 前不再有 NEWLINE，分支间不能要求 NEWLINE+。
 * 所有用例都挂错误监听器并断言 0 语法错误（ANTLR 默认错误恢复，不会抛异常）。
 */
class MatchCaseBlockBodyTest {

  private record Parsed(List<String> errors, AsterParser.ModuleContext ctx) {}

  private static Parsed parse(String src) {
    var lexer = new AsterCustomLexer(CharStreams.fromString(new Canonicalizer().canonicalize(src)));
    var parser = new AsterParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    List<String> errors = new ArrayList<>();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        errors.add(line + ":" + col + " " + msg);
      }
    });
    var ctx = parser.module();
    assertNotNull(ctx);
    return new Parsed(errors, ctx);
  }

  private static Module parseOk(String src) {
    Parsed p = parse(src);
    assertEquals(List.of(), p.errors(), "不应有语法错误:\n" + src);
    return new AstBuilder().visitModule(p.ctx());
  }

  private static Stmt.Match firstMatch(Module m, int declIndex) {
    var func = assertInstanceOf(Decl.Func.class, m.decls().get(declIndex));
    return assertInstanceOf(Stmt.Match.class, func.body().statements().get(0));
  }

  @Test
  void blockBodyFollowedByInlineReturnCase() {
    Module m = parseOk("Module p.\n\nDefine Color as one of Red, Green.\n\n"
        + "Rule r given c as Color, produce Int:\n  Match c:\n    When Red,\n      Let x be 1.\n      Return x plus 1.\n"
        + "    When Green, Return 2.\n");
    var match = firstMatch(m, 1);
    assertEquals(2, match.cases().size());
    Block body = assertInstanceOf(Block.class, match.cases().get(0).body());
    assertEquals(2, body.statements().size());
    assertInstanceOf(Stmt.Let.class, body.statements().get(0));
    assertInstanceOf(Stmt.Return.class, match.cases().get(1).body());
  }

  @Test
  void consecutiveBlockBodiesWithSingleStatement() {
    Module m = parseOk("Module p.\n\nRule r given n as Int, produce Text:\n  Match n:\n"
        + "    When 0,\n      Return \"zero\".\n    When 1,\n      Let s be \"one\".\n      Return s.\n");
    var match = firstMatch(m, 0);
    assertEquals(2, match.cases().size());
    // 单条语句的缩进块仍是 Block 分支体（与 TS 一致），不是 Return
    assertEquals(1, assertInstanceOf(Block.class, match.cases().get(0).body()).statements().size());
    assertEquals(2, assertInstanceOf(Block.class, match.cases().get(1).body()).statements().size());
  }

  @Test
  void nestedBlockInsideCaseAndStatementAfterMatch() {
    Module m = parseOk("Module p.\n\nRule r given n as Int, produce Int:\n  Match n:\n"
        + "    When 0,\n      If n greater than 1:\n        Return 1.\n      Return 0.\n"
        + "    When 1, Return 9.\n  Return 3.\n");
    var func = assertInstanceOf(Decl.Func.class, m.decls().get(0));
    assertEquals(2, func.body().statements().size(), "Match 之后的语句不得丢失");
    var match = assertInstanceOf(Stmt.Match.class, func.body().statements().get(0));
    Block body = assertInstanceOf(Block.class, match.cases().get(0).body());
    assertInstanceOf(Stmt.If.class, body.statements().get(0));
    assertEquals(2, match.cases().size());
  }

  @Test
  void inlineReturnCasesStillParse() {
    var match = firstMatch(parseOk("Module p.\n\nRule r given n as Int, produce Int:\n  Match n:\n"
        + "    When 0, Return 1.\n\n    When 1, Return 2.\n"), 0);
    assertEquals(2, match.cases().size());
  }

  @Test
  void blockBodyLowersToCoreBlock() {
    Module m = parseOk("Module p.\n\nRule r given n as Int, produce Int:\n  Match n:\n"
        + "    When 0,\n      Let x be 1.\n      Return x.\n    When 1, Return 2.\n");
    var core = new CoreLowering().lowerModule(m);
    var func = (CoreModel.Func) core.decls.get(0);
    var match = (CoreModel.Match) func.body.statements.get(0);
    assertInstanceOf(CoreModel.Block.class, match.cases.get(0).body);
    assertInstanceOf(CoreModel.Return.class, match.cases.get(1).body);
  }

  @Test
  void caseBodyOnNextLineWithoutIndentIsSyntaxError() {
    Parsed p = parse("Module p.\n\nRule r given n as Int, produce Int:\n  Match n:\n    When 0,\n    Return 1.\n");
    assertFalse(p.errors().isEmpty(), "分支体换行后未缩进必须报语法错误");
  }
}
