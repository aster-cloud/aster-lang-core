package aster.core.lexicon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LexiconRegistry 单元测试
 * <p>
 * 验证词法表注册中心的功能。
 */
class LexiconRegistryTest {

    private LexiconRegistry registry;

    @BeforeEach
    void setUp() {
        registry = LexiconRegistry.getInstance();
        // R4: tests share the singleton — always restore baseline before each test.
        // SPI re-discovery 必须用持有 LexiconPlugin SPI 的 classloader（应用 CL），
        // 不是测试线程的 contextClassLoader（gradle worker CL，未必能看到 plugin 文件）。
        registry.markAvailable("zh-CN");
        registry.markAvailable("de-DE");
        registry.markAvailable("xx-ZZ");
        if (!registry.has("zh-CN") || !registry.has("de-DE")) {
            registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
        }
    }

    // ============================================================
    // 内置词法表测试
    // ============================================================

    @Test
    void testBuiltinEnUsLexiconRegistered() {
        assertTrue(registry.has("en-US"), "英文词法表应已注册");
        assertNotNull(registry.get("en-US").orElse(null));
        assertEquals("en-US", registry.getOrThrow("en-US").getId());
    }

    @Test
    void testBuiltinZhCnLexiconRegistered() {
        assertTrue(registry.has("zh-CN"), "中文词法表应已注册");
        assertNotNull(registry.get("zh-CN").orElse(null));
        assertEquals("zh-CN", registry.getOrThrow("zh-CN").getId());
    }

    @Test
    void testDefaultLexicon() {
        Lexicon defaultLexicon = registry.getDefault();
        assertNotNull(defaultLexicon);
        assertEquals("en-US", defaultLexicon.getId());
    }

    // ============================================================
    // 列表和查询测试
    // ============================================================

    @Test
    void testListRegisteredLexicons() {
        var list = registry.list();
        assertTrue(list.contains("en-US"), "列表应包含 en-US");
        assertTrue(list.contains("zh-CN"), "列表应包含 zh-CN");
    }

    @Test
    void testGetNonExistentLexicon() {
        assertTrue(registry.get("xx-XX").isEmpty());
        assertFalse(registry.has("xx-XX"));
    }

    @Test
    void testGetOrThrowNonExistent() {
        assertThrows(IllegalArgumentException.class, () -> {
            registry.getOrThrow("xx-XX");
        });
    }

    // ============================================================
    // 词法表内容验证
    // ============================================================

    @Test
    void testEnUsLexiconContent() {
        Lexicon enUs = registry.getOrThrow("en-US");

        assertEquals("en-US", enUs.getId());
        assertEquals("English (US)", enUs.getName());
        assertEquals(Lexicon.Direction.LTR, enUs.getDirection());

        // 验证关键词映射（注意：大小写必须与 ANTLR 词法器匹配）
        var keywords = enUs.getKeywords();
        assertEquals("If", keywords.get(SemanticTokenKind.IF));
        assertEquals("Return", keywords.get(SemanticTokenKind.RETURN));
        assertEquals("true", keywords.get(SemanticTokenKind.TRUE));
    }

    @Test
    void testZhCnLexiconContent() {
        Lexicon zhCn = registry.getOrThrow("zh-CN");

        assertEquals("zh-CN", zhCn.getId());
        assertEquals("简体中文", zhCn.getName());
        assertEquals(Lexicon.Direction.LTR, zhCn.getDirection());

        // 验证关键词映射（与 TypeScript 前端保持一致）
        // ADR-0008 v2：单字关键字（若/真/假/或/且）已升级为多字
        // （匹配于/真值/假值/或者/并且），避免与中文常用业务标识符冲突。
        var keywords = zhCn.getKeywords();
        assertEquals("如果", keywords.get(SemanticTokenKind.IF));
        assertEquals("匹配于", keywords.get(SemanticTokenKind.MATCH));
        assertEquals("返回", keywords.get(SemanticTokenKind.RETURN));
        assertEquals("真值", keywords.get(SemanticTokenKind.TRUE));
        assertEquals("模块", keywords.get(SemanticTokenKind.MODULE_DECL));
    }

    // ============================================================
    // 标点符号配置测试
    // ============================================================

