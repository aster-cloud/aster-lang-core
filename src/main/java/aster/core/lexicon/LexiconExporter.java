package aster.core.lexicon;

import aster.core.canonicalizer.TransformerRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 将所有已注册的 Lexicon 定义导出为 JSON 格式。
 * <p>
 * 输出的 JSON 可供 aster-lang-ts 和 aster-cloud 代码生成脚本消费，
 * 确保跨项目的关键词映射始终与 Java 真源保持一致。
 * <p>
 * <b>注意</b>：{@link CanonicalizationConfig} 中的 {@code SyntaxTransformer} 实例
 * 属于代码逻辑，不会被序列化到 JSON 中。JSON 仅包含声明式配置数据。
 */
public final class LexiconExporter {

    private final ObjectMapper mapper;

    public LexiconExporter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 导出所有已注册的 Lexicon 到 JSON（包含 overlay 数据）。
     * <p>
     * 通过 {@link LexiconRegistry} 动态获取所有已注册的语言包，
     * 并通过 SPI 发现 {@link LexiconPlugin} 以提取 overlay 资源。
     *
     * @param version 版本号（如 "0.0.1"）
     * @param writer  输出目标
     */
    public void export(String version, Writer writer) throws IOException {
        List<Lexicon> lexicons = new ArrayList<>(LexiconRegistry.getInstance().getAll());
        lexicons.sort((a, b) -> a.getId().compareTo(b.getId()));

        Map<String, LexiconPlugin> pluginsByLexiconId = discoverPluginMap();
        export(version, lexicons, pluginsByLexiconId, writer);
    }

    /**
     * 导出指定 Lexicon 列表到 JSON（不含 overlay）。
     *
     * @param version  版本号
     * @param lexicons 要导出的 Lexicon 列表
     * @param writer   输出目标
     */
    public void export(String version, List<Lexicon> lexicons, Writer writer) throws IOException {
        export(version, lexicons, Map.of(), writer);
    }

    /**
     * 导出指定 Lexicon 列表到 JSON，附带 overlay 资源。
     *
     * @param version            版本号
     * @param lexicons           要导出的 Lexicon 列表
     * @param pluginsByLexiconId lexicon ID 到 LexiconPlugin 的映射
     * @param writer             输出目标
     */
    public void export(String version, List<Lexicon> lexicons,
                       Map<String, LexiconPlugin> pluginsByLexiconId, Writer writer) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        root.put("version", version);
        root.put("generatedAt", Instant.now().toString());

        // tokenKinds: 所有枚举值，保持声明顺序
        ArrayNode tokenKinds = root.putArray("tokenKinds");
        for (SemanticTokenKind kind : SemanticTokenKind.values()) {
            tokenKinds.add(kind.name());
        }

        // categories: 分类映射
        ObjectNode categories = root.putObject("categories");
        for (Map.Entry<String, List<SemanticTokenKind>> entry : SemanticTokenKind.CATEGORIES.entrySet()) {
            ArrayNode arr = categories.putArray(entry.getKey());
            for (SemanticTokenKind kind : entry.getValue()) {
                arr.add(kind.name());
            }
        }

        // lexicons
        ObjectNode lexiconsNode = root.putObject("lexicons");
        for (Lexicon lexicon : lexicons) {
            LexiconPlugin plugin = pluginsByLexiconId.get(lexicon.getId());
            lexiconsNode.set(lexicon.getId(), serializeLexicon(lexicon, plugin));
        }

        // checksum: 对 lexicons 内容的 SHA-256
        String lexiconsJson = mapper.writeValueAsString(lexiconsNode);
        root.put("checksum", sha256(lexiconsJson));

