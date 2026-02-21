package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.lexicon.CanonicalizationConfig;

import java.util.regex.Pattern;

/**
 * 英语属格 {@code 's} → 成员访问符 {@code .} 的变换器。
 * <p>
 * 示例：{@code driver's age} → {@code driver.age}
 */
public final class EnglishPossessiveTransformer implements SyntaxTransformer {

    public static final EnglishPossessiveTransformer INSTANCE = new EnglishPossessiveTransformer();

    private static final Pattern POSSESSIVE = Pattern.compile(
            "([\\p{L}][\\p{L}0-9_]*)'s\\s+([\\p{L}][\\p{L}0-9_]*)"
    );

    private EnglishPossessiveTransformer() {}

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        return segmenter.replaceOutsideStrings(source, POSSESSIVE, "$1.$2");
    }
}
