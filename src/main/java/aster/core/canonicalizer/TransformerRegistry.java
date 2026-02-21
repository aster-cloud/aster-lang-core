package aster.core.canonicalizer;

import aster.core.canonicalizer.transformers.*;

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

    private static final ConcurrentHashMap<String, Supplier<SyntaxTransformer>> REGISTRY =
            new ConcurrentHashMap<>();

    static {
        // 英文基础变换器（保留在 core 中，属于 IR 规范化的基础能力）
        REGISTRY.put("english-possessive", () -> EnglishPossessiveTransformer.INSTANCE);
        REGISTRY.put("result-is", () -> ResultIsTransformer.INSTANCE);
        REGISTRY.put("set-to", () -> SetToTransformer.INSTANCE);
    }

    private TransformerRegistry() {}

    /**
     * 注册一个变换器。
     *
     * @param name     变换器名称
     * @param supplier 变换器工厂
     * @throws IllegalArgumentException 如果名称已被注册
     */
    public static void register(String name, Supplier<SyntaxTransformer> supplier) {
        if (REGISTRY.putIfAbsent(name, supplier) != null) {
            throw new IllegalArgumentException(
                    "Transformer '" + name + "' already registered. Available: " + REGISTRY.keySet()
            );
        }
    }

    /**
     * 批量注册变换器。
     *
     * @param transformers 名称到工厂的映射
     */
    public static void registerAll(Map<String, Supplier<SyntaxTransformer>> transformers) {
        for (var entry : transformers.entrySet()) {
            register(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 按名称获取变换器。
     *
     * @param name 变换器名称
     * @return 变换器实例
     * @throws IllegalArgumentException 如果名称不存在
     */
    public static SyntaxTransformer get(String name) {
        var supplier = REGISTRY.get(name);
        if (supplier == null) {
            throw new IllegalArgumentException(
                    "Unknown transformer: '" + name + "'. Available: " + REGISTRY.keySet()
            );
        }
        return supplier.get();
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
     * 清除所有注册的变换器并重新注册内置变换器（仅用于测试）。
     */
    public static void reset() {
        REGISTRY.clear();
        REGISTRY.put("english-possessive", () -> EnglishPossessiveTransformer.INSTANCE);
        REGISTRY.put("result-is", () -> ResultIsTransformer.INSTANCE);
        REGISTRY.put("set-to", () -> SetToTransformer.INSTANCE);
    }
}
