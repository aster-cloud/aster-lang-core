package aster.core.lexicon;

import aster.core.canonicalizer.TransformerRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        return appendSemanticChecks(lexicon, baseResult);
    }

    /**
     * 在给定的 base 校验结果上追加 DynamicLexicon 特有校验（customRules 可编译性 +
     * ADR-0022 别名遮蔽/重复）。
     * <p>
     * 抽出成独立方法，是为了让 {@link LexiconRegistry#register} 与 SPI 注册路径复用<b>同一</b>
     * 别名硬校验契约（审计 #58：别名遮蔽此前只在 CLI/测试里被 {@link #validateLexicon} 拦，
     * register() 从不调用）。关键：本方法<b>不</b>调用 {@link LexiconRegistry#getInstance()}——
     * registry 构造期（loadEmbeddedDefaults / discoverPlugins）INSTANCE 尚未赋值，getInstance()
     * 会返回 null。调用方传入用<b>实例方法</b> {@code this.validate(lexicon)} 算好的 baseResult 即可。
     *
     * @param lexicon    待校验的 lexicon
     * @param baseResult {@link LexiconRegistry#validate} 的结果（由调用方以实例方法算出）
     * @return 追加了别名/正则校验后的结果
     */
    static LexiconRegistry.ValidationResult appendSemanticChecks(
            Lexicon lexicon, LexiconRegistry.ValidationResult baseResult) {
        List<String> errors = new ArrayList<>(baseResult.errors());
        List<String> warnings = new ArrayList<>(baseResult.warnings());

        // 额外校验：customRules 的正则可编译性 + ReDoS 形状筛查
        CanonicalizationConfig config = lexicon.getCanonicalization();
        if (config.customRules() != null) {
            for (CanonicalizationConfig.CanonicalizationRule rule : config.customRules()) {
                // 不可信正则：先做 ReDoS 静态筛查（超长 / 嵌套量词），再验证可编译性。
                for (String redosError : RegexGuard.screen(rule.pattern())) {
                    errors.add("Unsafe regex in customRule '" + rule.name() + "': " + redosError);
                }
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

        // ADR 0022：别名不得遮蔽任何规范拼写或其它 kind 的别名。别名经规范化（lowercase）
        // 后若与任一规范拼写撞 → error（会让识别歧义/抢占规范词）；与其它别名撞 → error。
        // 这把"别名零损"的前提固化为注册时的硬校验。
        Map<String, SemanticTokenKind> canonByLower = new HashMap<>();
        for (Map.Entry<SemanticTokenKind, String> e : lexicon.getKeywords().entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                canonByLower.put(e.getValue().toLowerCase(Locale.ROOT), e.getKey());
            }
        }
        Map<String, SemanticTokenKind> aliasByLower = new HashMap<>();
        for (Map.Entry<SemanticTokenKind, List<String>> e : lexicon.getAliases().entrySet()) {
            for (String alias : e.getValue()) {
                if (alias == null || alias.isBlank()) {
                    errors.add("Empty alias for " + e.getKey());
                    continue;
                }
                String lower = alias.toLowerCase(Locale.ROOT);
                SemanticTokenKind clashCanon = canonByLower.get(lower);
                if (clashCanon != null) {
                    errors.add("Alias '" + alias + "' for " + e.getKey()
                            + " shadows canonical keyword of " + clashCanon);
                }
                SemanticTokenKind clashAlias = aliasByLower.putIfAbsent(lower, e.getKey());
                if (clashAlias != null) {
                    errors.add("Alias '" + alias + "' is defined for both "
                            + clashAlias + " and " + e.getKey());
                }
            }
        }

        return new LexiconRegistry.ValidationResult(errors.isEmpty(), errors, warnings);
    }
}
