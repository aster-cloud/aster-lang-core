package aster.core.canonicalizer;

import aster.core.lexicon.CanonicalizationConfig;

/**
 * 可插拔的语法变换器接口。
 * <p>
 * 每个变换器负责一类语法规范化操作（如标点翻译、所有格转换、语序重排等）。
 * 变换器在 Canonicalizer 的关键词翻译步骤之前按顺序执行。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li>字符串安全：变换器需自行保护字符串字面量内的内容不被修改</li>
 *   <li>幂等性：多次执行同一变换器应产生相同结果</li>
 *   <li>顺序敏感：变换器按配置顺序执行，后续变换器接收前序变换器的输出</li>
 * </ul>
 */
@FunctionalInterface
public interface SyntaxTransformer {
    /**
     * 对源码文本执行变换。
     *
     * @param source       当前源码文本（可能已被前序变换器修改）
     * @param config       规范化配置（包含标点、关键词等信息）
     * @param segmenter    字符串分段器（用于保护字符串字面量内容）
     * @return 变换后的源码文本
     */
    String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter);
}
