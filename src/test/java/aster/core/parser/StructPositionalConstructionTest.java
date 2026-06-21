package aster.core.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：对已声明类型用位置式 {@code TypeName(args)} 构造，应在构建期报错并提示
 * 用命名字段 {@code TypeName with field set to ...}，而非静默降级成函数调用。
 * <p>
 * 历史 bug：位置式被当作普通 Call，编译期不报错，直到执行期才暴露为「未定义函数」。
 */
class StructPositionalConstructionTest {

    private aster.core.ast.Module pb(String input) {
        var lexer = new AsterCustomLexer(org.antlr.v4.runtime.CharStreams.fromString(input));
        var tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
        var parser = new AsterParser(tokens);
        var ctx = parser.module();
        return new AstBuilder().visitModule(ctx);
    }

    @Test
    void positionalConstructOfDeclaredTypeErrors() {
        String src = "Module a.b.\nDefine Box has v, w.\nRule f given x, produce:\n  Return Box(5, 6).\n";
        var ex = assertThrows(IllegalStateException.class, () -> pb(src));
        assertTrue(ex.getMessage().contains("Box"), "应提及类型名: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("set to"),
            "应提示用命名字段 with..set to: " + ex.getMessage());
    }

    @Test
    void namedFieldConstructWorks() {
        String src = "Module a.b.\nDefine Box has v, w.\nRule f given x, produce:\n"
            + "  Return Box with v set to 5 and w set to 6.\n";
        assertDoesNotThrow(() -> pb(src));
    }

    @Test
    void okBuiltinStillCallable() {
        // Ok 是内置变体构造，不是已声明类型，位置式仍合法
        String src = "Module a.b.\nRule f given x, produce:\n  Return Ok(x).\n";
        assertDoesNotThrow(() -> pb(src));
    }

    @Test
    void ordinaryFunctionCallStillWorks() {
        // 普通函数调用（非类型名）不受影响
        String src = "Module a.b.\nRule g given y, produce:\n  Return y.\n"
            + "Rule f given x, produce:\n  Return g(x).\n";
        assertDoesNotThrow(() -> pb(src));
    }

    @Test
    void forwardReferencePositionalAlsoErrors() {
        // Box 在 Define 之前被位置式构造——预扫描确保也报错（与 TS 引擎 parity）
        String src = "Module a.b.\nRule f given x, produce:\n  Return Box(5, 6).\n"
            + "Define Box has v, w.\n";
        assertThrows(IllegalStateException.class, () -> pb(src));
    }

    @Test
    void typeAliasIsNotTreatedAsConstructible() {
        // type alias 别名标量(Score=Int)不是可构造记录类型，Score(5) 不应触发构造报错
        // （与 TS parity；alias 不进 record 集）
        String src = "Module a.b.\nRule f given x, produce:\n  Return Score(5).\n"
            + "Type Score as Int.\n";
        assertDoesNotThrow(() -> pb(src));
    }
}
