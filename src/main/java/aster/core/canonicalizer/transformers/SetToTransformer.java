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

    private static final Pattern SET_TO = Pattern.compile(
            "^(\\s*)Set\\s+([\\p{L}][\\p{L}0-9_]*)\\s+to\\s+",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    private SetToTransformer() {}

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        return segmenter.replaceOutsideStrings(source, SET_TO, "$1Let $2 be ");
    }
}
