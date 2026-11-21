package aster.core.embed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 名称解析前后的预算边界检查（wontlost-ltd/aster-api#594）。预算原先只在语法分析的预测里检查，名称解析把预算用完时
 * 解析照常成功、悄悄超支请求级额度。名称解析经 {@link InProcessCnlParser.NameResolver} 接缝注入慢实现。
 */
class ParseStageBudgetTest {

    private static final String NORMAL = "Module m.\n\nRule f given x as Int, produce Int:\n  Return x.\n";

    @AfterEach
    void restoreDefault() {
        ParseBudget.configure(null);
    }

    /** 真实的名称解析，之前先停顿 {@code pause}。 */
    private static InProcessCnlParser.NameResolver slowResolver(Duration pause, AtomicBoolean called) {
        return (ast, scopes) -> {
            called.set(true);
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return aster.core.resolve.NameResolution.resolveAndLower(ast,
                new aster.core.resolve.ResolutionContext(scopes, aster.core.resolve.ImportedDeclarations.NONE));
        };
    }

    @Test
    void 名称解析用完预算时归为带码_E054() {
        ParseBudget.configure(Duration.ofMillis(200));
        AtomicBoolean called = new AtomicBoolean();
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parseUnsafeWithAliases(NORMAL, "en-US", null, null, slowResolver(Duration.ofMillis(400), called)));
        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
        var d = e.getDiagnostics().stream().filter(x -> "E054".equals(x.code())).findFirst().orElseThrow();
        assertEquals(InProcessCnlParser.BudgetExceededFailure.REASON, d.params().get("reason"));
        assertEquals("200", d.params().get("budgetMs"));
    }

    @Test
    void 预算未用完时名称解析之后照常成功() {
        ParseBudget.configure(Duration.ofSeconds(2));
        AtomicBoolean called = new AtomicBoolean();
        assertEquals("m", InProcessCnlParser.parseUnsafeWithAliases(NORMAL, "en-US", null, null,
            slowResolver(Duration.ofMillis(10), called)).moduleName());
    }

    @Test
    void 预算在名称解析之前已用完时不再开始名称解析() {
        AtomicBoolean called = new AtomicBoolean();
        // 预算只够语法分析：预测里的检查不停顿，构建 AST 之后的边界检查先于名称解析中止
        ParseBudget budget = ParseBudget.start(Duration.ofNanos(1));
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parseUnsafeWithAliases(NORMAL, "en-US", null, null,
                slowResolver(Duration.ZERO, called), budget, org.antlr.v4.runtime.atn.PredictionMode.SLL));
        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
        assertFalse(called.get(), "预算用完后不应再开始名称解析");
    }
}
