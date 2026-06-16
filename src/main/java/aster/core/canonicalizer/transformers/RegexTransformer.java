package aster.core.canonicalizer.transformers;

import aster.core.canonicalizer.StringSegmenter;
import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.lexicon.CanonicalizationConfig;
import aster.core.lexicon.RegexGuard;

import java.util.regex.Pattern;

/**
 * 基于正则表达式的通用变换器。
 * <p>
 * 从声明式配置（如 {@code customRules}）构造。
 * 仅在字符串字面量外部执行替换。
 * <p>
 * <b>安全</b>：模式来自不可信 lexicon 配置，构造期经 {@link RegexGuard#compile} 静态筛查
 * （拒绝超长 / 嵌套量词 ReDoS 形状），匹配期经看门狗超时执行，避免灾难性回溯导致 DoS。
 */
public final class RegexTransformer implements SyntaxTransformer {

    private final String name;
    private final Pattern pattern;
    private final String replacement;

    public RegexTransformer(String name, String pattern, String replacement) {
        this.name = name;
        this.pattern = RegexGuard.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS);
        this.replacement = replacement;
    }

    @Override
    public String transform(String source, CanonicalizationConfig config, StringSegmenter segmenter) {
        return segmenter.replaceOutsideStrings(source, pattern, replacement, true);
    }

    public String getName() {
        return name;
    }
}
