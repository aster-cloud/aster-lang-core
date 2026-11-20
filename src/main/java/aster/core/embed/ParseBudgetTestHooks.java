package aster.core.embed;

import java.time.Duration;

/**
 * 宿主测试访问 {@link ParseBudget} 测试接缝的唯一公开入口（只供测试使用）。
 *
 * <p>aster-api 的 REST 预算测试要在真实墙钟上验证「解析超预算 → E054、计数、线程释放」，需要
 * {@link ParseBudget#slowChecksForTesting} 把任意输入变慢；那个方法是包内可见，宿主测试不在本包里。
 * 生产代码不得调用本类：停顿是进程级的，开着会让同一 JVM 里之后的每次解析都变慢。测试结束必须
 * 以 {@code slowChecks(null)} 恢复。
 */
public final class ParseBudgetTestHooks {

    private ParseBudgetTestHooks() {
    }

    /** 见 {@link ParseBudget#slowChecksForTesting}；null 关闭。 */
    public static void slowChecks(Duration delay) {
        ParseBudget.slowChecksForTesting(delay);
    }
}