        mapper.writeValue(writer, root);
    }

    private ObjectNode serializeLexicon(Lexicon lexicon, LexiconPlugin plugin) {
        ObjectNode node = mapper.createObjectNode();

        node.put("id", lexicon.getId());
        node.put("name", lexicon.getName());
        node.put("direction", lexicon.getDirection().name().toLowerCase());

        // keywords: 有序映射（按 SemanticTokenKind 枚举声明顺序）
        ObjectNode keywords = node.putObject("keywords");
        Map<SemanticTokenKind, String> kw = lexicon.getKeywords();
        for (SemanticTokenKind kind : SemanticTokenKind.values()) {
            String value = kw.get(kind);
            if (value != null) {
                keywords.put(kind.name(), value);
            }
        }

        // punctuation
        node.set("punctuation", serializePunctuation(lexicon.getPunctuation()));

        // canonicalization (仅声明式配置，不包含 SyntaxTransformer)
        node.set("canonicalization", serializeCanonicalization(lexicon.getCanonicalization()));

        // messages
        node.set("messages", serializeMessages(lexicon.getMessages()));

        // overlays: 从 LexiconPlugin 的 classpath 资源嵌入
        if (plugin != null) {
            embedOverlays(node, plugin);
        }

        return node;
    }

    private ObjectNode serializePunctuation(PunctuationConfig punct) {
        ObjectNode node = mapper.createObjectNode();
        node.put("statementEnd", punct.statementEnd());
        node.put("listSeparator", punct.listSeparator());
        node.put("enumSeparator", punct.enumSeparator());
        node.put("blockStart", punct.blockStart());
        node.put("stringQuoteOpen", punct.stringQuoteOpen());
        node.put("stringQuoteClose", punct.stringQuoteClose());
        if (punct.markerOpen() != null) {
            node.put("markerOpen", punct.markerOpen());
        }
        if (punct.markerClose() != null) {
            node.put("markerClose", punct.markerClose());
        }
        return node;
    }

    private ObjectNode serializeCanonicalization(CanonicalizationConfig config) {
        ObjectNode node = mapper.createObjectNode();
        node.put("fullWidthToHalf", config.fullWidthToHalf());
        node.put("whitespaceMode", config.whitespaceMode().name());
        node.put("removeArticles", config.removeArticles());

        if (!config.articles().isEmpty()) {
            ArrayNode articles = node.putArray("articles");
            for (String article : config.articles()) {
                articles.add(article);
            }
        }

        if (!config.customRules().isEmpty()) {
            ArrayNode rules = node.putArray("customRules");
            for (CanonicalizationConfig.CanonicalizationRule rule : config.customRules()) {
                ObjectNode ruleNode = mapper.createObjectNode();
                ruleNode.put("name", rule.name());
                ruleNode.put("pattern", rule.pattern());
                ruleNode.put("replacement", rule.replacement());
                rules.add(ruleNode);
            }
        }

        if (!config.allowedDuplicates().isEmpty()) {
            ArrayNode dupes = node.putArray("allowedDuplicates");
            for (Set<SemanticTokenKind> group : config.allowedDuplicates()) {
                ArrayNode groupArr = mapper.createArrayNode();
                for (SemanticTokenKind kind : group) {
                    groupArr.add(kind.name());
                }
                dupes.add(groupArr);
            }
        }

        if (!config.compoundPatterns().isEmpty()) {
            ArrayNode patterns = node.putArray("compoundPatterns");
            for (CompoundPattern cp : config.compoundPatterns()) {
                ObjectNode cpNode = mapper.createObjectNode();
                cpNode.put("name", cp.name());
                cpNode.put("opener", cp.opener().name());
                ArrayNode ctxKw = cpNode.putArray("contextualKeywords");
                for (SemanticTokenKind kw : cp.contextualKeywords()) {
                    ctxKw.add(kw.name());
                }
                cpNode.put("closer", cp.closer().name());
                patterns.add(cpNode);
            }
        }

        // preTranslationTransformers / postTranslationTransformers:
        // 导出变换器的注册键名，供 DynamicLexicon 加载时通过 TransformerRegistry 查找
        if (!config.preTranslationTransformers().isEmpty()) {
            ArrayNode pre = node.putArray("preTranslationTransformers");
            for (var t : config.preTranslationTransformers()) {
                pre.add(TransformerRegistry.getKey(t));
            }
        }
        if (!config.postTranslationTransformers().isEmpty()) {
            ArrayNode post = node.putArray("postTranslationTransformers");
            for (var t : config.postTranslationTransformers()) {
                post.add(TransformerRegistry.getKey(t));
            }
        }

        return node;
    }

    private ObjectNode serializeMessages(ErrorMessages messages) {
        ObjectNode node = mapper.createObjectNode();
        node.put("unexpectedToken", messages.unexpectedToken());
        node.put("expectedKeyword", messages.expectedKeyword());
        node.put("undefinedVariable", messages.undefinedVariable());
        node.put("typeMismatch", messages.typeMismatch());
        node.put("unterminatedString", messages.unterminatedString());
        node.put("invalidIndentation", messages.invalidIndentation());
        return node;
    }

    private void embedOverlays(ObjectNode lexiconNode, LexiconPlugin plugin) {
        Map<String, String> overlays = plugin.getOverlayResources();
        if (overlays.isEmpty()) return;

        ObjectNode overlaysNode = lexiconNode.putObject("overlays");
        for (Map.Entry<String, String> entry : overlays.entrySet()) {
            String key = entry.getKey();
            String resourcePath = entry.getValue();
            try (InputStream is = plugin.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    System.err.printf("Overlay resource not found: %s (plugin: %s)%n",
                            resourcePath, plugin.getClass().getName());
                    continue;
                }
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JsonNode parsed = mapper.readTree(json);
                overlaysNode.set(key, parsed);
            } catch (IOException e) {
                System.err.printf("Failed to read overlay resource: %s (%s)%n",
                        resourcePath, e.getMessage());
            }
        }
    }

    private static Map<String, LexiconPlugin> discoverPluginMap() {
        Map<String, LexiconPlugin> map = new HashMap<>();
        for (LexiconPlugin plugin : ServiceLoader.load(LexiconPlugin.class)) {
            try {
                Lexicon lexicon = plugin.createLexicon();
                map.put(lexicon.getId(), plugin);
            } catch (Exception e) {
                System.err.printf("Failed to load plugin for overlay discovery: %s%n",
                        plugin.getClass().getName());
            }
        }
        return map;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
