package aster.core.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CanonicalJson.canonicalWithHash 单元测试（M2 回放 payload）：
 * 一次序列化同时拿 canonical 串 + hash，二者必须与分别调用 canonicalJson / canonicalHash 完全一致，
 * 且 hash(串) == 返回的 hash（cloud 直接 re-hash 校验的前提）。
 */
class CanonicalWithHashTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("canonicalWithHash 的串 == canonicalJson，hash == canonicalHash（一次序列化等价两次）")
    void pairEqualsSeparateCalls() throws Exception {
        var node = MAPPER.readTree("{\"b\":2,\"a\":1,\"nested\":{\"z\":true}}");
        var ctx = CanonicalJson.TypeContext.empty();

        CanonicalJson.CanonicalPair pair = CanonicalJson.canonicalWithHash(node, ctx);

        assertEquals(CanonicalJson.canonicalJson(node, ctx), pair.canonical(),
                "pair.canonical 必须与 canonicalJson 逐字节一致");
        assertEquals(CanonicalJson.canonicalHash(node, ctx), pair.hash(),
                "pair.hash 必须与 canonicalHash 一致");
    }

    @Test
    @DisplayName("★hash(返回串) == 返回 hash（cloud 侧 sha256(version+\\n+串) 校验的核心契约）")
    void hashOfReturnedStringMatchesReturnedHash() throws Exception {
        var node = MAPPER.readTree("[1,\"two\",{\"k\":null}]");
        CanonicalJson.CanonicalPair pair = CanonicalJson.canonicalWithHash(node);

        String recomputed = sha256Hex(CanonicalJson.CANONICALIZATION_VERSION + "\n" + pair.canonical());
        assertEquals(pair.hash(), recomputed,
                "对返回的 canonical 串直接 sha256(version+\\n+串) 必须等于返回的 hash——cloud 不 re-canonicalize 也能校验");
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(d);
    }
}
