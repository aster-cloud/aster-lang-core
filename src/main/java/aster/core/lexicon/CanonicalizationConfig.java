package aster.core.lexicon;

import aster.core.canonicalizer.SyntaxTransformer;

import java.util.List;
import java.util.Set;

/**
 * 规范化配置 - 控制源代码预处理行为。
 *
 * @param fullWidthToHalf             是否将全角字符转换为半角（数字、运算符）
 * @param whitespaceMode              空格处理模式
 * @param removeArticles              是否移除冠词 (英文: a, an, the)
 * @param articles                    冠词列表（如果 removeArticles 为 true）
 * @param customRules                 自定义规范化规则
 * @param allowedDuplicates           允许共享同一关键字的语义令牌组
 * @param compoundPatterns            复合关键词模式（如 "若...为"、"令...为"）
 * @param preTranslationTransformers  关键词翻译前执行的语法变换器链
 * @param postTranslationTransformers 关键词翻译后执行的语法变换器链
 */
public record CanonicalizationConfig(
    boolean fullWidthToHalf,
    WhitespaceMode whitespaceMode,
    boolean removeArticles,
    List<String> articles,
    List<CanonicalizationRule> customRules,
    List<Set<SemanticTokenKind>> allowedDuplicates,
    List<CompoundPattern> compoundPatterns,
    List<SyntaxTransformer> preTranslationTransformers,
    List<SyntaxTransformer> postTranslationTransformers
) {
    /**
     * 空格处理模式
     */
    public enum WhitespaceMode {
        /** 英文模式：空格敏感 */
        ENGLISH,
        /** 中文模式：空格不敏感 */
        CHINESE,
        /** 混合模式 */
        MIXED
    }

    /**
     * 默认规范化配置（纯数据默认值，不引用任何变换器）。
     * <p>
     * 仅作为 {@link DynamicLexicon} 在 JSON 缺少 canonicalization 节点时的 fallback。
     * 实际的语言特定配置由各语言包的 JSON 文件定义。
     */
    public static CanonicalizationConfig defaults() {
        return new CanonicalizationConfig(
            false,                    // fullWidthToHalf
            WhitespaceMode.ENGLISH,   // whitespaceMode
            false,                    // removeArticles
            List.of(),                // articles
            List.of(),                // customRules
            List.of(),                // allowedDuplicates
            List.of(),                // compoundPatterns
            List.of(),                // preTranslationTransformers
            List.of()                 // postTranslationTransformers
        );
    }

    /**
     * 自定义规范化规则
     *
     * @param name        规则名称（用于调试和日志）
     * @param pattern     匹配模式（正则表达式字符串）
     * @param replacement 替换内容
     */
    public record CanonicalizationRule(
        String name,
        String pattern,
        String replacement
    ) {}
}
