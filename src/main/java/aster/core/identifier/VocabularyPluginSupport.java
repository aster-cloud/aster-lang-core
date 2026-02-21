package aster.core.identifier;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@link VocabularyPlugin} 实现的辅助工具类。
 * <p>
 * 提供从类路径加载 JSON 词汇表的通用方法，
 * 消除各语言包插件中重复的 {@code loadVocabulary} 方法。
 */
public final class VocabularyPluginSupport {

    private VocabularyPluginSupport() {}

    /**
     * 从类路径加载 JSON 格式的领域词汇表。
     *
     * @param clazz 用于定位资源的类（通常传 {@code getClass()}）
     * @param path  类路径中的资源路径（如 {@code "vocabularies/insurance-auto-zh-CN.json"}）
     * @return 解析后的领域词汇表
     * @throws IllegalStateException 如果资源不存在
     * @throws UncheckedIOException  如果读取失败
     */
    public static DomainVocabulary loadVocabulary(Class<?> clazz, String path) {
        ClassLoader cl = clazz.getClassLoader();
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
        }
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        try (var is = cl.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found: " + path);
            }
            return VocabularyLoader.loadFromStream(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load vocabulary: " + path, e);
        }
    }
}
