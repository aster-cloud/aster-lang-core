package aster.core.identifier;

import java.util.List;

/**
 * 领域词汇表 SPI 接口。
 * <p>
 * 第三方可通过实现此接口并在 {@code META-INF/services/aster.core.identifier.VocabularyPlugin}
 * 中注册，将领域词汇表打包为 jar 分发。
 * <p>
 * 语言包可同时实现 {@link aster.core.lexicon.LexiconPlugin} 和 {@code VocabularyPlugin}，
 * 在同一 JAR 中提供词法表和领域词汇表。
 */
public interface VocabularyPlugin {

    /**
     * 创建该插件的主要领域词汇表。
     *
     * @return 完整配置的领域词汇表
     */
    DomainVocabulary createVocabulary();

    /**
     * 返回该插件提供的额外领域词汇表。
     * <p>
     * 用于在同一插件中提供多个领域的词汇表。
     *
     * @return 额外的领域词汇表列表，默认为空
     */
    default List<DomainVocabulary> getVocabularies() {
        return List.of();
    }
}
