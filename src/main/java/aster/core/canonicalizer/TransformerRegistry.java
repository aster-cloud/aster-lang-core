package aster.core.canonicalizer;

import aster.core.canonicalizer.transformers.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 可扩展的变换器注册表。
 * <p>
 * 内置英文变换器在类加载时注册，语言包通过 {@link #register} / {@link #registerAll}
 * 在 SPI 发现阶段追加自己的变换器。
 */
public final class TransformerRegistry {

    /**
     * R6-C1：单一 map 存 supplier + owner，避免双 map 非原子更新。
     * 用 ConcurrentHashMap.compute 实现真正原子的 register / unregister。
     *
     * @param supplier 变换器工厂
     * @param owner    注册它的 classloader；内置 transformer 传 null（永久，禁止替换）
     */
    private record Entry(Supplier<SyntaxTransformer> supplier, ClassLoader owner) {}

    private static final ConcurrentHashMap<String, Entry> REGISTRY = new ConcurrentHashMap<>();

    static {
        // 英文基础变换器（保留在 core 中，属于 IR 规范化的基础能力）
        // owner=null → 永久内置，任何 owner-aware 注册都不能覆盖
        REGISTRY.put("english-possessive", new Entry(() -> EnglishPossessiveTransformer.INSTANCE, null));
        REGISTRY.put("result-is", new Entry(() -> ResultIsTransformer.INSTANCE, null));
        REGISTRY.put("set-to", new Entry(() -> SetToTransformer.INSTANCE, null));
    }

    private TransformerRegistry() {}

    /**
     * 注册一个变换器（无 owner —— 永久 / 内置语义）。
     *
     * @param name     变换器名称
     * @param supplier 变换器工厂
     * @throws IllegalArgumentException 如果名称已被注册
     */
    public static void register(String name, Supplier<SyntaxTransformer> supplier) {
        Entry prev = REGISTRY.putIfAbsent(name, new Entry(supplier, null));
        if (prev != null) {
            throw new IllegalArgumentException(
                    "Transformer '" + name + "' already registered. Available: " + REGISTRY.keySet()
            );
        }
    }

    /**
     * 批量注册变换器（无 owner）。
     *
     * @param transformers 名称到工厂的映射
     */
    public static void registerAll(Map<String, Supplier<SyntaxTransformer>> transformers) {
        for (var entry : transformers.entrySet()) {
            register(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 幂等批量注册：已注册的 name 静默跳过（保留旧 owner）。
     *
     * <p>用于 hot-plug 路径——同一 plugin 被多次扫描（例如 jar replace 后重新发现），
     * 第二次重复注册不应抛错。
     */
    public static void registerAllIdempotent(Map<String, Supplier<SyntaxTransformer>> transformers) {
        for (var entry : transformers.entrySet()) {
            REGISTRY.putIfAbsent(entry.getKey(), new Entry(entry.getValue(), null));
        }
    }

    /**
     * R5-Backend-3 + R6-C1：带 owner 的原子幂等批量注册。
     *
     * <p>用 ConcurrentHashMap.compute 让 "check existing owner + decide action" 单步原子完成：
     * <ul>
     *   <li>name 未注册 → 注册并记录 owner</li>
     *   <li>name 已注册且 owner 相同 → 升级（同 loader 再次注册同名）</li>
     *   <li>name 已注册且 owner 不同 → first-wins，跳过</li>
     *   <li>name 已注册但 owner=null（内置）→ 跳过（不动内置）</li>
     * </ul>
     */
    public static void registerAllWithOwner(
            Map<String, Supplier<SyntaxTransformer>> transformers,
            ClassLoader owner) {
        if (owner == null) {
            registerAllIdempotent(transformers);
            return;
        }
        for (var entry : transformers.entrySet()) {
            String name = entry.getKey();
            Supplier<SyntaxTransformer> sup = entry.getValue();
            REGISTRY.compute(name, (k, existing) -> {
                if (existing == null) {
                    // 未注册 → 注册
                    return new Entry(sup, owner);
                }
                if (existing.owner == null) {
                    // 内置 → 不动
                    return existing;
                }
                if (existing.owner == owner) {
                    // 同 owner → 升级
                    return new Entry(sup, owner);
                }
                // 不同 owner → first-wins
                return existing;
            });
        }
    }

    /**
     * R5-Backend-3 + R6-C1：批量移除由指定 loader 注册的所有 transformer。
     *
     * <p>原子化：用 compute 让 "check owner + remove" 在单步完成，
     * 避免并发 register/unregister 之间的不一致窗口。
     * 不影响内置 transformer（owner=null）。
     *
     * @return 实际移除的 transformer name 集合
     */
    public static Set<String> unregisterByOwner(ClassLoader owner) {
        if (owner == null) return Set.of();
        Set<String> removed = new HashSet<>();
        for (String name : REGISTRY.keySet()) {
            REGISTRY.computeIfPresent(name, (k, existing) -> {
                if (existing.owner == owner) {
                    removed.add(k);
                    return null;  // 返回 null = 删除
                }
                return existing;
            });
        }
        return removed;
    }

    /**
     * 按名称获取变换器。
     *
     * @param name 变换器名称
     * @return 变换器实例
     * @throws IllegalArgumentException 如果名称不存在
     */
    public static SyntaxTransformer get(String name) {
        Entry e = REGISTRY.get(name);
        if (e == null) {
            throw new IllegalArgumentException(
                    "Unknown transformer: '" + name + "'. Available: " + REGISTRY.keySet()
            );
        }
        return e.supplier.get();
    }

    /**
     * 检查是否存在指定名称的变换器。
     */
    public static boolean contains(String name) {
        return REGISTRY.containsKey(name);
    }

    /**
     * 获取所有可用的变换器名称。
     */
    public static Set<String> availableNames() {
        return REGISTRY.keySet();
    }

    /**
     * 通过实例反查注册键。
     *
     * @param transformer 变换器实例
     * @return 注册键名
     */
    public static String getKey(SyntaxTransformer transformer) {
        for (var entry : REGISTRY.entrySet()) {
            if (entry.getValue().supplier.get() == transformer) {
                return entry.getKey();
            }
        }
        return transformer.getClass().getSimpleName();
    }

    /**
     * 清除所有注册的变换器并重新注册内置变换器（仅用于测试）。
     */
    public static void reset() {
        REGISTRY.clear();
        REGISTRY.put("english-possessive", new Entry(() -> EnglishPossessiveTransformer.INSTANCE, null));
        REGISTRY.put("result-is", new Entry(() -> ResultIsTransformer.INSTANCE, null));
        REGISTRY.put("set-to", new Entry(() -> SetToTransformer.INSTANCE, null));
    }
}
