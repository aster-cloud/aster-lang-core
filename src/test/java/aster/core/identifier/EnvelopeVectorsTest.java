package aster.core.identifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * envelope 共享向量（ADR 0051 §7）的 Java 侧核对。
 *
 * <p>fixture 是 aster-lang-test {@code corpus/shared-vectors/envelope-vectors.json} 的逐字节副本（sha256 钉住，
 * 源文件变化时须同步复制并更新 {@link #VECTORS_SHA256}）。每条向量带源词汇 {@code vocabulary}：
 * Java 的 {@link VocabularyScopes#canonicalJson()} 必须与向量中的 scopesJson（由 TS canonicalScopesJson 生成）
 * 逐字节一致；按长度前缀分帧规则重算的 5 段 envelope 哈希必须等于 expectedSha256。
 */
class EnvelopeVectorsTest {

    private static final String VECTORS_SHA256 = "cc743299e6f0bde27b650337f0ebb70652b9b8c80ed6278d764b8c1416b8c563";
    private static final String[] FIELDS = {"content", "aliasSetJson", "locale", "toolchainId", "scopesJson"};

    private static byte[] vectorsBytes() throws Exception {
        return CaseFoldTest.resource("/shared-vectors/envelope-vectors.json");
    }

    private static JsonNode vectors() throws Exception {
        return new ObjectMapper().readTree(vectorsBytes());
    }

    @Test
    void fixtureIsVerbatimCopy() throws Exception {
        assertEquals(VECTORS_SHA256, CaseFoldTest.sha256(vectorsBytes()));
    }

    @Test
    void canonicalJsonMatchesScopesJson() throws Exception {
        int withVocabulary = 0;
        for (JsonNode v : vectors()) {
            JsonNode vocabulary = v.get("vocabulary");
            if (vocabulary.isNull()) {
                // 无词汇：scopesJson 为空串，不序列化空 scopes
                assertEquals("", v.get("scopesJson").asText());
                continue;
            }
            withVocabulary++;
            DomainVocabulary dv = VocabularyLoader.loadFromString(vocabulary.toString());
            assertEquals(v.get("scopesJson").asText(), VocabularyScopes.build(dv).canonicalJson(),
                "vocabulary " + vocabulary.path("id").asText());
        }
        assertTrue(withVocabulary >= 2, "至少两条带词汇的向量");
    }

    @Test
    void envelopeHashMatchesExpected() throws Exception {
        for (JsonNode v : vectors()) {
            assertEquals(v.get("expectedSha256").asText(), envelope(v));
        }
    }

    /** 每段写 {@code <UTF-8 字节数>:<字节>|}，段序固定，SHA-256 小写十六进制。 */
    private static String envelope(JsonNode v) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String field : FIELDS) {
            byte[] b = v.get(field).asText().getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(b);
            digest.update((byte) '|');
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
