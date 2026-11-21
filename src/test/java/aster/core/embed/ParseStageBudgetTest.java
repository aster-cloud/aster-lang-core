package aster.core.embed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** 先在默认预算下解析一次：冷 JVM 的首次解析（类加载、ANTLR DFA）可能超过下面测试用的短预算。 */
    @BeforeEach
    void warmUp() {
        InProcessCnlParser.parse(NORMAL, "en-US");
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
        assertTrue(called.get(), "超时须发生在名称解析里，而不是更早的阶段");
    }

    @Test
    void 名称解析以诊断失败且已超时时报_E054_而不是名称解析错误() {
        ParseBudget.configure(Duration.ofMillis(200));
        String unresolved = "Module m.\n\nRule f given x as Unbekannt, produce Int:\n  Return 1.\n";
        AtomicBoolean called = new AtomicBoolean();
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parseUnsafeWithAliases(unresolved, "en-US", null, null, (ast, scopes) -> {
                slowResolver(Duration.ofMillis(400), called).resolve(ast, scopes);
                throw new IllegalStateException("名称解析失败（模拟 E8xx）");
            }));
        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
        assertTrue(called.get());
    }

    @Test
    void 名称解析以诊断失败但未超时时照常抛出原错误() {
        ParseBudget.configure(Duration.ofSeconds(2));
        String unresolved = "Module m.\n\nRule f given x as Unbekannt, produce Int:\n  Return 1.\n";
        assertThrows(InProcessCnlParser.NameResolutionFailure.class, () -> InProcessCnlParser.parse(unresolved, "en-US"));
    }

    @Test
    void 预算未用完时名称解析之后照常成功() {
        ParseBudget.configure(Duration.ofSeconds(2));
        AtomicBoolean called = new AtomicBoolean();
        assertEquals("m", InProcessCnlParser.parseUnsafeWithAliases(NORMAL, "en-US", null, null,
            slowResolver(Duration.ofMillis(10), called)).moduleName());
    }

    /**
     * 不变量：预算用完后不再开始名称解析。源码无法确定地让预算恰好在构建 AST 与名称解析之间耗尽（ParseBudget 直接读
     * nanoTime），1ns 预算可能先在语法分析的预测里中止；无论哪一处检查先触发，名称解析都不能开始。名称解析之前的
     * 边界检查与之后的检查共用 checkStageBudget，后者由上面两个用例确定地覆盖。
     */
    @Test
    void 预算用完后不再开始名称解析() {
        AtomicBoolean called = new AtomicBoolean();
        ParseBudget budget = ParseBudget.start(Duration.ofNanos(1));
        InProcessCnlParser.CnlParseException e = assertThrows(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parseUnsafeWithAliases(NORMAL, "en-US", null, null,
                slowResolver(Duration.ZERO, called), budget, org.antlr.v4.runtime.atn.PredictionMode.SLL));
        assertInstanceOf(InProcessCnlParser.BudgetExceededFailure.class, e);
        assertFalse(called.get(), "预算用完后不应再开始名称解析");
    }
}
