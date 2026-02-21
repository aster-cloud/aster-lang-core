package aster.core.lexicon;

import aster.core.canonicalizer.SyntaxTransformer;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 语言包 SPI 接口。
 * <p>
 * 第三方可通过实现此接口并在 {@code META-INF/services/aster.core.lexicon.LexiconPlugin}
 * 中注册，将语言包打包为 jar 分发。
 * <p>
 * 语言包可同时提供词法表和语法变换器，SPI 发现时会自动注册到对应的注册表。
 */
public interface LexiconPlugin {

    /**
     * 创建该语言包的 Lexicon 实例。
     *
     * @return 完整配置的 Lexicon
     */
    Lexicon createLexicon();

    /**
     * 返回该语言包提供的语法变换器。
     * <p>
     * 变换器将在 SPI 发现阶段自动注册到 {@link aster.core.canonicalizer.TransformerRegistry}。
     *
     * @return 变换器名称到工厂的映射，默认为空
     */
    default Map<String, Supplier<SyntaxTransformer>> getTransformers() {
        return Map.of();
    }
}
