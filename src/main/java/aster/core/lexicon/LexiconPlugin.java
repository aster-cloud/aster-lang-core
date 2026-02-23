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

    /**
     * 返回该语言包提供的 overlay 资源路径。
     * <p>
     * Overlay 资源是 JSON 文件，包含语言特定的类型推断规则、输入生成规则、
     * 诊断消息翻译和 LSP UI 文本等扩展数据。这些资源在 lexicon 导出时嵌入到
     * 生成的 JSON 中，供 TypeScript 端消费。
     * <p>
     * 支持的 overlay 类型：
     * <ul>
     *   <li>{@code typeInferenceRules} — 类型推断命名规则</li>
     *   <li>{@code inputGenerationRules} — 输入值生成规则</li>
     *   <li>{@code diagnosticMessages} — 诊断消息翻译</li>
     *   <li>{@code diagnosticHelp} — 诊断帮助文本翻译</li>
     *   <li>{@code lspUiTexts} — LSP 界面文本</li>
     * </ul>
     *
     * @return overlay 类型名称到 classpath 资源路径的映射，默认为空
     */
    default Map<String, String> getOverlayResources() {
        return Map.of();
    }
}
