package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Stmt;
import aster.core.canonicalizer.Canonicalizer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 非 Name 表达式上的尾随成员访问（issue #127 正文）。
 *
 * <p>★真实缺陷：文法 {@code postfixExpr: primaryExpr postfixSuffix*} 允许 MemberSuffix
 * 跟在 CallSuffix 之后，于是 {@code getConfig().timeout} 能解析通过；但
 * {@code AstBuilder.applyTrailingMembers} 对非 Name base 直接 {@code return base;}，
 * 把 {@code .timeout} 整个扔掉，**既不报错也不产生诊断**。
 *
 * <p>实测（修复前）：源码 {@code Return double(x).bar.} 得到的 AST 里只有
 * {@code Call[target=double, args=[x]]}，{@code .bar} 不存在——语义无声改变。
 *
 * <p>AST 暂无成员访问节点，无法正确表达该语义；在补上之前唯一诚实的做法是 fail-fast。
 */
class TrailingMemberAccessTest {

  /** 经 Canonicalizer → ANTLR → AstBuilder，返回 AST（与本包既有测试同一路径）。 */
  private Module build(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var moduleCtx = parser.module();
    assertNotNull(moduleCtx, "解析 null: " + src);
    return new AstBuilder().visitModule(moduleCtx);
  }

  @Test
  void callResultMemberAccessFailsFast_insteadOfSilentlyDropping() {
    // ★核心回归：修复前这里不抛异常，且 `.bar` 被静默丢弃。
    var ex = assertThrows(IllegalStateException.class,
        () -> build("Module t.\n\nRule r given x:\n  Return double(x).bar."));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("bar"),
        "报错须点名被丢弃的成员，否则用户无从定位；实际: " + ex.getMessage());
  }

  @Test
  void multiSegmentTrailingMemberIsNamedInFull() {
    // 多级尾随成员须完整报出，不能只报第一段——否则用户改完一段又撞下一段。
    var ex = assertThrows(IllegalStateException.class,
        () -> build("Module t.\n\nRule r given x:\n  Return double(x).a.b."));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("a.b"),
        "多级成员须完整报出；实际: " + ex.getMessage());
  }

  @Test
  void withCallSuffixOnCallResultAlsoFailsFast() {
    // ★applyTrailingMembers 有**两个**调用点：postfixExpr 收尾（:1290）与
    //   applyWithCallSuffix（:1316）。上面几条只覆盖前者——把后者的委托改回
    //   静默构造 Call，全部 1541 条测试仍全绿（实测）。这是「只查首次出现」模式：
    //   修复被验证在第一个调用点上，第二个溜过去。
    //   自然语言调用形式 `f(x).bar with y` 走的正是第二个调用点。
    var ex = assertThrows(IllegalStateException.class,
        () -> build("Module t.\n\nRule r given x:\n  Return double(x).bar with x."));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("bar"),
        "with 调用形式同样须 fail-fast 并点名成员；实际: " + ex.getMessage());
  }

  @Test
  void withCallSuffixOnLiteralAlsoFailsFast() {
    // 字面量 base 同样不是 Name，走同一分支。
    var ex = assertThrows(IllegalStateException.class,
        () -> build("Module t.\n\nRule r given x:\n  Return 5.bar with x."));
    assertTrue(ex.getMessage() != null && ex.getMessage().contains("bar"),
        "实际: " + ex.getMessage());
  }

  @Test
  void nameBaseTrailingMemberStillComposesQualifiedName() {
    // ★反向护栏：Name base 的尾随成员须合成限定名（`config.enabled`），
    //   这是 applyTrailingMembers 既有的合法分支，**不得**被本次 fail-fast 误伤。
    //   没有这条，把 `if (base instanceof Expr.Name)` 短路掉（让所有 base 都抛异常）
    //   也能让上面几条变绿——实测该变异确实能存活，故本条是必需的。
    //
    //   ★注意必须用 `If cond` 位置：`Return config.timeout.` 里的 `.timeout`
    //   根本不会被文法解析成 postfixSuffix（实测停在 config，尾巴落到块外），
    //   走不到 applyTrailingMembers——用它做护栏是假绿。
    var module = build("Module a.b.\n\nRule f given xs as List, produce Int:\n"
        + "  If config.enabled then Return 1 else Return 2.\n");

    var func = (aster.core.ast.Decl.Func) module.decls().get(0);
    var ifStmt = assertInstanceOf(Stmt.If.class, func.body().statements().get(0));
    var cond = assertInstanceOf(Expr.Name.class, ifStmt.cond());
    assertEquals("config.enabled", cond.name(),
        "Name base 的尾随成员须合成限定名，不受本次改动影响");
  }

  @Test
  void plainCallWithoutTrailingMemberStillWorks() {
    // ★反向护栏之二：无尾随成员的普通调用不得被误伤。
    var module = build("Module t.\n\nRule r given x:\n  Return double(x).");

    var func = (aster.core.ast.Decl.Func) module.decls().get(0);
    var ret = assertInstanceOf(Stmt.Return.class, func.body().statements().get(0));
    var call = assertInstanceOf(Expr.Call.class, ret.expr());
    var target = assertInstanceOf(Expr.Name.class, call.target());
    assertEquals("double", target.name());
    assertEquals(1, call.args().size(), "参数不得丢失");
  }
}
