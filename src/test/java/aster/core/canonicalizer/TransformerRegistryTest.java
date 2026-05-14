package aster.core.canonicalizer;

import aster.core.lexicon.CanonicalizationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R5-Backend-3：测试 owner-aware transformer 注册。
 */
class TransformerRegistryTest {

    @AfterEach
    void cleanup() {
        TransformerRegistry.reset();
    }

    @Test
    void testRegisterAllWithOwnerFirstWinsForDifferentOwners() {
        URLClassLoader loaderA = new URLClassLoader("A", new java.net.URL[0], null);
        URLClassLoader loaderB = new URLClassLoader("B", new java.net.URL[0], null);

        SyntaxTransformer tA = mock("A");
        SyntaxTransformer tB = mock("B");

        TransformerRegistry.registerAllWithOwner(Map.of("test-tx", () -> tA), loaderA);
        TransformerRegistry.registerAllWithOwner(Map.of("test-tx", () -> tB), loaderB);

        // first-wins: loaderA 应仍持有 test-tx
        assertSame(tA, TransformerRegistry.get("test-tx"),
            "不同 owner 注册同名 transformer 时 first-wins");
    }

    @Test
    void testRegisterAllWithOwnerAllowsSameOwnerUpgrade() {
        URLClassLoader loader = new URLClassLoader("A", new java.net.URL[0], null);
        SyntaxTransformer v1 = mock("v1");
        SyntaxTransformer v2 = mock("v2");

        TransformerRegistry.registerAllWithOwner(Map.of("upgrade-tx", () -> v1), loader);
        TransformerRegistry.registerAllWithOwner(Map.of("upgrade-tx", () -> v2), loader);

        assertSame(v2, TransformerRegistry.get("upgrade-tx"),
            "同 owner 再注册视为升级，新值覆盖旧值");
    }

    @Test
    void testUnregisterByOwnerRemovesOnlyOwnedTransformers() {
        URLClassLoader loaderA = new URLClassLoader("A", new java.net.URL[0], null);
        URLClassLoader loaderB = new URLClassLoader("B", new java.net.URL[0], null);

        TransformerRegistry.registerAllWithOwner(Map.of("only-a-1", () -> mock("a1"),
                                                       "only-a-2", () -> mock("a2")), loaderA);
        TransformerRegistry.registerAllWithOwner(Map.of("only-b", () -> mock("b")), loaderB);

        Set<String> removed = TransformerRegistry.unregisterByOwner(loaderA);
        assertEquals(Set.of("only-a-1", "only-a-2"), removed);
        assertFalse(TransformerRegistry.contains("only-a-1"));
        assertFalse(TransformerRegistry.contains("only-a-2"));
        assertTrue(TransformerRegistry.contains("only-b"),
            "其他 owner 的 transformer 不应被影响");

        // 内置 transformer 不动
        assertTrue(TransformerRegistry.contains("english-possessive"));
        assertTrue(TransformerRegistry.contains("result-is"));
        assertTrue(TransformerRegistry.contains("set-to"));
    }

    @Test
    void testUnregisterByOwnerNullReturnsEmpty() {
        Set<String> removed = TransformerRegistry.unregisterByOwner(null);
        assertTrue(removed.isEmpty());
    }

    @Test
    void testR7ConcurrentRegisterAllWithOwnerFirstWinsExactlyOnce() throws Exception {
        // R7-Backend-7：N 个线程并发 registerAllWithOwner(name=concurrent-tx, 各自 owner)
        // 必须**恰好一个**赢，其余跳过。最终 owner 必须等于赢家 loader。
        int N = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(N);
        URLClassLoader[] loaders = new URLClassLoader[N];
        SyntaxTransformer[] transformers = new SyntaxTransformer[N];

        for (int i = 0; i < N; i++) {
            loaders[i] = new URLClassLoader("L" + i, new java.net.URL[0], null);
            transformers[i] = mock("T" + i);
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    TransformerRegistry.registerAllWithOwner(
                        Map.of("concurrent-tx", () -> transformers[idx]), loaders[idx]);
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        done.await();

        // exactly 一个 transformer 是当前活跃的
        SyntaxTransformer winner = TransformerRegistry.get("concurrent-tx");
        int winnerIdx = -1;
        for (int i = 0; i < N; i++) {
            if (transformers[i] == winner) { winnerIdx = i; break; }
        }
        assertTrue(winnerIdx >= 0,
            "R7-Backend-7: 必须有一个 transformer 赢得 first-wins 之战");

        // unregisterByOwner(winner) 应清理；其他 owner 的 unregister 应是 no-op
        Set<String> removed = TransformerRegistry.unregisterByOwner(loaders[winnerIdx]);
        assertTrue(removed.contains("concurrent-tx"));
        for (int i = 0; i < N; i++) {
            if (i != winnerIdx) {
                Set<String> shouldBeEmpty = TransformerRegistry.unregisterByOwner(loaders[i]);
                assertFalse(shouldBeEmpty.contains("concurrent-tx"),
                    "R7-Backend-7: 非 winner 的 unregister 不应能清掉 concurrent-tx");
            }
        }
        assertFalse(TransformerRegistry.contains("concurrent-tx"),
            "all owners gone → transformer should be gone");
    }

    @Test
    void testRegisterAllWithOwnerDoesNotOverwriteBuiltins() {
        URLClassLoader loader = new URLClassLoader("X", new java.net.URL[0], null);
        SyntaxTransformer evil = mock("evil");

        // 试图覆盖内置 english-possessive
        TransformerRegistry.registerAllWithOwner(Map.of("english-possessive", () -> evil), loader);

        // 内置 transformer 保持原样（不应被 owner 注册覆盖）
        assertNotSame(evil, TransformerRegistry.get("english-possessive"));

        // 这个 owner 也没有获取到 english-possessive 的所有权
        Set<String> removed = TransformerRegistry.unregisterByOwner(loader);
        assertFalse(removed.contains("english-possessive"));
    }

    /** 占位 mock —— 仅用于身份比较 */
    private static SyntaxTransformer mock(String tag) {
        return new SyntaxTransformer() {
            @Override
            public String transform(String source,
                                    CanonicalizationConfig config,
                                    StringSegmenter segmenter) {
                return tag + ":" + source;
            }
            @Override
            public String toString() { return "mock(" + tag + ")"; }
        };
    }
}
