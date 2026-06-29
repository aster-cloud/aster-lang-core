package aster.core.parser;

import aster.core.ast.Expr;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decimal 字面量（ADR 0025，m 后缀）解析 + 降级测试。
 *
 * <p>验证 `1.08m` → AST Expr.Decimal(canonical 字符串) → Core IR DecimalE。canonical
 * 化规则（去前导/尾零、零归 "0"）必须与 TS canonicalizeDecimal、truffle BigDecimal
 * toPlainString 逐位一致——三引擎 Core IR value 字节相同是 parity 契约。fixture 与
 * ts decimal-literals.test.ts、truffle DecimalBuiltinTest 同源。
 */
class DecimalLiteralTest {

    private aster.core.ast.Module parseAndBuild(String input) {
        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        tokens.seek(0);
        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        AsterParser.ModuleContext moduleCtx = parser.module();
        assertNotNull(moduleCtx, "解析返回 null ModuleContext: " + input);
        aster.core.ast.Module module = new AstBuilder().visitModule(moduleCtx);
        assertNotNull(module, "AstBuilder 返回 null: " + input);
        return module;
    }

    /** 解析单个 `Return <expr>.` 规则体，取出返回表达式的 AST 节点。 */
    private Expr returnExprOf(String literal) {
        String src = "Module probe.\n\nRule main produce Decimal:\n  Return " + literal + ".\n";
        var module = parseAndBuild(src);
        var func = (aster.core.ast.Decl.Func) module.decls().get(0);
        var ret = (aster.core.ast.Stmt.Return) func.body().statements().get(0);
        return ret.expr();
    }

    /** 解析 + 降级，取出 Core IR 中返回表达式的 DecimalE.value。 */
    private String loweredDecimalValue(String literal) {
        String src = "Module probe.\n\nRule main produce Decimal:\n  Return " + literal + ".\n";
        var module = parseAndBuild(src);
        var core = new CoreLowering().lowerModule(module);
        var func = (CoreModel.Func) core.decls.get(0);
        var ret = (CoreModel.Return) func.body.statements.get(0);
        assertInstanceOf(CoreModel.DecimalE.class, ret.expr,
            "返回表达式应降级为 DecimalE，实际: " + ret.expr.getClass().getSimpleName());
        return ((CoreModel.DecimalE) ret.expr).value;
    }

    @Test void parsesAsDecimalNotFloat() {
        // `1.08m` 必须解析为 Expr.Decimal（而非 FLOAT + 标识符 m）
        Expr e = returnExprOf("1.08m");
        assertInstanceOf(Expr.Decimal.class, e, "1.08m 应解析为 Decimal，实际: " + e.kind());
        assertEquals("1.08", ((Expr.Decimal) e).value());
        // 整数形 `10m` 同样
        assertInstanceOf(Expr.Decimal.class, returnExprOf("10m"));
    }

    @Test void canonicalLowering() {
        // canonical 化：去尾零 / 去前导+尾零 / 零归 "0" / 整数保持
        assertEquals("1.08", loweredDecimalValue("1.08m"));
        assertEquals("1", loweredDecimalValue("1.00m"));
        assertEquals("1.23", loweredDecimalValue("001.2300m"));
        assertEquals("0", loweredDecimalValue("0.000m"));
        assertEquals("10", loweredDecimalValue("10m"));
        assertEquals("100", loweredDecimalValue("100m"));
        // 大写 M 后缀也接受
        assertEquals("2.5", loweredDecimalValue("2.5M"));
    }

    @Test void digitLimitV1() {
        // ADR 0025 v1：≤38 有效位（Codex 审查 P1）。38 接受，39 拒绝（与 TS 一致）。
        String d38 = "1".repeat(38);
        String d39 = "1".repeat(39);
        org.junit.jupiter.api.Assertions.assertEquals(d38, loweredDecimalValue(d38 + "m"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> loweredDecimalValue(d39 + "m"), "39 位应拒绝");
        // 纯小数前导零不计有效位
        String frac = "0." + "0".repeat(38) + "1";
        org.junit.jupiter.api.Assertions.assertEquals(frac, loweredDecimalValue(frac + "m"));
    }

    @Test void floatStillParsesAsDouble() {
        // 回归：无 m 后缀的 `1.08` 仍是 Double（不被 Decimal 抢走）
        String src = "Module probe.\n\nRule main produce Double:\n  Return 1.08.\n";
        var module = parseAndBuild(src);
        var core = new CoreLowering().lowerModule(module);
        var func = (CoreModel.Func) core.decls.get(0);
        var ret = (CoreModel.Return) func.body.statements.get(0);
        assertInstanceOf(CoreModel.DoubleE.class, ret.expr,
            "1.08（无 m）应仍是 Double，实际: " + ret.expr.getClass().getSimpleName());
    }
}