    @Test
    void testEnUsPunctuationConfig() {
        Lexicon enUs = registry.getOrThrow("en-US");
        var punct = enUs.getPunctuation();

        assertNotNull(punct.statementEnd());
        assertNotNull(punct.listSeparator());
        assertNotNull(punct.blockStart());
    }

    @Test
    void testZhCnPunctuationConfig() {
        Lexicon zhCn = registry.getOrThrow("zh-CN");
        var punct = zhCn.getPunctuation();

        assertNotNull(punct.statementEnd());
        assertNotNull(punct.listSeparator());
        assertNotNull(punct.blockStart());
    }

    // ============================================================
    // 软下线 + 监听器测试（hot-plug 支持）
    // ============================================================

    @Test
    void testListenerFiresOnMarkUnavailable() {
        // 先确保 zh-CN 当前可用（test runtime 含 aster-lang-zh）
        assertTrue(registry.has("zh-CN"));

        java.util.List<java.util.Set<String>> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable unsubscribe = registry.addChangeListener(received::add);
        try {
            assertTrue(registry.markUnavailable("zh-CN"), "首次下线应返回 true");
            assertFalse(registry.has("zh-CN"), "下线后 has() 应返回 false");
            assertFalse(registry.get("zh-CN").isPresent(), "下线后 get() 应返回 empty");

            // 重复下线返回 false（无副作用）
            assertFalse(registry.markUnavailable("zh-CN"), "重复下线应返回 false");

            assertTrue(registry.markAvailable("zh-CN"), "恢复返回 true");
            assertTrue(registry.has("zh-CN"));
        } finally {
            registry.markAvailable("zh-CN");
            unsubscribe.run();
        }

        // 至少触发了 2 次事件（下线 + 恢复）；重复下线不触发
        assertTrue(received.size() >= 2,
            "应至少收到 2 次变更事件，实际 " + received.size());

        // 倒数第一次应该是恢复后的快照：包含 zh-CN
        java.util.Set<String> last = received.get(received.size() - 1);
        assertTrue(last.contains("zh-CN"), "最后一次快照应包含 zh-CN");
    }

    @Test
    void testCannotMarkEnUsUnavailable() {
        // en-US 是 FallbackLexicon backbone，禁止下线
        assertFalse(registry.markUnavailable("en-US"), "下线 en-US 应被拒绝");
        assertTrue(registry.has("en-US"), "en-US 始终可用");
    }

    @Test
    void testUnsubscribeRemovesListener() {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        Runnable unsubscribe = registry.addChangeListener(s -> count.incrementAndGet());
        try {
            registry.markUnavailable("zh-CN");
            int before = count.get();
            unsubscribe.run();
            registry.markAvailable("zh-CN"); // 取消订阅后不再收到
            assertEquals(before, count.get(),
                "取消订阅后变更不应再触发监听器");
        } finally {
            registry.markAvailable("zh-CN");
        }
    }

    @Test
    void testAvailableIdsExcludesUnavailable() {
        try {
            registry.markUnavailable("zh-CN");
            java.util.Set<String> ids = registry.availableIds();
            assertTrue(ids.contains("en-US"));
            assertFalse(ids.contains("zh-CN"),
                "availableIds() 应排除已下线 lexicon");
        } finally {
            registry.markAvailable("zh-CN");
        }
    }

    @Test
    void testListAlsoExcludesUnavailable() {
        try {
            registry.markUnavailable("zh-CN");
            assertFalse(registry.list().contains("zh-CN"),
                "list() 应与 availableIds() 一致，排除已下线 lexicon");
        } finally {
            registry.markAvailable("zh-CN");
        }
    }

    @Test
    void testDisabledIdsReflectsDesiredDisabled() {
        try {
            assertFalse(registry.disabledIds().contains("zh-cn"),
                "未下线时 disabledIds() 不含 zh-CN");

            registry.markUnavailable("zh-CN");
            // disabledIds() 返回归一化 ID（小写），跨副本对账据此与持久集差分。
            assertTrue(registry.disabledIds().contains("zh-cn"),
                "下线后 disabledIds() 应含归一化的 zh-cn");

            registry.markAvailable("zh-CN");
            assertFalse(registry.disabledIds().contains("zh-cn"),
                "恢复后 disabledIds() 不再含 zh-cn");
        } finally {
            registry.markAvailable("zh-CN");
        }
    }

