package aster.core.lexicon;

import aster.core.canonicalizer.SyntaxTransformer;
import aster.core.canonicalizer.TransformerRegistry;
import aster.core.canonicalizer.transformers.RegexTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据驱动的 Lexicon 实现。
 * <p>
 * 从 JSON 配置文件构造，无需每种语言编写 Java 类。
 * 支持内置变换器引用（{@link TransformerRegistry}）和声明式正则规则（{@link RegexTransformer}）。
 * <p>
 * JSON 格式参见 {@code build/generated/lexicons/lexicons.json} 中的示例。
 */
public final class DynamicLexicon implements Lexicon {

    private final String id;
    private final String name;
    private final Direction direction;
    private final Map<SemanticTokenKind, String> keywords;
    private final PunctuationConfig punctuation;
    private final CanonicalizationConfig canonicalization;
    private final ErrorMessages messages;

    private DynamicLexicon(
            String id,
            String name,
            Direction direction,
            Map<SemanticTokenKind, String> keywords,
            PunctuationConfig punctuation,
            CanonicalizationConfig canonicalization,
            ErrorMessages messages
    ) {
        this.id = id;
        this.name = name;
        this.direction = direction;
        this.keywords = Map.copyOf(keywords);
        this.punctuation = punctuation;
        this.canonicalization = canonicalization;
        this.messages = messages;
    }

    /**
     * 从 JSON 文件加载 Lexicon。
     *
     * @param jsonFile JSON 配置文件路径
     * @return DynamicLexicon 实例
     * @throws UncheckedIOException 如果文件读取失败
     * @throws IllegalArgumentException 如果 JSON 内容无效
     */
    public static DynamicLexicon fromJson(Path jsonFile) {
        try {
            String content = Files.readString(jsonFile);
            return fromJsonString(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read lexicon file: " + jsonFile, e);
        }
    }

    /**
     * 从 JSON 字符串加载 Lexicon。
     *
     * @param json JSON 内容
     * @return DynamicLexicon 实例
     * @throws IllegalArgumentException 如果 JSON 内容无效
     */
    public static DynamicLexicon fromJsonString(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            return fromJsonNode(root);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON content", e);
        }
    }

    /**
     * 从已解析的 JSON 节点构造 Lexicon。
     *
     * @param root JSON 根节点
     * @return DynamicLexicon 实例
     */
    public static DynamicLexicon fromJsonNode(JsonNode root) {
        // meta
        JsonNode meta = requireNode(root, "meta");
        String id = requireText(meta, "id");
        String name = requireText(meta, "name");
        Direction direction = parseDirection(meta.path("direction").asText("LTR"));

        // keywords
        Map<SemanticTokenKind, String> keywords = parseKeywords(requireNode(root, "keywords"));

        // punctuation
        PunctuationConfig punctuation = parsePunctuation(requireNode(root, "punctuation"));

        // canonicalization
        CanonicalizationConfig canonicalization = parseCanonicalization(root.path("canonicalization"));

        // messages
        ErrorMessages messages = parseMessages(root.path("messages"));

        return new DynamicLexicon(id, name, direction, keywords, punctuation, canonicalization, messages);
    }

    // ============================================================
    // 解析方法
    // ============================================================

