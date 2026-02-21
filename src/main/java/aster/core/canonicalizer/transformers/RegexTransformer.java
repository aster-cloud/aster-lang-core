package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.lexicon.CanonicalizationConfig;

import java.util.regex.Pattern;

/**
 * 基于正则表达式的通用变换器。
 * <p>
 * 从声明式配置（如 {@code customRules}）构造。
 * 仅在字符串字面量外部执行替换。
 */
public final class RegexTransformer implements SyntaxTransformer {

    private final String name;
    private final Pattern pattern;
    private final String replacement;

    public RegexTransformer(String name, String pattern, String replacement) {
        this.name = name;
        this.pattern = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS);
        this.replacement = replacement;
    }

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        return segmenter.replaceOutsideStrings(source, pattern, replacement);
    }

    public String getName() {
        return name;
    }
}
