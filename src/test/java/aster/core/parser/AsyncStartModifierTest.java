package aster.core.parser;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Stmt;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code Start x as async f().} 中的 {@code async} 是**修饰符**，不是函数。
 *
 * <h2>缺陷</h2>
 *
 * {@code visitStartStmt} 原先在识别到 ASYNC token 时，把任务体包成
 * {@code Call(Name("async"), [task])}：
 *
 * <pre>
 *   Start profile as async ProfileSvc.load(u.id).
 *     → Start{ expr: Call(Name "async", [Call(Name "ProfileSvc.load", [u.id])]) }
 * </pre>
 *
 * 两个问题：
 * <ul>
 *   <li><b>异步语义本就由 Start 承载</b>。truffle 的 {@code StartNode} 自己检查
 *       Async effect、materialize frame、把子表达式包成 Runnable 提交调度器——
 *       它只需要任务体本身，外面那层包装是多余的。</li>
 *   <li><b>运行时会炸</b>。{@code async} 没有任何内建或用户定义，{@code Loader}
 *       对未定义的命名空间函数直接报「未定义」（不退化成成员访问）。该形态目前
 *       只被解析、从不执行（语料无对应 eval 输入），所以是**潜伏**缺陷——一旦
 *       有人真的跑带 {@code as async} 的策略就会失败。</li>
 * </ul>
 *
 * <p>TS 引擎一直是对的（{@code Start{expr: <任务体>}}）。
 */
class AsyncStartModifierTest {

    private static Stmt.Start firstStart(String source) {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        aster.core.ast.Module module = new AstBuilder().visitModule(parser.module());
        Block body = ((Decl.Func) module.decls().get(0)).body();
        return (Stmt.Start) body.statements().get(0);
    }

    @Test
    @DisplayName("★`as async` 不得把任务体包成 async(...) 调用（运行时无此函数）")
    void asyncModifierMustNotWrapTheTaskInACall() {
        Stmt.Start start = firstStart("""
            Rule run, produce. It performs io:
              Start task as async load().
              Wait for task.
              Return "done".
            """);

        Expr.Call task = assertInstanceOf(Expr.Call.class, start.expr(),
            "Start 的 expr 应是任务体调用本身。");
        assertEquals("load", ((Expr.Name) task.target()).name(),
            "任务体应直接是 `load()`，而不是被 `async(...)` 包一层。"
                + "\n★若这里是 \"async\"，运行时会因为找不到名为 async 的函数而失败。");
    }

    @Test
    @DisplayName("不带 async 的 Start 行为不变（反向守卫）")
    void startWithoutAsyncIsUnchanged() {
        // 确认修复只是去掉了包装，没有连带改变 Start 本身的结构。
        Stmt.Start start = firstStart("""
            Rule run, produce. It performs io:
              Start task as load().
              Wait for task.
              Return "done".
            """);

        Expr.Call task = assertInstanceOf(Expr.Call.class, start.expr());
        assertEquals("load", ((Expr.Name) task.target()).name());
        assertEquals("task", start.name(), "绑定名不应受影响。");
    }
}