    private static Map<SemanticTokenKind, String> parseKeywords(JsonNode node) {
        Map<SemanticTokenKind, String> keywords = new EnumMap<>(SemanticTokenKind.class);
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            try {
                SemanticTokenKind kind = SemanticTokenKind.valueOf(entry.getKey());
                keywords.put(kind, entry.getValue().asText());
            } catch (IllegalArgumentException e) {
                // 忽略未知 token（前向兼容）
            }
        }
        return keywords;
    }

    private static PunctuationConfig parsePunctuation(JsonNode node) {
        return new PunctuationConfig(
                requireText(node, "statementEnd"),
                requireText(node, "listSeparator"),
                node.path("enumSeparator").asText(requireText(node, "listSeparator")),
                requireText(node, "blockStart"),
                requireText(node, "stringQuoteOpen"),
                requireText(node, "stringQuoteClose"),
                node.path("markerOpen").isTextual() ? node.path("markerOpen").asText() : null,
                node.path("markerClose").isTextual() ? node.path("markerClose").asText() : null
        );
    }

    private static CanonicalizationConfig parseCanonicalization(JsonNode node) {
        if (node.isMissingNode()) {
            return CanonicalizationConfig.english();
        }

        boolean fullWidthToHalf = node.path("fullWidthToHalf").asBoolean(false);

        CanonicalizationConfig.WhitespaceMode whitespaceMode =
                parseWhitespaceMode(node.path("whitespaceMode").asText("ENGLISH"));

        boolean removeArticles = node.path("removeArticles").asBoolean(false);

        List<String> articles = new ArrayList<>();
        JsonNode articlesNode = node.path("articles");
        if (articlesNode.isArray()) {
            for (JsonNode a : articlesNode) {
                articles.add(a.asText());
            }
        }

        List<CanonicalizationConfig.CanonicalizationRule> customRules = parseCustomRules(node.path("customRules"));

        List<Set<SemanticTokenKind>> allowedDuplicates = parseAllowedDuplicates(node.path("allowedDuplicates"));

        List<CompoundPattern> compoundPatterns = parseCompoundPatterns(node.path("compoundPatterns"));

        List<SyntaxTransformer> preTransformers = parseTransformerChain(node.path("preTranslationTransformers"));
        List<SyntaxTransformer> postTransformers = parseTransformerChain(node.path("postTranslationTransformers"));

        return new CanonicalizationConfig(
                fullWidthToHalf,
                whitespaceMode,
                removeArticles,
                articles,
                customRules,
                allowedDuplicates,
                compoundPatterns,
                preTransformers,
                postTransformers
        );
    }

    private static List<CanonicalizationConfig.CanonicalizationRule> parseCustomRules(JsonNode node) {
        List<CanonicalizationConfig.CanonicalizationRule> rules = new ArrayList<>();
        if (!node.isArray()) return rules;
        for (JsonNode rule : node) {
            rules.add(new CanonicalizationConfig.CanonicalizationRule(
                    requireText(rule, "name"),
                    requireText(rule, "pattern"),
                    requireText(rule, "replacement")
            ));
        }
        return rules;
    }

    private static List<Set<SemanticTokenKind>> parseAllowedDuplicates(JsonNode node) {
        List<Set<SemanticTokenKind>> result = new ArrayList<>();
        if (!node.isArray()) return result;
        for (JsonNode group : node) {
            if (!group.isArray()) continue;
            Set<SemanticTokenKind> set = new HashSet<>();
            for (JsonNode tokenName : group) {
                try {
                    set.add(SemanticTokenKind.valueOf(tokenName.asText()));
                } catch (IllegalArgumentException e) {
                    // 忽略未知 token
                }
            }
            if (set.size() >= 2) {
                result.add(set);
            }
        }
        return result;
    }

    private static List<CompoundPattern> parseCompoundPatterns(JsonNode node) {
        List<CompoundPattern> patterns = new ArrayList<>();
        if (!node.isArray()) return patterns;
        for (JsonNode p : node) {
            SemanticTokenKind opener;
            try {
                opener = SemanticTokenKind.valueOf(requireText(p, "opener"));
            } catch (IllegalArgumentException e) {
                continue;
            }

            List<SemanticTokenKind> contextualKeywords = new ArrayList<>();
            JsonNode kwNode = p.path("contextualKeywords");
            if (kwNode.isArray()) {
                for (JsonNode kw : kwNode) {
                    try {
                        contextualKeywords.add(SemanticTokenKind.valueOf(kw.asText()));
                    } catch (IllegalArgumentException e) {
                        // 忽略
                    }
                }
            }

            CompoundPattern.CloserType closer = CompoundPattern.CloserType.DEDENT;
            String closerStr = p.path("closer").asText("DEDENT");
            try {
                closer = CompoundPattern.CloserType.valueOf(closerStr);
            } catch (IllegalArgumentException e) {
                // 使用默认值
            }

            patterns.add(new CompoundPattern(
                    requireText(p, "name"),
                    opener,
                    contextualKeywords,
                    closer
            ));
        }
        return patterns;
    }

    /**
     * 解析变换器链。
     * <p>
     * 支持两种格式：
     * <ul>
     *   <li>字符串：内置变换器名称，从 {@link TransformerRegistry} 查找</li>
     *   <li>对象 {@code {"name", "pattern", "replacement"}}：声明式正则规则</li>
     * </ul>
     */
    private static List<SyntaxTransformer> parseTransformerChain(JsonNode node) {
        List<SyntaxTransformer> chain = new ArrayList<>();
        if (!node.isArray()) return chain;

        for (JsonNode item : node) {
            if (item.isTextual()) {
                // 内置变换器名称
                String transformerName = item.asText();
                if (TransformerRegistry.contains(transformerName)) {
                    chain.add(TransformerRegistry.get(transformerName));
                }
            } else if (item.isObject() && item.has("pattern")) {
                // 声明式正则规则
                chain.add(new RegexTransformer(
                        item.path("name").asText("custom-rule"),
                        requireText(item, "pattern"),
                        requireText(item, "replacement")
                ));
            }
        }
        return chain;
    }

    private static ErrorMessages parseMessages(JsonNode node) {
        if (node.isMissingNode()) {
            return ErrorMessages.english();
        }
        return new ErrorMessages(
                node.path("unexpectedToken").asText("Unexpected token: {token}"),
                node.path("expectedKeyword").asText("Expected keyword: {keyword}"),
                node.path("undefinedVariable").asText("Undefined variable: {name}"),
                node.path("typeMismatch").asText("Type mismatch: expected {expected}, got {actual}"),
                node.path("unterminatedString").asText("Unterminated string literal"),
                node.path("invalidIndentation").asText("Invalid indentation: must be multiples of 2 spaces")
        );
    }

    private static Direction parseDirection(String value) {
        return switch (value.toUpperCase()) {
            case "RTL" -> Direction.RTL;
            default -> Direction.LTR;
        };
    }

    private static CanonicalizationConfig.WhitespaceMode parseWhitespaceMode(String value) {
        return switch (value.toUpperCase()) {
            case "CHINESE" -> CanonicalizationConfig.WhitespaceMode.CHINESE;
            case "MIXED" -> CanonicalizationConfig.WhitespaceMode.MIXED;
            default -> CanonicalizationConfig.WhitespaceMode.ENGLISH;
        };
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private static JsonNode requireNode(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return node;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Missing or non-text field: " + field);
        }
        return node.asText();
    }

    // ============================================================
    // Lexicon 接口实现
    // ============================================================

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Direction getDirection() {
        return direction;
    }

    @Override
    public Map<SemanticTokenKind, String> getKeywords() {
        return keywords;
    }

    @Override
    public PunctuationConfig getPunctuation() {
        return punctuation;
    }

    @Override
    public CanonicalizationConfig getCanonicalization() {
        return canonicalization;
    }

    @Override
    public ErrorMessages getMessages() {
        return messages;
    }
}
