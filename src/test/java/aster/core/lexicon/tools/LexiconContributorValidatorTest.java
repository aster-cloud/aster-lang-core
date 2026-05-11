package aster.core.lexicon.tools;

import aster.core.lexicon.DynamicLexicon;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.tools.LexiconValidationReport.Issue;
import aster.core.lexicon.tools.LexiconValidationReport.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LexiconContributorValidator — 贡献者校验场景")
class LexiconContributorValidatorTest {

    private final LexiconContributorValidator validator = new LexiconContributorValidator();

    @Test
    @DisplayName("placeholder template-XX-XX 被检测为 ERROR")
    void rejectsPlaceholderMetaId() {
        String json = """
            {
              "meta": { "id": "template-XX-XX", "name": "Template", "direction": "LTR" },
              "keywords": {},
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.passed()).isFalse();
        assertThat(report.errors()).extracting(Issue::code).contains("META_ID_PLACEHOLDER");
    }

    @Test
    @DisplayName("TODO_TRANSLATE_ 开头的 keyword 被检测为 ERROR")
    void rejectsTodoTranslateKeyword() {
        String json = """
            {
              "meta": { "id": "fr-FR", "name": "Français", "direction": "LTR" },
              "keywords": { "MODULE_DECL": "TODO_TRANSLATE_MODULE_DECL" },
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.passed()).isFalse();
        assertThat(report.errors()).extracting(Issue::code).contains("KEYWORD_TODO");
    }

    @Test
    @DisplayName("保留字符 [](),.;:= 被检测为 ERROR")
    void rejectsReservedChars() {
        String json = """
            {
              "meta": { "id": "fr-FR", "name": "Français", "direction": "LTR" },
              "keywords": { "OR": "ou,encore" },
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.errors()).extracting(Issue::code).contains("KEYWORD_RESERVED_CHAR");
    }

    @Test
    @DisplayName("重复 keyword 被检测为 ERROR（除 allowedDuplicates）")
    void rejectsDuplicateKeywordValues() {
        String json = """
            {
              "meta": { "id": "fr-FR", "name": "Français", "direction": "LTR" },
              "keywords": { "AND": "et", "OR": "et" },
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.errors()).extracting(Issue::code).contains("KEYWORD_DUPLICATE");
    }

    @Test
    @DisplayName("allowedDuplicates 中显式允许的重复不报错")
    void acceptsAllowedDuplicates() {
        // TO_WORD + FUNC_TO 在 en-US 中均译为 "to"，由 allowedDuplicates 显式允许
        String json = """
            {
              "meta": { "id": "fake-en", "name": "Fake EN", "direction": "LTR" },
              "keywords": { "TO_WORD": "to", "FUNC_TO": "to" },
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" },
              "canonicalization": {
                "fullWidthToHalf": false, "whitespaceMode": "ENGLISH", "removeArticles": false,
                "articles": [], "allowedDuplicates": [["TO_WORD", "FUNC_TO"]],
                "preTranslationTransformers": [], "postTranslationTransformers": []
              }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.errors()).extracting(Issue::code).doesNotContain("KEYWORD_DUPLICATE");
    }

    @Test
    @DisplayName("ABI 不兼容版本被检测为 ERROR")
    void rejectsIncompatibleAbi() {
        String json = """
            {
              "meta": { "id": "fr-FR", "name": "Français", "direction": "LTR" },
              "keywords": {},
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, "2.0");

        assertThat(report.errors()).extracting(Issue::code).contains("ABI_INCOMPATIBLE");
    }

    @Test
    @DisplayName("null/blank keyword 不会触发 NPE（codex audit M-1）")
    void doesNotThrowOnNullOrBlankKeyword() {
        String json = """
            {
              "meta": { "id": "fr-FR", "name": "Français", "direction": "LTR" },
              "keywords": { "MODULE_DECL": "", "IMPORT": "Module" },
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);

        // 不应抛 NPE；应正常返回 report，含 KEYWORD_BLANK
        var report = validator.validate(lex, null, null);
        assertThat(report.errors()).extracting(Issue::code).contains("KEYWORD_BLANK");
    }

    @Test
    @DisplayName("punctuation.enumSeparator 缺失被检测为 ERROR（codex audit M-2）")
    void rejectsMissingEnumSeparator() {
        String json = """
            {
              "meta": { "id": "fr-FR", "name": "Français", "direction": "LTR" },
              "keywords": {},
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": "", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.errors()).extracting(Issue::code).contains("PUNCT_ENUM_SEPARATOR_MISSING");
    }

    @Test
    @DisplayName("punctuation.blockStart 缺失被检测为 ERROR（codex audit M-2）")
    void rejectsMissingBlockStart() {
        String json = """
            {
              "meta": { "id": "fr-FR", "name": "Français", "direction": "LTR" },
              "keywords": {},
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": "", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.errors()).extracting(Issue::code).contains("PUNCT_BLOCK_START_MISSING");
    }

    @Test
    @DisplayName("非 BCP47 格式 meta.id 触发 WARNING（不阻断）")
    void warnsOnNonStandardMetaId() {
        String json = """
            {
              "meta": { "id": "FrenchFrance", "name": "Français", "direction": "LTR" },
              "keywords": {},
              "punctuation": { "statementEnd": ".", "listSeparator": ",", "enumSeparator": ",", "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\"" }
            }
            """;
        Lexicon lex = DynamicLexicon.fromJsonString(json);
        var report = validator.validate(lex, null, null);

        assertThat(report.issues()).extracting(Issue::severity, Issue::code)
            .contains(org.assertj.core.groups.Tuple.tuple(Severity.WARNING, "META_ID_NON_STANDARD"));
    }
}
