package aster.core.lexicon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DynamicLexicon 单元测试。
 * <p>
 * 验证从 JSON 配置加载 Lexicon 的功能。
 */
class DynamicLexiconTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构建一个最小但完整的 JSON 配置，覆盖全部 74 个 SemanticTokenKind。
     */
    private static ObjectNode buildMinimalLexiconJson() {
        ObjectNode root = mapper.createObjectNode();

        // meta
        ObjectNode meta = root.putObject("meta");
        meta.put("id", "test-LN");
        meta.put("name", "Test Language");
        meta.put("direction", "LTR");

        // keywords: 为全部 SemanticTokenKind 填充值
        ObjectNode keywords = root.putObject("keywords");
        for (SemanticTokenKind kind : SemanticTokenKind.values()) {
            keywords.put(kind.name(), "test_" + kind.name().toLowerCase());
        }
        // 覆盖一些特定值
        keywords.put("MODULE_DECL", "TestModule");
        keywords.put("IF", "TestIf");
        keywords.put("RETURN", "TestReturn");
        keywords.put("TRUE", "testTrue");
        keywords.put("FALSE", "testFalse");

        // punctuation
        ObjectNode punct = root.putObject("punctuation");
        punct.put("statementEnd", ".");
        punct.put("listSeparator", ",");
        punct.put("enumSeparator", ",");
        punct.put("blockStart", ":");
        punct.put("stringQuoteOpen", "\"");
        punct.put("stringQuoteClose", "\"");

        // canonicalization
        ObjectNode canon = root.putObject("canonicalization");
        canon.put("fullWidthToHalf", false);
        canon.put("whitespaceMode", "ENGLISH");
        canon.put("removeArticles", false);

        // messages
        ObjectNode messages = root.putObject("messages");
        messages.put("unexpectedToken", "Unexpected: {token}");
        messages.put("expectedKeyword", "Expected: {keyword}");
        messages.put("undefinedVariable", "Undefined: {name}");
        messages.put("typeMismatch", "Type mismatch: {expected} vs {actual}");
        messages.put("unterminatedString", "Unterminated string");
        messages.put("invalidIndentation", "Bad indentation");

        return root;
    }

    @Test
    void testFromJsonString_minimalConfig() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());

        assertThat(lexicon.getId()).isEqualTo("test-LN");
        assertThat(lexicon.getName()).isEqualTo("Test Language");
        assertThat(lexicon.getDirection()).isEqualTo(Lexicon.Direction.LTR);
    }

    @Test
    void testKeywordsMapping() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());

        Map<SemanticTokenKind, String> keywords = lexicon.getKeywords();
        assertThat(keywords).hasSize(SemanticTokenKind.values().length);
        assertThat(keywords.get(SemanticTokenKind.MODULE_DECL)).isEqualTo("TestModule");
        assertThat(keywords.get(SemanticTokenKind.IF)).isEqualTo("TestIf");
        assertThat(keywords.get(SemanticTokenKind.RETURN)).isEqualTo("TestReturn");
        assertThat(keywords.get(SemanticTokenKind.TRUE)).isEqualTo("testTrue");
    }

    @Test
    void testPunctuationConfig() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());

        PunctuationConfig punct = lexicon.getPunctuation();
        assertThat(punct.statementEnd()).isEqualTo(".");
        assertThat(punct.listSeparator()).isEqualTo(",");
        assertThat(punct.blockStart()).isEqualTo(":");
        assertThat(punct.stringQuoteOpen()).isEqualTo("\"");
        assertThat(punct.stringQuoteClose()).isEqualTo("\"");
        assertThat(punct.hasMarkers()).isFalse();
    }

    @Test
    void testPunctuationWithMarkers() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode punct = (ObjectNode) json.get("punctuation");
        punct.put("markerOpen", "【");
        punct.put("markerClose", "】");

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getPunctuation().hasMarkers()).isTrue();
        assertThat(lexicon.getPunctuation().markerOpen()).isEqualTo("【");
        assertThat(lexicon.getPunctuation().markerClose()).isEqualTo("】");
    }

    @Test
    void testCanonicalizationConfig() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode canon = (ObjectNode) json.get("canonicalization");
        canon.put("fullWidthToHalf", true);
        canon.put("whitespaceMode", "CHINESE");
        canon.put("removeArticles", true);
        ArrayNode articles = canon.putArray("articles");
        articles.add("a");
        articles.add("the");

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        CanonicalizationConfig config = lexicon.getCanonicalization();

        assertThat(config.fullWidthToHalf()).isTrue();
        assertThat(config.whitespaceMode()).isEqualTo(CanonicalizationConfig.WhitespaceMode.CHINESE);
        assertThat(config.removeArticles()).isTrue();
        assertThat(config.articles()).containsExactly("a", "the");
    }

    @Test
    void testCustomRules() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode canon = (ObjectNode) json.get("canonicalization");
        ArrayNode rules = canon.putArray("customRules");
        ObjectNode rule = mapper.createObjectNode();
        rule.put("name", "test-rule");
        rule.put("pattern", "\\bfoo\\b");
        rule.put("replacement", "bar");
        rules.add(rule);

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getCanonicalization().customRules()).hasSize(1);
        assertThat(lexicon.getCanonicalization().customRules().get(0).name()).isEqualTo("test-rule");
        assertThat(lexicon.getCanonicalization().customRules().get(0).pattern()).isEqualTo("\\bfoo\\b");
    }

    @Test
    void testAllowedDuplicates() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode canon = (ObjectNode) json.get("canonicalization");
        ArrayNode dupes = canon.putArray("allowedDuplicates");
        ArrayNode group = mapper.createArrayNode();
        group.add("UNDER");
        group.add("LESS_THAN");
        dupes.add(group);

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getCanonicalization().allowedDuplicates()).hasSize(1);
        assertThat(lexicon.getCanonicalization().allowedDuplicates().get(0))
                .containsExactlyInAnyOrder(SemanticTokenKind.UNDER, SemanticTokenKind.LESS_THAN);
    }

    @Test
    void testTransformerChain_builtinByName() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode canon = (ObjectNode) json.get("canonicalization");
        ArrayNode preTransformers = canon.putArray("preTranslationTransformers");
        preTransformers.add("english-possessive");

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getCanonicalization().preTranslationTransformers()).hasSize(1);
    }

    @Test
    void testTransformerChain_customRegex() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode canon = (ObjectNode) json.get("canonicalization");
        ArrayNode postTransformers = canon.putArray("postTranslationTransformers");
        ObjectNode regexRule = mapper.createObjectNode();
        regexRule.put("name", "custom-regex");
        regexRule.put("pattern", "\\btest\\b");
        regexRule.put("replacement", "replaced");
        postTransformers.add(regexRule);

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getCanonicalization().postTranslationTransformers()).hasSize(1);
    }

    @Test
    void testMessages() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());

        ErrorMessages messages = lexicon.getMessages();
        assertThat(messages.unexpectedToken()).isEqualTo("Unexpected: {token}");
        assertThat(messages.typeMismatch()).isEqualTo("Type mismatch: {expected} vs {actual}");
    }

    @Test
    void testRTLDirection() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ((ObjectNode) json.get("meta")).put("direction", "RTL");

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getDirection()).isEqualTo(Lexicon.Direction.RTL);
    }

    @Test
    void testFromJson_fileLoad() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        Path tempFile = Files.createTempFile("test-lexicon-", ".json");
        try {
            Files.writeString(tempFile, json.toString());
            DynamicLexicon lexicon = DynamicLexicon.fromJson(tempFile);
            assertThat(lexicon.getId()).isEqualTo("test-LN");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testMissingMetaField_throwsException() {
        ObjectNode json = mapper.createObjectNode();
        json.putObject("keywords");
        json.putObject("punctuation");

        assertThatThrownBy(() -> DynamicLexicon.fromJsonString(json.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("meta");
    }

    @Test
    void testMissingKeywordsField_throwsException() {
        ObjectNode json = mapper.createObjectNode();
        ObjectNode meta = json.putObject("meta");
        meta.put("id", "xx-XX");
        meta.put("name", "Test");
        json.putObject("punctuation");

        assertThatThrownBy(() -> DynamicLexicon.fromJsonString(json.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keywords");
    }

    @Test
    void testUnknownTokenKind_ignored() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode keywords = (ObjectNode) json.get("keywords");
        keywords.put("UNKNOWN_FUTURE_TOKEN", "whatever");

        // 应该不抛异常，未知 token 被忽略
        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getKeywords()).hasSize(SemanticTokenKind.values().length);
    }

    @Test
    void testDefaultCanonicalizationWhenMissing() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        json.remove("canonicalization");

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        // 默认使用英文配置
        assertThat(lexicon.getCanonicalization()).isNotNull();
        assertThat(lexicon.getCanonicalization().whitespaceMode())
                .isEqualTo(CanonicalizationConfig.WhitespaceMode.ENGLISH);
    }

    @Test
    void testDefaultMessagesWhenMissing() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        json.remove("messages");

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getMessages()).isNotNull();
        assertThat(lexicon.getMessages().unexpectedToken()).contains("{token}");
    }

    @Test
    void testLexiconInterfaceMethods() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());

        // 测试 Lexicon 接口的默认方法
        assertThat(lexicon.isKeyword("TestModule")).isTrue();
        assertThat(lexicon.isKeyword("nonexistent")).isFalse();

        assertThat(lexicon.findSemanticTokenKind("TestIf"))
                .isPresent()
                .contains(SemanticTokenKind.IF);

        assertThat(lexicon.getKeyword(SemanticTokenKind.MODULE_DECL))
                .isPresent()
                .contains("TestModule");

        assertThat(lexicon.buildKeywordIndex()).containsKey("testmodule"); // 小写
    }

    @Test
    void testCompoundPatterns() throws Exception {
        ObjectNode json = buildMinimalLexiconJson();
        ObjectNode canon = (ObjectNode) json.get("canonicalization");
        ArrayNode patterns = canon.putArray("compoundPatterns");
        ObjectNode pattern = mapper.createObjectNode();
        pattern.put("name", "match-when");
        pattern.put("opener", "MATCH");
        ArrayNode ctxKw = pattern.putArray("contextualKeywords");
        ctxKw.add("WHEN");
        pattern.put("closer", "DEDENT");
        patterns.add(pattern);

        DynamicLexicon lexicon = DynamicLexicon.fromJsonString(json.toString());
        assertThat(lexicon.getCanonicalization().compoundPatterns()).hasSize(1);
        assertThat(lexicon.getCanonicalization().compoundPatterns().get(0).name()).isEqualTo("match-when");
        assertThat(lexicon.getCanonicalization().compoundPatterns().get(0).opener()).isEqualTo(SemanticTokenKind.MATCH);
    }
}