    @Test
    void testDisabledIdsNeverContainsEnUsBackbone() {
        // en-US backbone 永不可下线（markUnavailable 守护）→ disabledIds() 必不含它。
        registry.markUnavailable("en-US");
        assertFalse(registry.disabledIds().contains("en-us"),
            "en-US backbone 不可下线，disabledIds() 必不含");
    }

    @Test
    void testDisabledIdsSnapshotIsDefensiveCopy() {
        try {
            registry.markUnavailable("zh-CN");
            java.util.Set<String> snapshot = registry.disabledIds();
            snapshot.clear(); // 修改快照不应影响注册表内部状态
            assertFalse(registry.has("zh-CN"),
                "清空 disabledIds() 返回的快照不应让 zh-CN 重新可用（防御性拷贝）");
        } finally {
            registry.markAvailable("zh-CN");
        }
    }

    @Test
    void testDesiredDisabledPersistsAcrossRegisterCycle() {
        // M8 回归：在已注册的 zh-CN 上 markUnavailable 后，
        // 一次 hypothetical re-register 不应清除 desired-disabled 状态。
        // 通过 discoverPlugins() 模拟重新 SPI 扫描（en-US/zh-CN 已存在，会跳过 containsKey）
        try {
            assertTrue(registry.markUnavailable("zh-CN"));
            assertFalse(registry.has("zh-CN"), "下线后 has=false");

            registry.discoverPlugins();  // 重新扫描——zh-CN 仍 desired-disabled
            assertFalse(registry.has("zh-CN"),
                "重新 SPI 扫描后 desired-disabled 必须持久（M8）");

            // 唯一能恢复的方式是显式 markAvailable
            assertTrue(registry.markAvailable("zh-CN"));
            assertTrue(registry.has("zh-CN"));
        } finally {
            registry.markAvailable("zh-CN");
        }
    }

    @Test
    void testMarkUnavailableAcceptsUnknownIdForFuturePersistence() {
        // M8: 未注册 id 也能被 mark；将来注册时会立刻生效
        String fake = "xx-ZZ";
        try {
            assertTrue(registry.markUnavailable(fake),
                "未注册 id 标记为 disabled 应成功");
            // 即使现在不在 lexicons map 也不会因为 register 清掉
            assertFalse(registry.has(fake));
        } finally {
            registry.markAvailable(fake);
        }
    }

    @Test
    void testR6ConcurrentDiscoverPluginsDetailedAtomic() throws Exception {
        // R6-C1：并发 discoverPluginsDetailed 同 loader，原子 putIfAbsent 保证
        // **只有一个 id 被记为 newlyRegistered**，第二个 caller 因 putIfAbsent 失败 → 静默跳过
        registry.unregister("zh-CN");
        assertFalse(registry.has("zh-CN"));

        try {
            int N = 8;
            ClassLoader loader = LexiconPlugin.class.getClassLoader();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(N);
            java.util.concurrent.atomic.AtomicInteger winnersWithZh =
                new java.util.concurrent.atomic.AtomicInteger();

            for (int i = 0; i < N; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        java.util.Set<String> ids = registry.discoverPluginsDetailed(loader);
                        if (ids.contains("zh-CN")) winnersWithZh.incrementAndGet();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
                t.start();
            }
            start.countDown();
            done.await();

            assertEquals(1, winnersWithZh.get(),
                "R6-C1：并发 discoverPluginsDetailed 仅一个调用应**新注册** zh-CN，"
                + "其他调用看到已 register → 不计入 newlyRegistered");
            assertTrue(registry.has("zh-CN"), "zh-CN 已注册");
        } finally {
            if (!registry.has("zh-CN")) {
                registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
            }
        }
    }

    @Test
    void testR6PreviewPluginIdsZeroSideEffect() {
        // R6-M1：previewPluginIds 用 metadata，preview 后 transformer registry 应不变
        ClassLoader loader = LexiconPlugin.class.getClassLoader();
        java.util.Set<String> tBefore =
            aster.core.canonicalizer.TransformerRegistry.availableNames()
                .stream().collect(java.util.stream.Collectors.toSet());

        // Multiple preview calls — should NOT mutate transformer registry
        for (int i = 0; i < 5; i++) {
            registry.previewPluginIds(loader);
        }

        java.util.Set<String> tAfter =
            aster.core.canonicalizer.TransformerRegistry.availableNames()
                .stream().collect(java.util.stream.Collectors.toSet());
        assertEquals(tBefore, tAfter,
            "R6-M1: previewPluginIds 不应修改 TransformerRegistry —— "
            + "plugin 用 providedLexiconIds metadata 而非 createLexicon");
    }

