package aster.core.identifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unicode 15.1 简单折叠：与 TS 共用的跨语言测试向量 + 辅助函数（ADR 0051 §6）。
 */
class CaseFoldTest {

    /** aster-lang-locales/casefold/test-vectors.json 原件的 sha256；副本必须逐字节一致。 */
    private static final String VECTORS_SHA256 =
        "0d8a9a43d7a7a90f1a8ccd964f868f8043dd0e5b2b9ded1ea850071799a71bb3";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static byte[] resource(String path) throws IOException {
        try (InputStream in = CaseFoldTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("缺少测试资源 " + path);
            return in.readAllBytes();
        }
    }

    static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JsonNode vectors() throws IOException {
        return MAPPER.readTree(resource("/identifier/casefold/test-vectors.json"));
    }

    /** 以码点数组还原输入，避免文件编码影响。 */
    private static String fromCodePoints(JsonNode arr) {
        StringBuilder sb = new StringBuilder();
        arr.forEach(n -> sb.appendCodePoint(n.asInt()));
        return sb.toString();
    }

    @Test
    void vectorFileMatchesLocalesOriginal() throws Exception {
        assertEquals(VECTORS_SHA256, sha256(resource("/identifier/casefold/test-vectors.json")));
    }

    @TestFactory
    Stream<DynamicTest> testVectors() throws IOException {
        JsonNode all = vectors();
        assertTrue(all.size() > 0, "向量非空");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : all) {
            String a = fromCodePoints(v.get("codepoints").get("a"));
            String b = fromCodePoints(v.get("codepoints").get("b"));
            boolean equal = v.get("equal").asBoolean();
            tests.add(DynamicTest.dynamicTest(v.get("note").asText() + ": " + a + " / " + b + " => " + equal,
                () -> assertEquals(equal, CaseFold.foldKey(a).equals(CaseFold.foldKey(b)))));
        }
        return tests.stream();
    }

    @Test
    void turkishDottedCapitalIUnchanged() {
        assertEquals("İ", CaseFold.foldKey("İ"));
    }

    @Test
    void unmappedCodePointsIncludingAstralUnchanged() {
        assertEquals("天城文𝒜", CaseFold.foldKey("天城文𝒜"));
    }

    @Test
    void astralCaseFoldsPerCodePoint() {
        assertEquals(new String(Character.toChars(0x10428)), CaseFold.foldKey(new String(Character.toChars(0x10400))));
    }

    @Test
    void nfcComposes() {
        assertEquals("é", CaseFold.nfc("é"));
    }

    @Test
    void codePointOrderNotUtf16Order() {
        String fullwidthTilde = "～";
        String u10000 = new String(Character.toChars(0x10000));
        assertTrue(CaseFold.compareCodePoints(fullwidthTilde, u10000) < 0);
        assertTrue(fullwidthTilde.compareTo(u10000) > 0, "UTF-16 单元序相反，证明确有差异");
        assertEquals(0, CaseFold.compareCodePoints("abc", "abc"));
        assertTrue(CaseFold.compareCodePoints("ab", "abc") < 0);
        assertTrue(CaseFold.compareCodePoints("abc", "ab") > 0);
    }

    @Test
    void greekFoldsEqualUnderNfcClosure() {
        assertEquals(CaseFold.foldKey("Ϊ́"), CaseFold.foldKey("ΐ"));
    }

    @Test
    void foldKeyIsAlwaysNfc() throws IOException {
        List<String> inputs = new ArrayList<>(List.of("Ϊ́", "ΐ"));
        for (JsonNode v : vectors()) {
            inputs.add(v.get("a").asText());
            inputs.add(v.get("b").asText());
        }
        for (String s : inputs) {
            String k = CaseFold.foldKey(s);
            assertEquals(CaseFold.nfc(k), k, "折叠键须对 NFC 闭合: " + s);
        }
    }

    @Test
    void doesNotUseHostLowercase() {
        // 简单折叠不展开 ß，也不受默认 Locale 影响
        assertFalse(CaseFold.foldKey("Straße").equals(CaseFold.foldKey("STRASSE")));
        assertEquals("stufe", CaseFold.foldKey("STUFE"));
    }

    @Test
    void tableHas1457SortedEntries() {
        assertEquals("15.1", CaseFold.VERSION);
        assertEquals(1457, CaseFoldTable.FROM.length);
        assertEquals(1457, CaseFoldTable.TO.length);
        for (int i = 1; i < CaseFoldTable.FROM.length; i++) {
            assertTrue(CaseFoldTable.FROM[i - 1] < CaseFoldTable.FROM[i], "码点须严格升序");
        }
    }

    /** 兄弟仓库存在时核对生成表与 locales 数据一致；不存在则跳过（构建不依赖兄弟仓库）。 */
    @Test
    void tableMatchesLocalesSourceWhenPresent() throws IOException {
        Path src = Path.of("..", "aster-lang-locales", "casefold", "casefold-15.1.json");
        assumeTrue(Files.exists(src), "兄弟仓库 aster-lang-locales 不存在");
        JsonNode map = MAPPER.readTree(src.toFile()).get("map");
        Map<Integer, Integer> expected = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = map.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            expected.put(Integer.parseInt(e.getKey(), 16), Integer.parseInt(e.getValue().asText(), 16));
        }
        Map<Integer, Integer> actual = new TreeMap<>();
        for (int i = 0; i < CaseFoldTable.FROM.length; i++) {
            actual.put(CaseFoldTable.FROM[i], CaseFoldTable.TO[i]);
        }
        assertEquals(expected, actual);
    }
}
