package aster.core.lexicon.tools;

import aster.core.lexicon.CanonicalizationConfig;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconAbiVersion;
import aster.core.lexicon.SemanticTokenKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static aster.core.lexicon.tools.LexiconValidationReport.Issue;
import static aster.core.lexicon.tools.LexiconValidationReport.Severity;

/**
 * Lexicon 完整性 + 合规校验器。
 *
 * <p>面向 lexicon 贡献者：把 lexicon JSON 加载后传给 {@link #validate(Lexicon, Lexicon, String)}，
 * 返回结构化报告（ERROR/WARNING/INFO + code + message + suggestion）。
 *
 * <p>检查项（与 PM 08 §3.4 对齐）：
 * <ol>
 *   <li>keyword 集合完整（与 reference lexicon 1:1）</li>
 *   <li>keyword 唯一性（除 allowedDuplicates 中显式声明的组合外）</li>
 *   <li>reserved chars 不冲突（{@code [](),.;:=}）</li>
 *   <li>meta.id IETF BCP 47 简单格式（非占位符）</li>
 *   <li>meta.name 非空且非占位符</li>
 *   <li>direction LTR/RTL 已声明</li>
 *   <li>punctuation 必填项已声明</li>
 *   <li>ABI version 兼容（默认 v1）</li>
 *   <li>所有 keyword 不以 TODO_TRANSLATE_ 开头</li>
 * </ol>
 */
public final class LexiconContributorValidator {

    private static final Pattern RESERVED_CHARS = Pattern.compile("[\\[\\](),.;:=]");
    private static final Pattern BCP47_LIKE = Pattern.compile("^[a-z]{2,3}(-[A-Z]{2,4})?$");
    private static final String TODO_PREFIX = "TODO_TRANSLATE_";
    private static final String TEMPLATE_PLACEHOLDER_ID = "template-XX-XX";

    /**
     * 校验一个 lexicon 是否符合 SPI 合规要求。
     *
     * @param target 被校验的 lexicon
     * @param reference 作为 keyword 完整性基准的 lexicon（通常是 en-US）；可为 null（跳过对比）
     * @param abiVersion 被校验插件报告的 ABI 版本（可为 null）
     */
    public LexiconValidationReport validate(Lexicon target, Lexicon reference, String abiVersion) {
        List<Issue> issues = new ArrayList<>();

        checkMeta(target, issues);
        checkAbi(abiVersion, issues);
        checkKeywordCompleteness(target, reference, issues);
        checkKeywordValues(target, issues);
        checkKeywordUniqueness(target, issues);
        checkPunctuation(target, issues);

        return new LexiconValidationReport(target.getId(), issues);
    }

    private void checkMeta(Lexicon target, List<Issue> issues) {
        String id = target.getId();
        if (id == null || id.isBlank()) {
            issues.add(new Issue(Severity.ERROR, "META_ID_MISSING",
                "Lexicon meta.id is missing or blank",
                "Set meta.id to a valid IETF BCP 47 tag, e.g. 'ja-JP'"));
        } else if (TEMPLATE_PLACEHOLDER_ID.equals(id)) {
            issues.add(new Issue(Severity.ERROR, "META_ID_PLACEHOLDER",
                "Lexicon meta.id is still the template placeholder 'template-XX-XX'",
                "Rename to your IETF BCP 47 tag, e.g. 'ja-JP'"));
        } else if (!BCP47_LIKE.matcher(id).matches()) {
            issues.add(new Issue(Severity.WARNING, "META_ID_NON_STANDARD",
                "Lexicon meta.id '" + id + "' does not match the BCP 47 lowercase-language[-UPPERCASE-region] convention",
                "Consider renaming to lowercase language with optional uppercase region, e.g. 'ja-JP'"));
        }

        if (target.getName() == null || target.getName().isBlank() || target.getName().contains("TODO")) {
            issues.add(new Issue(Severity.ERROR, "META_NAME_MISSING_OR_PLACEHOLDER",
                "Lexicon meta.name is missing, blank, or still contains 'TODO'",
                "Set meta.name to the language's autonym, e.g. '日本語' for ja-JP"));
        }

        if (target.getDirection() == null) {
            issues.add(new Issue(Severity.ERROR, "META_DIRECTION_MISSING",
                "Lexicon meta.direction is missing",
                "Declare 'LTR' or 'RTL' explicitly"));
        }
    }

    private void checkAbi(String abiVersion, List<Issue> issues) {
        if (!LexiconAbiVersion.isCompatible(abiVersion)) {
            issues.add(new Issue(Severity.ERROR, "ABI_INCOMPATIBLE",
                "Plugin reports ABI version '" + abiVersion + "' which is incompatible with core "
                    + LexiconAbiVersion.V1.version,
                "Update LexiconPlugin#getAbiVersion() to return '" + LexiconAbiVersion.V1.version + "'"));
        }
    }