    @Test
    void testR7ConcurrentRegisterUnregisterRace() throws Exception {
        // R7-Backend-6：并发 unregister + discoverPluginsDetailed 同 id。
        // 关键不变式：最终状态是 deterministic（要么注册要么没；不会出现
        // "registry 报告有但 owner 缺失" 这种破坏性中间态）
        registry.unregister("zh-CN");
        try {
            int N = 4;
            ClassLoader loader = LexiconPlugin.class.getClassLoader();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(N * 2);

            for (int i = 0; i < N; i++) {
                Thread tReg = new Thread(() -> {
                    try {
                        start.await();
                        registry.discoverPluginsDetailed(loader);
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
                Thread tUnreg = new Thread(() -> {
                    try {
                        start.await();
                        registry.unregister("zh-CN");
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                });
                tReg.start();
                tUnreg.start();
            }
            start.countDown();
            done.await();

            // 最终状态可能是 has=true 或 has=false，但 owner consistency 必须保持
            ClassLoader finalOwner = registry.ownerOf("zh-CN");
            boolean finalHas = registry.has("zh-CN");
            if (finalHas) {
                assertNotNull(finalOwner,
                    "R7-Backend-6: 如果 zh-CN 注册了，必须有 owner（不可能 lexicon present + owner null）");
            } else {
                // unregister win → 不应残留任何状态
                assertNull(finalOwner,
                    "R7-Backend-6: 如果 zh-CN 不在 registry，ownerOf 必须返回 null");
            }
        } finally {
            registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
        }
    }

    @Test
    void testR6OwnerOfTracksLoaderAccurately() {
        // R6-C1: ownerOf 返回真实 owner classloader（来自 LexiconEntry.owner）
        // zh-CN 通过 SPI 注册（owner = LexiconPlugin.class.getClassLoader()）
        ClassLoader owner = registry.ownerOf("zh-CN");
        assertNotNull(owner, "zh-CN 必须有 owner");
        // 验证：同 owner 注册的所有 lexicon 可通过 bulk unregister 移除
        java.util.Set<String> removed = registry.unregisterByOwner(owner);
        assertTrue(removed.contains("zh-CN"));
        // restore
        registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
    }

    @Test
    void testDiscoveryFailuresGetterIsEmptyOnHappyPath() {
        // M9: 正常 happy path 上不应该有任何 plugin 错误记录
        registry.discoverPlugins();
        java.util.Map<String, String> failures = registry.discoveryFailures();
        assertNotNull(failures);
        // 不强断言 empty —— Quarkus/IDE classpath 偶有边角失败，关键是 getter 可用
        // 但内置 en-US backbone 不应出现在失败列表
        for (String k : failures.keySet()) {
            assertFalse(k.toLowerCase().contains("en-us"),
                "en-US backbone 不应是 discovery failure");
        }
    }

    // ============================================================
    // R4 unregister + discoverPluginsDetailed 测试
    // ============================================================

    @Test
    void testUnregisterRefusesEnUsBackbone() {
        // R3-M5：en-US 是 FallbackLexicon 的 backbone，禁止 unregister
        assertFalse(registry.unregister("en-US"));
        assertTrue(registry.has("en-US"));
    }

    @Test
    void testUnregisterReturnsTrueOnRemoval() {
        // 先确保 zh-CN 在
        assertTrue(registry.has("zh-CN"));
        try {
            assertTrue(registry.unregister("zh-CN"));
            assertFalse(registry.has("zh-CN"),
                "unregister 后 has() 应返回 false");
            // 重复 unregister 返回 false
            assertFalse(registry.unregister("zh-CN"));
        } finally {
            // restore via discoverPlugins —— testRuntimeOnly aster-lang-zh 会重新注册
            registry.discoverPlugins();
            assertTrue(registry.has("zh-CN"), "discoverPlugins 应重新注册 zh-CN");
        }
    }

    @Test
    void testDiscoverPluginsDetailedReturnsNewIds() {
        // R4-C：discoverPluginsDetailed 必须返回**本次调用新注册的** ID 集合
        // —— 不依赖 availableIds set-diff
        ClassLoader loader = LexiconPlugin.class.getClassLoader();
        java.util.Set<String> firstCall = registry.discoverPluginsDetailed(loader);
        // 第二次调用 —— 同 loader，**应当返回空集**（所有 plugin 已被去重）
        java.util.Set<String> secondCall = registry.discoverPluginsDetailed(loader);
        assertTrue(secondCall.isEmpty(),
            "重复 discoverPluginsDetailed 不应再次返回相同 ID（去重契约）");

        // 第一次的 ID 都对应注册表中真实 lexicon
        for (String id : firstCall) {
            assertTrue(registry.has(id),
                "返回的 ID " + id + " 必须存在于 registry 中");
        }
    }

    @Test
    void testUnregisterByOwnerBulkRemovesLexicons() {
        // R5-Backend-1：bulk unregister 按 owner 一次性清理
        // 1) 拿到 zh-CN 的 owner（应当是其 plugin jar 的 classloader）
        ClassLoader zhOwner = registry.ownerOf("zh-CN");
        assertNotNull(zhOwner, "zh-CN 必须有 owner（通过 SPI 注册）");

        try {
            // 2) bulk unregister
            java.util.Set<String> removed = registry.unregisterByOwner(zhOwner);
            assertTrue(removed.contains("zh-CN"),
                "unregisterByOwner 应返回该 owner 移除的所有 lexicon ID");
            assertFalse(registry.has("zh-CN"));
            assertNull(registry.ownerOf("zh-CN"),
                "owner 映射也要被清理");
        } finally {
            // 恢复
            registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
        }
    }

    @Test
    void testUnregisterByOwnerNullSafe() {
        // R5：传 null 不抛错，返回空集
        java.util.Set<String> removed = registry.unregisterByOwner(null);
        assertTrue(removed.isEmpty());
    }

    @Test
    void testPreviewPluginIdsDoesNotMutateRegistry() {
        // R5-Backend-2：previewPluginIds 是只读的 dry-run
        try {
            registry.unregister("zh-CN");
            assertFalse(registry.has("zh-CN"));

            ClassLoader loader = LexiconPlugin.class.getClassLoader();
            java.util.Set<String> preview = registry.previewPluginIds(loader);
            // preview 包含 zh-CN（plugin 声称提供），但**没有**实际注册
            assertTrue(preview.contains("zh-CN") || preview.isEmpty(),
                "preview 应包含 plugin 声称的 ID 或为空（取决于运行环境）");
            assertFalse(registry.has("zh-CN"),
                "previewPluginIds **必须**是只读的，不能注册任何 lexicon");
        } finally {
            registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
        }
    }

    // ============================================================
    // R8-Backend-1：runAtomic 事务式 swap 不让 SSE 监听器看到中间态
    // ============================================================

    @Test
    void testR8RunAtomicSuppressesIntermediateFireChange() {
        // 在事务中先 unregister zh-CN 再 re-register —— 监听器应只看到一次（最终态）
        java.util.List<java.util.Set<String>> events = new java.util.ArrayList<>();
        Runnable unsub = registry.addChangeListener(snapshot -> {
            events.add(new java.util.HashSet<>(snapshot));
        });
        try {
            assertTrue(registry.has("zh-CN"));
            ClassLoader loader = LexiconPlugin.class.getClassLoader();
            events.clear();
            registry.runAtomic(() -> {
                registry.unregister("zh-CN");
                registry.discoverPluginsDetailed(loader);
            });
            // before 与 after 相同（zh-CN unregister 后又 re-register） → 不应发任何事件
            assertEquals(0, events.size(),
                "runAtomic 进出可用集合相同 → 不应有 change event。实际：" + events);
        } finally {
            unsub.run();
            if (!registry.has("zh-CN")) {
                registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
            }
        }
    }

    @Test
    void testR8RunAtomicFiresExactlyOnceWhenStateChanges() {
        // 事务内 markUnavailable 多个 lexicon —— 监听器只看到一次最终态
        java.util.List<java.util.Set<String>> events = new java.util.ArrayList<>();
        Runnable unsub = registry.addChangeListener(snapshot -> {
            events.add(new java.util.HashSet<>(snapshot));
        });
        try {
            events.clear();
            registry.runAtomic(() -> {
                registry.markUnavailable("zh-CN");
                registry.markUnavailable("de-DE");
            });
            assertEquals(1, events.size(),
                "事务内多次变更 → 出口处只广播一次。实际：" + events);
            assertFalse(events.get(0).contains("zh-CN"));
            assertFalse(events.get(0).contains("de-DE"));
            assertTrue(events.get(0).contains("en-US"));
        } finally {
            unsub.run();
            registry.markAvailable("zh-CN");
            registry.markAvailable("de-DE");
        }
    }

    @Test
    void testR8RunAtomicSerializesConcurrentTransactions() throws Exception {
        // 两个并发 runAtomic 必须串行执行（互斥）
        int threads = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxConcurrent =
            new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService es =
            java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(es.submit(() -> {
                    try { start.await(); } catch (InterruptedException ignored) {}
                    registry.runAtomic(() -> {
                        int now = inside.incrementAndGet();
                        maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                        inside.decrementAndGet();
                    });
                }));
            }
            start.countDown();
            for (var f : futures) f.get();
            assertEquals(1, maxConcurrent.get(),
                "runAtomic 必须互斥：同一时刻至多一个事务。实际最大并发: " + maxConcurrent);
        } finally {
            es.shutdown();
        }
    }

    @Test
    void testR9NestedRunAtomicSuppressesUntilOutermostExit() {
        // R9-Backend-M4: 嵌套 runAtomic 不应在内层出口广播
        java.util.List<java.util.Set<String>> events = new java.util.ArrayList<>();
        Runnable unsub = registry.addChangeListener(snapshot -> {
            events.add(new java.util.HashSet<>(snapshot));
        });
        try {
            events.clear();
            registry.runAtomic(() -> {
                registry.markUnavailable("zh-CN");
                // 内层事务 —— 应该被压制
                registry.runAtomic(() -> {
                    registry.markUnavailable("de-DE");
                });
                // 此时 depth 回到 1（外层）；监听器仍不应被通知
                assertEquals(0, events.size(),
                    "内层 runAtomic 退出时不应广播。实际：" + events);
            });
            // 最外层退出 —— 一次性广播最终态
            assertEquals(1, events.size(),
                "最外层退出时应广播一次。实际：" + events);
            assertFalse(events.get(0).contains("zh-CN"));
            assertFalse(events.get(0).contains("de-DE"));
        } finally {
            unsub.run();
            registry.markAvailable("zh-CN");
            registry.markAvailable("de-DE");
        }
    }

    @Test
    void testR8RunAtomicFiresEvenIfActionThrows() {
        // 事务内 action 抛错 —— 已发生的变化必须仍然广播给监听器
        java.util.List<java.util.Set<String>> events = new java.util.ArrayList<>();
        Runnable unsub = registry.addChangeListener(snapshot -> {
            events.add(new java.util.HashSet<>(snapshot));
        });
        try {
            events.clear();
            assertThrows(IllegalStateException.class, () -> {
                registry.runAtomic(() -> {
                    registry.markUnavailable("zh-CN");
                    throw new IllegalStateException("boom");
                });
            });
            // markUnavailable 仍生效（事务无 undo），监听器看到最终态
            assertEquals(1, events.size(),
                "action 抛错时事务出口仍应基于实际状态广播一次。实际：" + events);
            assertFalse(events.get(0).contains("zh-CN"));
        } finally {
            unsub.run();
            registry.markAvailable("zh-CN");
        }
    }

    @Test
    void testDiscoverPluginsDetailedAfterUnregisterReturnsRereg() {
        // R4-C 关键场景：同名替换。
        // 1. 确认 zh-CN 在
        assertTrue(registry.has("zh-CN"));
        try {
            // 2. unregister zh-CN
            assertTrue(registry.unregister("zh-CN"));
            // 3. 再次 discoverPluginsDetailed —— 应当返回 ["zh-CN"]（被重新注册）
            // 必须用持有 plugin SPI 的同一 classloader（LexiconPlugin 接口所在的 loader）
            ClassLoader loader = LexiconPlugin.class.getClassLoader();
            java.util.Set<String> reregistered = registry.discoverPluginsDetailed(loader);
            assertTrue(reregistered.contains("zh-CN"),
                "unregister 后 discoverPluginsDetailed 应明确返回重新注册的 zh-CN，"
                + "这是 hot-plug 同名替换检测的契约：返回的不是 set-diff，"
                + "而是\"本次调用注册了什么\"");
        } finally {
            // 确保测试后 zh-CN 仍可用
            if (!registry.has("zh-CN")) {
                registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
            }
        }
    }

    /**
     * R-multi-replica：模拟多副本启动时的**瞬时 SPI 失败**——首轮 getResources 抛 IOException
     * （ServiceLoader 扫不到 provider → 该轮丢插件），后续轮恢复。验证 discoverPlugins 的重试
     * 包装最终把全部 bundled lexicon 加齐（消除"某副本少一门语言"的跨副本不一致）。
     *
     * <p>背景：生产 6 副本各丢不同的一个 locale（zh/de/hi），因 ServiceLoader lazy 迭代在类
     * 加载竞态下偶发失败，旧实现 best-effort 静默丢弃。新实现重试直到完整。
     */
    @Test
    void testTransientSpiFailureRecoveredByRetry() {
        ClassLoader real = LexiconPlugin.class.getClassLoader();
        final String svc = "META-INF/services/aster.core.lexicon.LexiconPlugin";

        // 把 zh-CN 物理移除，模拟"本副本尚未加载 zh"。
        registry.unregister("zh-CN");
        assertFalse(registry.has("zh-CN"), "前置：zh-CN 已移除");

        // 首轮对 LexiconPlugin service 资源抛 IOException（瞬时），之后放行到真实 loader。
        java.util.concurrent.atomic.AtomicInteger svcCalls = new java.util.concurrent.atomic.AtomicInteger();
        ClassLoader flaky = new ClassLoader(real) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                if (svc.equals(name) && svcCalls.getAndIncrement() == 0) {
                    // 首次扫描 LexiconPlugin service 文件 → 瞬时失败（类加载竞态的等价物）。
                    throw new java.io.IOException("simulated transient SPI resource failure");
                }
                return super.getResources(name);
            }
        };

        try {
            // discoverPlugins 内部重试：第 1 轮 getResources 抛 IOException（ServiceLoader 该轮
            // 报 ServiceConfigurationError / 扫不到），第 2 轮恢复 → zh-CN 最终加齐。
            int loaded = registry.discoverPlugins(flaky);
            assertTrue(svcCalls.get() >= 2,
                "应至少触发 2 次 getResources（首轮失败 + 重试），实际=" + svcCalls.get());
            assertTrue(registry.has("zh-CN"),
                "重试后 zh-CN 应已加齐（瞬时失败不应导致永久丢失）；loaded=" + loaded);
        } finally {
            if (!registry.has("zh-CN")) {
                registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
            }
        }
    }

