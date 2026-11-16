package aster.core.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产编译路径与包格式 v1 黄金包的字节一致性（ADR 0059 P1 T6）。
 *
 * <p>夹具来自兄弟仓 aster-lang-test {@code conformance/package-v1/}（唯一真相源，#216）。B 包的
 * {@code coreIr.sha256} 是平台（aster-api {@code PolicyCompiler}）编出的 IR 字节哈希，{@code positionFreeSha256}
 * 是去掉 origin 列号后的投影哈希（修订 1 A2）：本类的 {@link PolicySourceCompiler#compileCanonical} 必须两者都重现。
 * 缺夹具即失败，不静默跳过。
 */
class PolicySourceCompilerCanonicalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONFORMANCE = resolveConformance();

    /** 兼容两种布局：CI 把兄弟仓检出到工作区内（./aster-lang-test），本地为并列目录（../aster-lang-test）。 */
    private static Path resolveConformance() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path rel = Path.of("conformance", "package-v1");
        for (Path base : new Path[]{cwd.resolve("aster-lang-test"), cwd.resolve("..").resolve("aster-lang-test")}) {
            Path p = base.resolve(rel).normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return cwd.resolve("..").resolve("aster-lang-test").resolve(rel).normalize();
    }

    @Test
    void conformanceFixturesExist() {
        assertTrue(Files.isDirectory(CONFORMANCE.resolve("packages")),
            "缺少 aster-lang-test conformance/package-v1 夹具：" + CONFORMANCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"b-credit-limit", "b-vocab-de", "b-zh", "b-alias"})
    void goldenBPackagesRecompileToPlatformBytes(String name) throws Exception {
        Path pkg = CONFORMANCE.resolve("packages").resolve(name);
        JsonNode manifest = MAPPER.readTree(Files.readString(pkg.resolve("manifest.json")));
        JsonNode source = manifest.path("source");
        String coreJson = PolicySourceCompiler.compileCanonical(
            Files.readString(pkg.resolve("policy/policy.aster")),
            source.path("locale").asText(),
            PolicySourceCompiler.vocabularyScopesOf(readOptional(pkg.resolve("policy/compile/vocabulary.json"))),
            PolicySourceCompiler.parseAliasSetJson(readOptional(pkg.resolve("policy/compile/alias-set.json"))),
            source.path("allowStructural").asBoolean());

        assertEquals(manifest.path("coreIr").path("sha256").asText(), sha256Hex(coreJson), name + " 的 IR 字节哈希");
        assertEquals(manifest.path("coreIr").path("positionFreeSha256").asText(),
            PolicySourceCompiler.positionFreeSha256(coreJson), name + " 的投影哈希");
    }

    @Test
    void positionFreeVectors() throws Exception {
        JsonNode vectors = MAPPER.readTree(Files.readString(CONFORMANCE.resolve("vectors/position-free-hash.json")))
            .path("vectors");
        assertTrue(vectors.size() >= 3, "position-free-hash 向量过少");
        for (JsonNode v : vectors) {
            String coreJson = v.path("coreJson").asText();
            String name = v.path("name").asText();
            assertEquals(v.path("sha256").asText(), sha256Hex(coreJson), name + " sha256");
            assertEquals(v.path("positionFreeSha256").asText(), PolicySourceCompiler.positionFreeSha256(coreJson),
                name + " positionFreeSha256");
        }
    }

    @Test
    void aliasSetJsonIsFailClosed() {
        assertEquals(Map.of(), PolicySourceCompiler.parseAliasSetJson(null));
        assertEquals(Map.of(), PolicySourceCompiler.parseAliasSetJson("null"));
        assertEquals(List.of("multiplied by"),
            PolicySourceCompiler.parseAliasSetJson("{\"TIMES\":[\"multiplied by\"]}")
                .get(aster.core.lexicon.SemanticTokenKind.TIMES));
        assertThrows(IllegalStateException.class, () -> PolicySourceCompiler.parseAliasSetJson("{\"NOPE\":[\"x y\"]}"));
        assertThrows(IllegalStateException.class, () -> PolicySourceCompiler.parseAliasSetJson("{"));
    }

    @Test
    void invalidAliasSetFailsCompilation() {
        String src = "Module demo.\n\nRule f given x as Int, produce Int:\n  Return x times 2.\n";
        assertThrows(InProcessCnlParser.CnlParseException.class, () -> PolicySourceCompiler.compileCanonical(
            src, "en-US", null, Map.of(aster.core.lexicon.SemanticTokenKind.TIMES, List.of("mal")), false));
    }

    @Test
    void positionFreeRejectsNonIr() {
        assertThrows(aster.core.ir.CoreIrFormatException.class, () -> PolicySourceCompiler.positionFreeSha256("{}"));
    }

    private static String readOptional(Path p) throws Exception {
        return Files.exists(p) ? Files.readString(p) : null;
    }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
