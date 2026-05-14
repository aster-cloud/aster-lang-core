package aster.core.lexicon;

import aster.core.canonicalizer.SyntaxTransformer;

import java.util.Map;
import java.util.Set;
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

    /**
     * R6-M1 + R7-3：返回该语言包**声明**会提供的 lexicon ID 集合。
     *
     * <p>用于 {@code LexiconRegistry.previewPluginIds(loader)} —— 在不创建
     * Lexicon 实例的前提下预先知道 jar 会提供什么。这让 hot-plug 的"事务式替换"
     * 真正零副作用。
     *
     * <p>R7-3 关键决定：**默认返回空集合**（不再 fallback 到 createLexicon），
     * 让"preview 零副作用"成为真契约。未 override 此方法的 plugin 在 preview
     * 阶段表现为"没有声明任何 ID" —— hot-plug 替换路径会认为它是 ghost-jar，
     * 拒绝加载。这强制 plugin 作者显式声明自己提供的 ID。
     *
     * <p>如果你的 plugin 想被 hot-plug 路径接受，请 override 此方法返回静态常量集合，
     * 例如：
     * <pre>{@code
     *   @Override
     *   public Set<String> providedLexiconIds() {
     *       return Set.of("zh-CN");
     *   }
     * }</pre>
     *
     * <p>(原始 SPI 加载路径不依赖此方法，仍调 createLexicon 注册 lexicon —— 兼容
     * 旧 plugin。仅 hot-plug 替换路径需要此声明。)
     *
     * @return plugin 承诺提供的 lexicon ID 集合
     */
    default Set<String> providedLexiconIds() {
        return Set.of();
    }

    /**
     * 该 lexicon 实现的 SPI ABI 版本（默认 {@code "1.0"}）。
     *
     * <p>核心运行时启动加载时会用 {@link LexiconAbiVersion#isCompatible(String)} 校验。
     * 不兼容的 lexicon 会被 skip + 告警，但不影响其他 lexicon 加载。
     *
     * <p>承诺：ABI v1 至少保证 18 个月不变更（详见 {@link LexiconAbiVersion#V1}）。
     *
     * @return SPI ABI 版本字符串，例如 {@code "1.0"}
     */
    default String getAbiVersion() {
        return LexiconAbiVersion.V1.version;
    }
}
