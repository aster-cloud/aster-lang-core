package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.lexicon.CanonicalizationConfig;

import java.util.regex.Pattern;

/**
 * {@code The result is X} → {@code Return X} 重写变换器。
 * <p>
 * 必须在冠词移除之前执行，否则 "The" 会被先移除。
 */
public final class ResultIsTransformer implements SyntaxTransformer {

    public static final ResultIsTransformer INSTANCE = new ResultIsTransformer();

    /** 行首形式: "  The result is X." → "  Return X." */
    private static final Pattern RESULT_IS_LINE_START = Pattern.compile(
            "^(\\s*)The result is\\s+",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    /** 内联形式（Match 分支等）: "When X, the result is Y." → "When X, Return Y." */
    private static final Pattern RESULT_IS_INLINE = Pattern.compile(
            "(,\\s*)the result is\\s+",
            Pattern.CASE_INSENSITIVE
    );

    private ResultIsTransformer() {}

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        String s = segmenter.replaceOutsideStrings(source, RESULT_IS_LINE_START, "$1Return ");
        s = segmenter.replaceOutsideStrings(s, RESULT_IS_INLINE, "$1Return ");
        return s;
    }
}
