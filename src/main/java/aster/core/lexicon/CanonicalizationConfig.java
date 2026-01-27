package aster.core.lexicon;

import java.util.List;
import java.util.Set;

/**
 * 规范化配置 - 控制源代码预处理行为。
 *
 * @param fullWidthToHalf    是否将全角字符转换为半角（数字、运算符）
 * @param whitespaceMode     空格处理模式
 * @param removeArticles     是否移除冠词 (英文: a, an, the)
 * @param articles           冠词列表（如果 removeArticles 为 true）
 * @param customRules        自定义规范化规则
 * @param allowedDuplicates  允许共享同一关键字的语义令牌组
 * @param compoundPatterns   复合关键词模式（如 "若...为"、"令...为"）
 */
public record CanonicalizationConfig(
    boolean fullWidthToHalf,
    WhitespaceMode whitespaceMode,
    boolean removeArticles,
    List<String> articles,
    List<CanonicalizationRule> customRules,
    List<Set<SemanticTokenKind>> allowedDuplicates,
    List<CompoundPattern> compoundPatterns
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
            List.of(Set.of(SemanticTokenKind.FUNC_TO, SemanticTokenKind.TO_WORD)), // allowedDuplicates
            List.of()                 // compoundPatterns
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
     */
    public static CanonicalizationConfig chinese() {
        return new CanonicalizationConfig(
            true,                     // fullWidthToHalf
            WhitespaceMode.CHINESE,   // whitespaceMode
            false,                    // removeArticles
            List.of(),                // articles
            List.of(),                // customRules
            List.of(Set.of(SemanticTokenKind.WHEN, SemanticTokenKind.BE)), // allowedDuplicates: "为" 用于 match/when 和 let/be
            List.of(                  // compoundPatterns
                CompoundPattern.matchWhen(),
                CompoundPattern.letBe()
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
