package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.lexicon.CanonicalizationConfig;

import java.util.regex.Pattern;

/**
 * {@code Set X to Y} → {@code Let X be Y} 重写变换器。
 */
public final class SetToTransformer implements SyntaxTransformer {

    public static final SetToTransformer INSTANCE = new SetToTransformer();

    /**
     * ★行首缩进用 {@code [ \t]*} 而非 {@code \s*}，这是 <b>ReDoS 修复</b>。
     *
     * <p>{@code \s} 包含 {@code \n}，在 {@code MULTILINE} 下 {@code ^} 会在
     * <b>每一个</b>行首匹配，而 {@code (\s*)} 又能一路吃穿后续所有空行——于是
     * 每个行首都要向后扫描整份剩余文本，呈二次增长。实测（n 个空行）：
     * 10000→86ms、20000→339ms、40000→1361ms，每翻倍 ×4。
     *
     * <p>攻击载荷是一份**全是空行**的源文件，Canonicalizer 是每份源码的必经之路。
     *
     * <p>★改用「水平空白」不改变语义，反而更准确：这个捕获组的用途是**保留该行
     * 的缩进**（替换串里的 {@code $1}），缩进按定义就只由空格与制表符构成，
     * 跨行吃掉换行本来就是错的。实测 16 组人工样本（其中 15 组确有替换）
     * 逐字节一致，耗时 1361ms → 1ms。
     */
    private static final Pattern SET_TO = Pattern.compile(
            "^([ \\t]*)Set\\s+([\\p{L}][\\p{L}0-9_]*)\\s+to\\s+",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    private SetToTransformer() {}

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        return segmenter.replaceOutsideStrings(source, SET_TO, "$1Let $2 be ");
    }
}
