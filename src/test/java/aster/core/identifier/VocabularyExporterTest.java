package aster.core.identifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VocabularyExporter 单元测试。
 *
 * 验证导出 JSON 格式正确、校验和稳定。
 */
class VocabularyExporterTest {

    private VocabularyRegistry registry;
    private VocabularyExporter exporter;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = VocabularyRegistry.getInstance();
        registry.reset();
        exporter = new VocabularyExporter();
    }

    @Test
    @DisplayName("导出 JSON 包含必需的顶层字段")
    void exportContainsRequiredFields() throws IOException {
        StringWriter writer = new StringWriter();
        exporter.export("1.0.0", registry, writer);
        JsonNode root = mapper.readTree(writer.toString());

        assertTrue(root.has("version"), "应包含 version 字段");
        assertTrue(root.has("generatedAt"), "应包含 generatedAt 字段");
        assertTrue(root.has("vocabularies"), "应包含 vocabularies 字段");
        assertTrue(root.has("checksum"), "应包含 checksum 字段");

        assertEquals("1.0.0", root.get("version").asText());
        assertTrue(root.get("vocabularies").isObject());
        assertEquals(64, root.get("checksum").asText().length(), "SHA-256 应为 64 字符十六进制");
    }

    @Test
    @DisplayName("导出包含所有 SPI 注册的词汇表")
    void exportContainsAllSpiVocabularies() throws IOException {
        StringWriter writer = new StringWriter();
        exporter.export("1.0.0", registry, writer);
        JsonNode root = mapper.readTree(writer.toString());
        JsonNode vocabs = root.get("vocabularies");

        // SPI 应提供 3 语种 × 2 领域 = 6 个词汇表
        assertTrue(vocabs.size() >= 6, "应至少有 6 个词汇表，实际: " + vocabs.size());
        assertTrue(vocabs.has("insurance.auto:zh-CN"));
        assertTrue(vocabs.has("finance.loan:zh-CN"));
        assertTrue(vocabs.has("insurance.auto:en-US"));
        assertTrue(vocabs.has("finance.loan:en-US"));
        assertTrue(vocabs.has("insurance.auto:de-DE"));
        assertTrue(vocabs.has("finance.loan:de-DE"));
    }

    @Test
    @DisplayName("每个词汇表包含完整结构")
    void eachVocabularyHasCompleteStructure() throws IOException {
        StringWriter writer = new StringWriter();
        exporter.export("1.0.0", registry, writer);
        JsonNode root = mapper.readTree(writer.toString());
        JsonNode vocabs = root.get("vocabularies");

        vocabs.fieldNames().forEachRemaining(key -> {
            JsonNode vocab = vocabs.get(key);
            assertTrue(vocab.has("id"), key + " 应包含 id");
            assertTrue(vocab.has("name"), key + " 应包含 name");
            assertTrue(vocab.has("locale"), key + " 应包含 locale");
            assertTrue(vocab.has("version"), key + " 应包含 version");
            assertTrue(vocab.has("structs"), key + " 应包含 structs");
            assertTrue(vocab.has("fields"), key + " 应包含 fields");
            assertTrue(vocab.has("functions"), key + " 应包含 functions");
            assertTrue(vocab.has("enumValues"), key + " 应包含 enumValues");
        });
    }

    @Test
    @DisplayName("校验和可独立验证")
    void checksumIsVerifiable() throws IOException, NoSuchAlgorithmException {
        StringWriter writer = new StringWriter();
        exporter.export("1.0.0", registry, writer);
        JsonNode root = mapper.readTree(writer.toString());

        String exportedChecksum = root.get("checksum").asText();
        JsonNode vocabsNode = root.get("vocabularies");

        // 用紧凑格式重新计算校验和（与导出器一致）
        ObjectMapper compactMapper = new ObjectMapper();
        String vocabsJson = compactMapper.writeValueAsString(vocabsNode);
        String computedChecksum = sha256(vocabsJson);

        assertEquals(exportedChecksum, computedChecksum, "校验和应可独立验证");
    }

    @Test
    @DisplayName("两次导出产生相同的校验和（字段排序稳定）")
    void checksumIsStableAcrossExports() throws IOException {
        StringWriter writer1 = new StringWriter();
        exporter.export("1.0.0", registry, writer1);
        JsonNode root1 = mapper.readTree(writer1.toString());

        StringWriter writer2 = new StringWriter();
        exporter.export("1.0.0", registry, writer2);
        JsonNode root2 = mapper.readTree(writer2.toString());

        assertEquals(
            root1.get("checksum").asText(),
            root2.get("checksum").asText(),
            "相同数据的两次导出应产生相同的校验和"
        );
    }

    @Test
    @DisplayName("词汇表键按 domain:locale 排序")
    void vocabularyKeysAreSorted() throws IOException {
        StringWriter writer = new StringWriter();
        exporter.export("1.0.0", registry, writer);
        JsonNode root = mapper.readTree(writer.toString());
        JsonNode vocabs = root.get("vocabularies");

        String prev = null;
        var it = vocabs.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (prev != null) {
                assertTrue(key.compareTo(prev) >= 0,
                    "键应按字典序排列: " + prev + " → " + key);
            }
            prev = key;
        }
    }

    private static String sha256(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
