package aster.core.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二元表达式的**中间节点**不得继承整条表达式的 span。
 *
 * <h2>缺陷</h2>
 *
 * {@code visitAdditiveExpr} / {@code visitMultiplicativeExpr} 对左结合链上的每个
 * 中间 {@code Call} 都用 {@code spanFrom(ctx)} —— 即**整条** additive/multiplicative
 * 表达式的范围。于是 {@code a plus b plus c} 建成 {@code Call(Call(a,b), c)} 时，
 * 内层 {@code Call(a,b)} 会继承外层的结束位置。
 *
 * <p>多行续行时尤其明显：
 * <pre>
 *   Return "Hello, "   ← L4
 *   plus name          ← L5
 *   plus "!".          ← L6
 * </pre>
 * 内层 {@code Call} 真实占 L4–L5，修复前却报成 L4–<b>L6</b>。TS 引擎报 5，是对的。
 *
 * <h2>为什么要钉住</h2>
 *
 * ADR 0032 把执行 trace 锚定到源码位置、ADR 0037 在其上建 OriginMap，两者都要
 * **按位置反查 IR 节点**。内层节点虚报范围会让反查落到错误的子表达式上——点第 6 行
 * 会同时命中内层与外层，而内层根本不覆盖那一行。这类错误不报错，只给出错误答案。
 */
class BinaryExprSpanTest {

    /** 三项相加，刻意分三行——这样内外层的结束行不同，能区分「继承」与「实算」。 */
    private static final String MULTILINE_CHAIN = """
        Module binary.span.sample.

        Rule greet given name as Text, produce Text:
          Return "Hello, "
          plus name
          plus "!".
        """;

    @Test
    @DisplayName("左结合链的内层节点 span 必须止于自己的右操作数，不得延伸到外层结尾")
    void innerOperandSpanMustNotInheritOuterEnd() {
        CoreModel.Call outer = firstReturnCall(MULTILINE_CHAIN);

        // 外层 + ：覆盖 L4..L6（整条链）
        assertEquals(4, outer.origin.start.line, "外层起始行");
        assertEquals(6, outer.origin.end.line, "外层结束行");

        // args[0] 是内层 Call（"Hello, " plus name），真实占 L4..L5
        Object arg0 = outer.args.get(0);
        assertTrue(arg0 instanceof CoreModel.Call,
            "args[0] 应为内层 Call（左结合），实际 " + arg0.getClass().getSimpleName()
                + "。若不是，本测试失去被测对象。");
        CoreModel.Call inner = (CoreModel.Call) arg0;

        assertEquals(4, inner.origin.start.line, "内层起始行");
        assertEquals(5, inner.origin.end.line,
            "内层 Call 应止于其右操作数所在行（5），实际 " + inner.origin.end.line
                + "。若为 6，说明它继承了外层整条表达式的结束位置。");

        // 内层严格被外层包含，且不等于外层——这正是「实算」与「继承」的区别。
        assertTrue(inner.origin.end.line < outer.origin.end.line,
            "内层结束行必须严格早于外层，否则按位置反查会同时命中两者。");
    }

    private static CoreModel.Call firstReturnCall(String source) {
        String canonical = new Canonicalizer().canonicalize(source);
        var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        var parser = new AsterParser(new CommonTokenStream(lexer));
        CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
        CoreModel.Func func = (CoreModel.Func) core.decls.get(0);
        CoreModel.Return ret = (CoreModel.Return) func.body.statements.get(0);
        return (CoreModel.Call) ret.expr;
    }
}
