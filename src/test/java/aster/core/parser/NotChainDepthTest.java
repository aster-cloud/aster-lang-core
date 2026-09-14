package aster.core.parser;

import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code not} 链的解析期深度守卫（issue #157）。
 *
 * <h2>两道既有防线为什么都漏了它</h2>
 *
 * <ul>
 *   <li>{@code AstBuilder.MAX_EXPR_DEPTH} 在 {@code visitExpr} 入口递增，属**访问期**；
 *       而 {@code notExpr : NOT notExpr} 的右递归在 ANTLR **解析期**就递归下去了。</li>
 *   <li>词法期预检按**括号深度**计数，而 {@code not not not …} 全程零括号。</li>
 * </ul>
 *
 * <h2>★实测复现（issue 标注「未实测」，此处补上）</h2>
 *
 * 默认栈的 Gradle test worker 上：{@code n=12000 → OK}，{@code n=15000 → StackOverflowError}。
 * issue 估计约 1 万，实测阈值在 12000–15000 之间；阈值随栈大小浮动，
 * 生产栈更小则更容易触发。
 */
class NotChainDepthTest {

    private void parse(String src) {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(src));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        parser.module();
    }

    private String withNots(int n) {
        return "Module m.\n\nDefine rule r:\n    Return " + "not ".repeat(n) + "true.\n";
    }

    @Test
    void 超长not链必须被拒绝而不是栈溢出() {
        // 15000 是实测必然栈溢出的量级。守卫生效后应当变成**可恢复的解析错误**，
        // 而不是 StackOverflowError（Error 级，穿透一切 catch(Exception)）。
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> parse(withNots(15000)),
            "超长 not 链必须被守卫拒绝");
        assertTrue(e.getMessage().contains("not 链过长"),
            "错误信息应指明是 not 链：" + e.getMessage());
    }

    @Test
    void 恰好触发阈值前后的行为() {
        // 上限 300：300 个应通过，301 个应被拒。
        assertDoesNotThrow(() -> parse(withNots(AsterCustomLexer.MAX_NESTING_DEPTH)),
            "恰好 " + AsterCustomLexer.MAX_NESTING_DEPTH + " 个 not 应当放行");
        assertThrows(IllegalStateException.class,
            () -> parse(withNots(AsterCustomLexer.MAX_NESTING_DEPTH + 1)),
            "超出上限 1 个就应被拒");
    }

    @Test
    void 正常的not用法不得被误伤() {
        // ★反向守卫：只有**紧挨着**的 not 才累加。
        //   `not x and not y and ...` 是完全正常的写法，出现几百个 not
        //   也不该被拒——把它算进去就是误伤合法代码。
        StringBuilder sb = new StringBuilder("Module m.\n\nDefine rule r:\n    Return not a1");
        for (int i = 2; i <= 500; i++) sb.append(" and not a").append(i);
        sb.append(".\n");

        assertDoesNotThrow(() -> parse(sb.toString()),
            "500 个**不连续**的 not 属正常写法，不得被拒");
    }

    @Test
    void 少量连续not仍然正常工作() {
        // 文法注释说明右结合正是为了支持 not not x，别把这个能力守没了。
        assertDoesNotThrow(() -> parse(withNots(3)), "not not not x 是合法写法");
    }
}
