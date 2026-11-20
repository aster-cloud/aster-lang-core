package aster.core.embed;

import java.time.Duration;

/**
 * 宿主测试访问 {@link ParseBudget} 测试接缝的唯一入口，放在 test fixtures（不进主 jar）。
 *
 * <p>aster-api 的 REST 预算测试要在真实墙钟上验证「解析超预算 → E054、计数、线程释放」，需要
 * {@link ParseBudget#slowChecksForTesting} 把任意输入变慢；那个方法是包内可见，宿主测试不在本包里。
 * 宿主以 {@code testImplementation(testFixtures("cloud.aster-lang:aster-lang-core"))} 引入本类（发布为
 * {@code test-fixtures} 分类器 jar），生产 classpath 上没有它。停顿是进程级的，测试结束必须以
 * {@code slowChecks(null)} 恢复，否则同一 JVM 里之后的每次解析都会变慢。
 */
public final class ParseBudgetTestHooks {

    private ParseBudgetTestHooks() {
    }

    /** 见 {@link ParseBudget#slowChecksForTesting}；null 关闭。 */
    public static void slowChecks(Duration delay) {
        ParseBudget.slowChecksForTesting(delay);
    }
}