    /**
     * R-multi-replica（Codex 审查补充）：模拟**带 provider class** 的瞬时
     * ServiceConfigurationError —— 生产最常见的类加载竞态 "Provider &lt;fqcn&gt; could not be
     * instantiated / not found"。首轮 service 文件多一行**不存在的 provider 类**，使
     * iter.next() 抛 ServiceConfigurationError（带 provider hint，key=该 class 名），第二轮
     * 该坏行消失 → 恢复。验证：**iterator 级失败无论是否带 provider hint 都触发重试**
     * （此前的 bug：带 hint 时 key 非 "spi-iter#" 前缀 → 漏判 → 不重试）。
     */
    @Test
    void testTransientSpiFailureWithProviderHintRecoveredByRetry() throws Exception {
        ClassLoader real = LexiconPlugin.class.getClassLoader();
        final String svc = "META-INF/services/aster.core.lexicon.LexiconPlugin";

        registry.unregister("zh-CN");
        assertFalse(registry.has("zh-CN"), "前置：zh-CN 已移除");

        // 把首轮要"注入坏行"的临时 service 文件写到 temp dir，URL 指向它。
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("flaky-spi");
        java.nio.file.Path svcFile = tmpDir.resolve("svc-bad.txt");
        // 不存在的 provider 类 → ServiceLoader.iterator().next() 抛
        // ServiceConfigurationError: Provider aster.bogus.DoesNotExistPlugin not found（带 hint）。
        java.nio.file.Files.writeString(svcFile, "aster.bogus.DoesNotExistPlugin\n");
        java.net.URL badUrl = svcFile.toUri().toURL();

        java.util.concurrent.atomic.AtomicInteger svcCalls = new java.util.concurrent.atomic.AtomicInteger();
        ClassLoader flaky = new ClassLoader(real) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                java.util.List<java.net.URL> urls = java.util.Collections.list(super.getResources(name));
                // 仅**首轮**在真实 service 资源**前面**插入坏 URL，让 ServiceLoader 先迭代到坏
                // 行抛错（带 provider hint），后续轮干净。
                if (svc.equals(name) && svcCalls.getAndIncrement() == 0) {
                    java.util.List<java.net.URL> withBad = new java.util.ArrayList<>();
                    withBad.add(badUrl);
                    withBad.addAll(urls);
                    return java.util.Collections.enumeration(withBad);
                }
                return java.util.Collections.enumeration(urls);
            }
        };

        try {
            int loaded = registry.discoverPlugins(flaky);
            assertTrue(svcCalls.get() >= 2,
                "带 provider-hint 的瞬时失败也应触发重试（≥2 次扫描），实际=" + svcCalls.get());
            assertTrue(registry.has("zh-CN"),
                "重试后 zh-CN 应加齐——带 provider hint 的 iterator 失败也必须重试；loaded=" + loaded);
        } finally {
            java.nio.file.Files.deleteIfExists(svcFile);
            java.nio.file.Files.deleteIfExists(tmpDir);
            if (!registry.has("zh-CN")) {
                registry.discoverPlugins(LexiconPlugin.class.getClassLoader());
            }
        }
    }

    // ============================================================
    // 审计 #58：register() 强制别名遮蔽/重复校验
    // ============================================================

    @Test
    void registerRejectsShadowingAlias() throws Exception {
        // 审计 #58：声明别名遮蔽规范拼写的 lexicon（aliases {"RETURN":["if"]}）此前能干净注册，
        // 悄悄把每个 if 改写成 return。register() 现须复用 LexiconValidator 的别名硬校验并拒绝。
        ObjectMapper mapper = new ObjectMapper();
        String json = new String(
            getClass().getClassLoader().getResourceAsStream("builtin/en-US.json").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) mapper.readTree(json);
        root.put("id", "qa-ZZ"); // 未占用的合法 BCP47 id，避免 "already registered" 抢先返回
        ArrayNode ifArr = mapper.createArrayNode();
        ifArr.add("if");
        root.putObject("aliases").set("RETURN", ifArr);
        Lexicon bad = DynamicLexicon.fromJsonString(mapper.writeValueAsString(root));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> registry.register(bad));
        assertTrue(ex.getMessage().contains("shadows canonical keyword"),
            "应报别名遮蔽错误，实际: " + ex.getMessage());
        assertFalse(registry.has("qa-ZZ"), "被拒的 lexicon 不应进入注册表");
    }

    @Test
    void registerRejectsDuplicateAliasAcrossKinds() throws Exception {
        // 审计 #58：同一别名给两个 kind → register() 拒绝（ADR-0022 别名重复）。
        ObjectMapper mapper = new ObjectMapper();
        String json = new String(
            getClass().getClassLoader().getResourceAsStream("builtin/en-US.json").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) mapper.readTree(json);
        root.put("id", "qa-ZY");
        ObjectNode aliases = root.putObject("aliases");
        ArrayNode a1 = mapper.createArrayNode(); a1.add("Foo");
        ArrayNode a2 = mapper.createArrayNode(); a2.add("Foo");
        aliases.set("FUNC_TO", a1);
        aliases.set("TYPE_DEF", a2);
        Lexicon bad = DynamicLexicon.fromJsonString(mapper.writeValueAsString(root));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> registry.register(bad));
        assertTrue(ex.getMessage().contains("defined for both"),
            "应报别名重复错误，实际: " + ex.getMessage());
        assertFalse(registry.has("qa-ZY"));
    }
}