    private void checkKeywordCompleteness(Lexicon target, Lexicon reference, List<Issue> issues) {
        Set<SemanticTokenKind> targetKeys = target.getKeywords().keySet();
        Set<SemanticTokenKind> refKeys = reference != null
            ? reference.getKeywords().keySet()
            : EnumSet.allOf(SemanticTokenKind.class);

        Set<SemanticTokenKind> missing = new TreeSet<>(refKeys);
        missing.removeAll(targetKeys);
        for (SemanticTokenKind k : missing) {
            issues.add(new Issue(Severity.ERROR, "MISSING_KEYWORD",
                "Required keyword '" + k.name() + "' is not defined",
                "Add keywords." + k.name() + " to your lexicon JSON"));
        }
    }

    private void checkKeywordValues(Lexicon target, List<Issue> issues) {
        for (Map.Entry<SemanticTokenKind, String> entry : target.getKeywords().entrySet()) {
            SemanticTokenKind k = entry.getKey();
            String v = entry.getValue();
            if (v == null || v.isBlank()) {
                issues.add(new Issue(Severity.ERROR, "KEYWORD_BLANK",
                    "Keyword '" + k.name() + "' has empty value",
                    "Translate keywords." + k.name() + " to your language"));
                continue;
            }
            if (v.startsWith(TODO_PREFIX)) {
                issues.add(new Issue(Severity.ERROR, "KEYWORD_TODO",
                    "Keyword '" + k.name() + "' still has placeholder value '" + v + "'",
                    "Replace with the translated term"));
                continue;
            }
            if (RESERVED_CHARS.matcher(v).find()) {
                issues.add(new Issue(Severity.ERROR, "KEYWORD_RESERVED_CHAR",
                    "Keyword '" + k.name() + "' value '" + v + "' contains an Aster reserved character",
                    "Remove any of: [ ] ( ) , . ; : ="));
            }
            if (!v.isEmpty() && Character.isDigit(v.charAt(0))) {
                issues.add(new Issue(Severity.WARNING, "KEYWORD_LEADING_DIGIT",
                    "Keyword '" + k.name() + "' value '" + v + "' starts with a digit",
                    "Prefer alphabetic / ideographic leads to avoid parser ambiguity"));
            }
        }
    }

    private void checkKeywordUniqueness(Lexicon target, List<Issue> issues) {
        CanonicalizationConfig canon = target.getCanonicalization();
        List<Set<SemanticTokenKind>> allowed = canon != null && canon.allowedDuplicates() != null
            ? canon.allowedDuplicates()
            : List.of();

        Map<String, List<SemanticTokenKind>> byValue = new HashMap<>();
        for (Map.Entry<SemanticTokenKind, String> entry : target.getKeywords().entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                // null/blank 已由 checkKeywordValues 报 KEYWORD_BLANK，跳过避免 NPE
                continue;
            }
            String norm = value.toLowerCase(Locale.ROOT);
            byValue.computeIfAbsent(norm, k -> new ArrayList<>()).add(entry.getKey());
        }

        for (Map.Entry<String, List<SemanticTokenKind>> e : new TreeMap<>(byValue).entrySet()) {
            List<SemanticTokenKind> kinds = e.getValue();
            if (kinds.size() <= 1) continue;

            Set<SemanticTokenKind> dup = EnumSet.copyOf(kinds);
            boolean isAllowed = allowed.stream().anyMatch(group -> group.containsAll(dup));
            if (!isAllowed) {
                issues.add(new Issue(Severity.ERROR, "KEYWORD_DUPLICATE",
                    "Multiple keywords share the value '" + e.getKey() + "': " + dup,
                    "Either translate one of them differently, or add the group to canonicalization.allowedDuplicates"));
            }
        }
    }

    private void checkPunctuation(Lexicon target, List<Issue> issues) {
        var punct = target.getPunctuation();
        if (punct == null) {
            issues.add(new Issue(Severity.ERROR, "PUNCT_MISSING",
                "Punctuation block is missing",
                "Declare statementEnd / listSeparator / enumSeparator / blockStart"));
            return;
        }
        if (punct.statementEnd() == null || punct.statementEnd().isBlank()) {
            issues.add(new Issue(Severity.ERROR, "PUNCT_STATEMENT_END_MISSING",
                "Punctuation.statementEnd is missing",
                "Set to '.' (or your language's equivalent)"));
        }
        if (punct.listSeparator() == null || punct.listSeparator().isBlank()) {
            issues.add(new Issue(Severity.ERROR, "PUNCT_LIST_SEPARATOR_MISSING",
                "Punctuation.listSeparator is missing",
                "Set to ',' (default)"));
        }
        if (punct.enumSeparator() == null || punct.enumSeparator().isBlank()) {
            issues.add(new Issue(Severity.ERROR, "PUNCT_ENUM_SEPARATOR_MISSING",
                "Punctuation.enumSeparator is missing",
                "Set to ',' or your language's equivalent"));
        }
        if (punct.blockStart() == null || punct.blockStart().isBlank()) {
            issues.add(new Issue(Severity.ERROR, "PUNCT_BLOCK_START_MISSING",
                "Punctuation.blockStart is missing",
                "Set to ':' or your language's equivalent"));
        }
    }
}
