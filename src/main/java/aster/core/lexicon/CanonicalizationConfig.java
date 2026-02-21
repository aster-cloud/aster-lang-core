package aster.core.lexicon;

import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.canonicalizer.TransformerRegistry;

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
     * 英文标准规范化配置
     */
    public static CanonicalizationConfig english() {
        return new CanonicalizationConfig(
            false,                    // fullWidthToHalf
            WhitespaceMode.ENGLISH,   // whitespaceMode
            true,                     // removeArticles
            List.of("a", "an", "the"), // articles
            List.of(),                // customRules
            List.of(
                Set.of(SemanticTokenKind.FUNC_TO, SemanticTokenKind.TO_WORD),
                Set.of(SemanticTokenKind.UNDER, SemanticTokenKind.LESS_THAN),  // 均映射为 "<" 符号
                Set.of(SemanticTokenKind.OVER, SemanticTokenKind.GREATER_THAN, SemanticTokenKind.MORE_THAN)  // 均映射为 ">" 符号
            ),
            List.of(),                // compoundPatterns
            List.of(                  // preTranslationTransformers
                TransformerRegistry.get("english-possessive")
            ),
            List.of(                  // postTranslationTransformers
                TransformerRegistry.get("result-is"),
                TransformerRegistry.get("set-to")
            )
        );
    }

    /**
     * 中文标准规范化配置
     * <p>
     * 包含复合关键词模式：
     * <ul>
     *   <li>match-when: 若...为（模式匹配）</li>
     *   <li>let-be: 令...为（变量声明）</li>
     * </ul>
     * <p>
     * 中文变换器通过 {@link TransformerRegistry} 按名称解析，
     * 由 aster-lang-zh 语言包在 SPI 发现阶段注册。
     */
    public static CanonicalizationConfig chinese() {
        return new CanonicalizationConfig(
            true,                     // fullWidthToHalf
            WhitespaceMode.CHINESE,   // whitespaceMode
            false,                    // removeArticles
            List.of(),                // articles
            List.of(),                // customRules
            List.of(
                Set.of(SemanticTokenKind.WHEN, SemanticTokenKind.BE),        // "为" 用于 match/when 和 let/be
                Set.of(SemanticTokenKind.TYPE_WITH, SemanticTokenKind.TYPE_HAS), // "包含" 用于旧/新类型字段语法
                Set.of(SemanticTokenKind.UNDER, SemanticTokenKind.LESS_THAN),   // 均映射为 "<" 符号
                Set.of(SemanticTokenKind.OVER, SemanticTokenKind.GREATER_THAN, SemanticTokenKind.MORE_THAN)  // 均映射为 ">" 符号
            ),
            List.of(                  // compoundPatterns
                CompoundPattern.matchWhen(),
                CompoundPattern.letBe()
            ),
            List.of(                  // preTranslationTransformers
                TransformerRegistry.get("english-possessive"),
                TransformerRegistry.get("chinese-punctuation"),
                TransformerRegistry.get("chinese-possessive"),
                TransformerRegistry.get("chinese-operator"),
                TransformerRegistry.get("chinese-function-syntax")
            ),
            List.of(                  // postTranslationTransformers
                TransformerRegistry.get("result-is"),
                TransformerRegistry.get("set-to"),
                TransformerRegistry.get("chinese-set-to"),
                TransformerRegistry.get("chinese-result-is")
            )
        );
    }

    /**
     * 德语标准规范化配置
     * <p>
     * 支持 ASCII 替代 umlaut：
     * <ul>
     *   <li>oe → ö (groesser → größer)</li>
     *   <li>ue → ü (zurueck → zurück)</li>
     *   <li>ae → ä (Minderjaehriger → Minderjähriger)</li>
     * </ul>
     */
    public static CanonicalizationConfig german() {
        return new CanonicalizationConfig(
            false,                    // fullWidthToHalf
            WhitespaceMode.ENGLISH,   // whitespaceMode (德语以空格分词)
            true,                     // removeArticles
            List.of("der", "die", "das", "ein", "eine", "einen", "einem", "einer", "eines"), // articles
            List.of(                  // customRules: ASCII umlaut normalization
                // Step 1: Handle oe -> ö
                new CanonicalizationRule("oe-to-ö", "oe", "ö"),
                // Step 2: Handle ue -> ü
                new CanonicalizationRule("ue-to-ü", "ue", "ü"),
                // Step 3: Handle ae -> ä
                new CanonicalizationRule("ae-to-ä", "ae", "ä"),
                // Step 4: Handle ss -> ß in specific keyword contexts
                new CanonicalizationRule("ss-to-ß-grösser", "\\bgrösser\\b", "größer"),
                new CanonicalizationRule("ss-to-ß-gross", "\\bgross\\b", "groß")
            ),
            List.of(                  // allowedDuplicates
                Set.of(SemanticTokenKind.UNDER, SemanticTokenKind.LESS_THAN),  // unter / kleiner als → "<"
                Set.of(SemanticTokenKind.OVER, SemanticTokenKind.GREATER_THAN, SemanticTokenKind.MORE_THAN)  // ueber / groesser als / mehr als → ">"
            ),
            List.of(),                // compoundPatterns
            List.of(                  // preTranslationTransformers
                TransformerRegistry.get("english-possessive")
            ),
            List.of(                  // postTranslationTransformers
                TransformerRegistry.get("result-is"),
                TransformerRegistry.get("set-to")
            )
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
