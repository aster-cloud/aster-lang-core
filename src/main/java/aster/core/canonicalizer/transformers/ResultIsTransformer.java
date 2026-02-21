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

    private static final Pattern RESULT_IS = Pattern.compile(
            "^(\\s*)The result is\\s+",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    private ResultIsTransformer() {}

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        return segmenter.replaceOutsideStrings(source, RESULT_IS, "$1Return ");
    }
}
