package aster.core.identifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 将所有已注册的领域词汇表导出为 JSON 格式。
 * <p>
 * 输出的 JSON 可供 aster-lang-ts 代码生成脚本消费，
 * 确保跨项目的领域词汇表始终与 Java 真源保持一致。
 * <p>
 * 复用 {@link VocabularyLoader#toJson(DomainVocabulary, boolean)} 进行序列化，
 * 确保导出格式与加载格式一致。
 */
public final class VocabularyExporter {

    private final ObjectMapper mapper;

    public VocabularyExporter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 导出所有已注册的领域词汇表到 JSON。
     * <p>
     * 输出格式：
     * <pre>
     * {
     *   "version": "...",
     *   "generatedAt": "...",
     *   "vocabularies": {
     *     "insurance.auto:zh-CN": { ... },
     *     ...
     *   },
     *   "checksum": "sha256-hex"
     * }
     * </pre>
     *
     * @param version  版本号
     * @param registry 词汇表注册中心
     * @param writer   输出目标
     */
    public void export(String version, VocabularyRegistry registry, Writer writer) throws IOException {
        // 动态收集所有已注册的词汇表，按 domain:locale 排序
        List<DomainVocabulary> vocabularies = registry.listAll().stream()
            .sorted(Comparator.comparing(v -> v.id() + ":" + v.locale()))
            .toList();

        if (vocabularies.isEmpty()) {
            System.err.println("警告: 未发现任何已注册的领域词汇表，请确认 SPI 插件配置正确");
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("version", version);
        root.put("generatedAt", Instant.now().toString());

        ObjectNode vocabsNode = root.putObject("vocabularies");
        for (DomainVocabulary vocab : vocabularies) {
            String key = vocab.id() + ":" + vocab.locale();
            // 复用 VocabularyLoader 的序列化，确保格式一致
            String vocabJson = VocabularyLoader.toJson(vocab, false);
            vocabsNode.set(key, mapper.readTree(vocabJson));
        }

        // 对 vocabularies 内容计算 SHA-256 校验和（使用紧凑格式，与 TS JSON.stringify 一致）
        ObjectMapper compactMapper = new ObjectMapper();
        String vocabsJson = compactMapper.writeValueAsString(vocabsNode);
        root.put("checksum", sha256(vocabsJson));

        mapper.writeValue(writer, root);
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
