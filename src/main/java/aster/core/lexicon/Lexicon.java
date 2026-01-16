package aster.core.lexicon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 词法表接口 - 定义一种自然语言的 CNL 词法。
 * <p>
 * 每个 Lexicon 实现必须提供完整的关键词映射、标点符号配置和规范化规则。
 * <p>
 * <b>核心原则</b>：
 * <ul>
 *   <li>语言无关：编译器核心不包含任何自然语言关键词</li>
 *   <li>平等皮肤：英语、中文、日语等所有语言都是平等的"皮肤"</li>
 *   <li>类型安全：所有映射都通过 SemanticTokenKind 进行类型检查</li>
 * </ul>
 */
public interface Lexicon {

    /**
     * 词法表唯一标识符 (e.g., "en-US", "zh-CN", "ja-JP")
     */
    String getId();

    /**
     * 人类可读的语言名称
     */
    String getName();

    /**
     * 文字方向
     */
    enum Direction {
        /** 左到右 */
        LTR,
        /** 右到左 */
        RTL
    }

    /**
     * 获取文字方向
     */
    Direction getDirection();

    /**
     * 获取关键词映射：SemanticTokenKind -> 该语言的关键词字符串
     */
    Map<SemanticTokenKind, String> getKeywords();

    /**
     * 获取标点符号配置
     */
    PunctuationConfig getPunctuation();

    /**
     * 获取规范化配置
     */
    CanonicalizationConfig getCanonicalization();

    /**
     * 获取错误消息模板
     */
    ErrorMessages getMessages();

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 构建关键词索引（关键词字符串 -> SemanticTokenKind）
     *
     * @return 关键词索引 Map
     */
    default Map<String, SemanticTokenKind> buildKeywordIndex() {
        Map<String, SemanticTokenKind> index = new HashMap<>();
        for (Map.Entry<SemanticTokenKind, String> entry : getKeywords().entrySet()) {
            // 使用 Locale.ROOT 避免土耳其语等特殊区域设置导致的大小写转换问题
            index.put(entry.getValue().toLowerCase(Locale.ROOT), entry.getKey());
        }
        return index;
    }

    /**
     * 获取多词关键词列表（按长度降序，用于最长匹配）
     *
     * @return 多词关键词数组
     */
    default List<String> getMultiWordKeywords() {
        List<String> multiWord = new ArrayList<>();
        PunctuationConfig punct = getPunctuation();

        for (String keyword : getKeywords().values()) {
            boolean isMultiWord = keyword.contains(" ");
            boolean hasMarker = punct.hasMarkers() && keyword.contains(punct.markerOpen());

            if (isMultiWord || hasMarker) {
                multiWord.add(keyword);
            }
        }

        // 按长度降序排列
        multiWord.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return multiWord;
    }

    /**
     * 从关键词查找 SemanticTokenKind
     *
     * @param keyword 关键词字符串
     * @return 对应的 SemanticTokenKind，如果不是关键词则返回 empty
     */
    default Optional<SemanticTokenKind> findSemanticTokenKind(String keyword) {
        // 使用 Locale.ROOT 避免土耳其语等特殊区域设置导致的大小写转换问题
        String lower = keyword.toLowerCase(Locale.ROOT);
        for (Map.Entry<SemanticTokenKind, String> entry : getKeywords().entrySet()) {
            if (entry.getValue().toLowerCase(Locale.ROOT).equals(lower)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * 检查字符串是否为该词法表的关键词
     *
     * @param word 要检查的字符串
     * @return 如果是关键词返回 true
     */
    default boolean isKeyword(String word) {
        // 使用 Locale.ROOT 避免土耳其语等特殊区域设置导致的大小写转换问题
        String lower = word.toLowerCase(Locale.ROOT);
        return getKeywords().values().stream()
            .anyMatch(kw -> kw.toLowerCase(Locale.ROOT).equals(lower));
    }

    /**
     * 获取指定语义类型的关键词
     *
     * @param kind 语义类型
     * @return 对应的关键词字符串，如果不存在则返回 empty
     */
    default Optional<String> getKeyword(SemanticTokenKind kind) {
        return Optional.ofNullable(getKeywords().get(kind));
    }
}
