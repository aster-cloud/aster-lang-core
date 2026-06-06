package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.lexicon.CanonicalizationConfig;

import java.util.regex.Pattern;

/**
 * 可选 {@code is} 连接词吸收变换器。
 *
 * <p>把比较短语前面那个可选的、纯自然语言连接作用的 {@code is} 去掉，让
 * {@code score is at least 700} 与 {@code score at least 700} 走同一条已实现的
 * 比较路径（后者由关键词翻译阶段把 {@code at least} 翻成 {@code >=}）。
 *
 * <p>支持的比较词（en-US 规范化后）：{@code at least}、{@code at most}、
 * {@code greater than}、{@code less than}、{@code more than}、{@code under}、
 * {@code over}。已实现的 {@code is equal to} / {@code is not equal to} 由
 * AstBuilder.normalizeOperator（Java）/ parseComparison（TS）单独处理，不在此动。
 *
 * <p><b>为何零歧义</b>：本变换器只在 {@code is} 后<b>紧跟一个比较词</b>时触发。
 * result-binding 的 {@code is}（{@code The result is X} → {@code Return X}，由
 * {@link ResultIsTransformer} 处理）后面跟的是表达式而非比较词，文本模式互不重叠。
 * bare {@code is}（{@code x is 5}）是另一项语言设计决策，<b>本变换器刻意不处理</b>，
 * 以避免 {@code is} 进一步过载。
 *
 * <p>必须在关键词翻译（{@code at least} → {@code >=}）之前执行，故归入
 * preTranslationTransformers 链。字符串字面量内的内容由 segmenter 保护。
 */
public final class IsComparatorTransformer implements SyntaxTransformer {

    public static final IsComparatorTransformer INSTANCE = new IsComparatorTransformer();

    /**
     * 匹配 {@code is}（独立单词，大小写不敏感）后跟<b>水平</b>空白，再跟受支持的比较词。
     *
     * <p>用 {@code [ \t]+}（而非 {@code \s+}）有两个原因：
     * <ul>
     *   <li><b>容忍多空格</b>：本 transformer 在 normalizeWhitespace 之前执行，
     *       {@code is at  least} 的多空格此刻尚未折叠；若写死单空格会漏匹配，导致
     *       Java 留下 {@code is} 而 TS（词法丢空格）不留，造成双引擎分歧。</li>
     *   <li><b>不跨行</b>：{@code \s} 含 {@code \n}，会把 {@code score is\nat least}
     *       误拼成 {@code score at least}，而 TS 的 {@code isKeywordSeq} 不跨 NEWLINE
     *       token——这是 {@code \s+} 引入的新分歧。限定水平空白 {@code [ \t]+} 与
     *       TS「比较词必须同段连续」的语义对齐。比较短语本就是单行表达式片段。</li>
     * </ul>
     * {@code \b} 词边界确保不误吸 {@code isOver}、{@code thisover} 之类标识符片段。
     * 仅 ASCII 英文关键词，不加 UNICODE_CASE（避免与 TS 大小写折叠行为产生差异）。
     */
    private static final Pattern IS_BEFORE_COMPARATOR = Pattern.compile(
            "\\bis[ \\t]+(?=(?:at[ \\t]+least|at[ \\t]+most|greater[ \\t]+than"
                    + "|less[ \\t]+than|more[ \\t]+than|under|over)\\b)",
            Pattern.CASE_INSENSITIVE
    );

    private IsComparatorTransformer() {}

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        // 把 "is " 整体删掉（保留后面的比较词），仅在字符串字面量之外生效。
        return segmenter.replaceOutsideStrings(source, IS_BEFORE_COMPARATOR, "");
    }
}
