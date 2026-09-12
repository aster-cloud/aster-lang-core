package aster.core.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 裸表达式语句必须降为「求值并丢弃」，<b>不能</b>降为 {@code Return}。
 *
 * <h2>缺陷（执行期行为错误，不只是 IR 分叉）</h2>
 *
 * {@code visitExprStmt} 原先把裸表达式包装成 {@code Stmt.Return}，注释写着
 * 「表达式语句实际上被当作隐式返回」。该说法<b>只对函数体最后一条语句成立</b>；
 * 出现在中间时，函数会<b>当场返回</b>，其后的语句永不执行：
 *
 * <pre>
 *   Let key be Secrets.get(...).        → Let
 *   File.write("/tmp/data.txt", resp).  → Return   ← 函数在此结束
 *   Db.insert("logs", timestamp).       → Return   ← 永不执行
 *   Return AiModel.generate(resp).      → Return   ← 永不执行
 * </pre>
 *
 * truffle 的 {@code ReturnNode} 抛 {@code ReturnException} 真正中断控制流
 * （已实测：三条连续 Return 的模块求值结果为<b>第一条</b>的值）。
 *
 * <p>parser 看不到自己是不是最后一条语句，所以「隐式返回」这个判断在这一层
 * 根本做不了。TS 引擎一直是对的（降为 {@code Let name:"_"}），本测试与其对齐。
 */
class BareExpressionStatementTest {

    private static List<CoreModel.Stmt> statementsOf(String body) {
        String src = "Module p.\n\nRule r, produce. It performs io:\n" + body;
        String canonical = new Canonicalizer().canonicalize(src);
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
        return ((CoreModel.Func) core.decls.get(0)).body.statements;
    }

    @Test
    @DisplayName("★中间的裸表达式语句不得变成 Return（否则其后语句永不执行）")
    void bareStatementInTheMiddleMustNotTerminateTheFunction() {
        List<CoreModel.Stmt> stmts = statementsOf(
            "  Io.write(\"a\").\n  Io.write(\"b\").\n  Return 1.\n");

        assertEquals(3, stmts.size(), "应有 3 条语句。");

        // 前两条：求值并丢弃，绑定到 "_"（与 TS 同口径）
        for (int i = 0; i < 2; i++) {
            CoreModel.Let let = assertInstanceOf(CoreModel.Let.class, stmts.get(i),
                "第 " + i + " 条裸表达式应降为 Let（求值并丢弃），实际 "
                    + stmts.get(i).getClass().getSimpleName()
                    + "\n★若是 Return，函数会在此结束，其后的语句永不执行。");
            assertEquals("_", let.name, "丢弃式绑定的名字应为 \"_\"（与 TS 一致）。");
        }

        // 最后一条是**真正**的 Return，确认修复只作用于裸表达式、没有波及 returnStmt。
        //
        // ★诚实标注：这一条**不是**一个能变红的守卫。`visitReturnStmt` 的返回类型就是
        //   `Stmt.Return`，编译器已经不允许它返回别的东西——想「把真正的 Return 也降成
        //   Let」在类型层面就通不过。已实测：无法构造出让本断言变红的变异。
        //   保留它是为了表达「修复的边界在哪」，而不是冒充一层实际不存在的保护。
        assertInstanceOf(CoreModel.Return.class, stmts.get(2),
            "显式 `Return 1.` 必须仍是 Return。");
    }

    @Test
    @DisplayName("单条裸表达式同样降为 Let（parser 无法判断自己是不是最后一条）")
    void aLoneBareStatementIsAlsoLowered() {
        List<CoreModel.Stmt> stmts = statementsOf("  Io.write(\"a\").\n");

        CoreModel.Let let = assertInstanceOf(CoreModel.Let.class, stmts.get(0),
            "裸表达式应一律降为 Let —— parser 在这一层看不到语句序列的位置信息，"
                + "\n「最后一条视为隐式返回」这个判断必须由更上层（lowering）来做。");
        assertEquals("_", let.name);
    }
}
