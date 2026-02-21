package aster.core.lexicon;

import aster.core.canonicalizer.TransformerRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 语言包校验器。
 * <p>
 * 对 JSON 语言包文件进行完整性和正确性校验：
 * <ul>
 *   <li>全部 {@link SemanticTokenKind} 必须有映射</li>
 *   <li>变换器名称必须在 {@link TransformerRegistry} 中存在</li>
 *   <li>customRules 的正则必须可编译</li>
 *   <li>标点配置必须完整</li>
 * </ul>
 */
public final class LexiconValidator {

    private LexiconValidator() {}

    /**
     * 校验 JSON 语言包文件。
     *
     * @param jsonFile JSON 文件路径
     * @return 校验结果
     */
    public static LexiconRegistry.ValidationResult validateFile(Path jsonFile) {
        DynamicLexicon lexicon;
        try {
            lexicon = DynamicLexicon.fromJson(jsonFile);
        } catch (Exception e) {
            return new LexiconRegistry.ValidationResult(
                    false,
                    List.of("Failed to parse JSON: " + e.getMessage()),
                    List.of()
            );
        }
        return validateLexicon(lexicon);
    }

    /**
     * 校验 JSON 字符串。
     *
     * @param json JSON 内容
     * @return 校验结果
     */
    public static LexiconRegistry.ValidationResult validateJsonString(String json) {
        DynamicLexicon lexicon;
        try {
            lexicon = DynamicLexicon.fromJsonString(json);
        } catch (Exception e) {
            return new LexiconRegistry.ValidationResult(
                    false,
                    List.of("Failed to parse JSON: " + e.getMessage()),
                    List.of()
            );
        }
        return validateLexicon(lexicon);
    }

    /**
     * 校验 Lexicon 实例（包括 DynamicLexicon 特有的校验）。
     *
     * @param lexicon Lexicon 实例
     * @return 校验结果
     */
    public static LexiconRegistry.ValidationResult validateLexicon(Lexicon lexicon) {
        // 先使用 LexiconRegistry 的通用验证
        LexiconRegistry.ValidationResult baseResult = LexiconRegistry.getInstance().validate(lexicon);

        List<String> errors = new ArrayList<>(baseResult.errors());
        List<String> warnings = new ArrayList<>(baseResult.warnings());

        // 额外校验：customRules 的正则可编译性
        CanonicalizationConfig config = lexicon.getCanonicalization();
        if (config.customRules() != null) {
            for (CanonicalizationConfig.CanonicalizationRule rule : config.customRules()) {
                try {
                    Pattern.compile(rule.pattern());
                } catch (PatternSyntaxException e) {
                    errors.add("Invalid regex in customRule '" + rule.name() + "': " + e.getMessage());
                }
            }
        }

        // 额外校验：检查关键词映射完整性
        int expected = SemanticTokenKind.values().length;
        int actual = lexicon.getKeywords().size();
        if (actual < expected) {
            warnings.add("Lexicon has " + actual + " keywords, expected " + expected
                    + " (missing " + (expected - actual) + ")");
        }

        return new LexiconRegistry.ValidationResult(errors.isEmpty(), errors, warnings);
    }
}
