package aster.core.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显示排版 L1–L7 共享向量（ADR 0059 修订 4 §4、D9）：{@code src/test/resources/layout/*.json} 是 aster-lang-test
 * {@code conformance/package-v1/vectors/} 的逐字节副本，sha256 钉死（与 aster-lang-ts {@code shared/layout-vectors/} 相同）；
 * 源文件变化时须同步复制并更新这里的哈希。
 */
class LayoutVectorsTest {

    /** 五个向量文件的 sha256（aster-lang-test#234；display-escape.json 为 ADR 0059 修订 5）。 */
    static final Map<String, String> VECTOR_SHA256 = Map.of(
        "layout.json", "cc51f78a44f87d3cf0c03543eeeb9d68015fd922923094c0aad76fe64a8aa79b",
        "display-reconstruct.json", "4e57005c1c69af329001537f2b043d492bcd4a1dc02a1f7ecaeba4c543e32537",
        "display-padding.json", "9977a80d16d604cbb7de1bafa695799cb48a36c33ec9936aa2a61b7c324ee65d",
        "keyword-spellings.json", "467b1075dd67e2e7249d50ae3b47eb5c1f00a2f1827dcf501016e97ef906fd3f",
        "display-escape.json", "8e7b1e9afba5a443f74b16e154418fa2e3bb9a142322114165c63b5aac94d5fb");

    static byte[] resource(String name) throws Exception {
        try (InputStream in = LayoutVectorsTest.class.getResourceAsStream("/layout/" + name)) {
            if (in == null) {
                throw new IllegalStateException("缺少测试资源 /layout/" + name);
            }
            return in.readAllBytes();
        }
    }

    static JsonNode vectors(String name) throws Exception {
        return new ObjectMapper().readTree(resource(name));
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.textValue()));
        return out;
    }

    @Test
    void fixturesAreVerbatimCopies() throws Exception {
        for (Map.Entry<String, String> e : VECTOR_SHA256.entrySet()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resource(e.getKey()));
            assertEquals(e.getValue(), HexFormat.of().formatHex(digest), e.getKey() + " 与 aster-lang-test 真相源不同，重新复制");
        }
    }

    @TestFactory
    Stream<DynamicTest> layoutVectors() throws Exception {
        JsonNode doc = vectors("layout.json");
        assertTrue(doc.path("cases").size() > 60, "layout 向量过少");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : doc.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("id").asText() + " → " + c.path("expect").asText(), () -> {
                byte[] layoutBytes = c.path("layoutText").textValue().getBytes(StandardCharsets.UTF_8);
                String display = c.has("displayText") ? c.path("displayText").textValue() : displayOf(layoutBytes);
                String got = "ok";
                PolicyLayout layout = null;
                try {
                    // sourceB64：policy.aster 的原始字节（非法 UTF-8），走字节入口；否则走已解码文本入口
                    layout = c.has("sourceB64")
                        ? PackageLayoutRules.checkPackage(layoutBytes, display.getBytes(StandardCharsets.UTF_8),
                            java.util.Base64.getDecoder().decode(c.path("sourceB64").textValue()), c.path("layoutVersion").asInt(1),
                            strings(c.path("vocabularyLocalized")), strings(c.path("literalKeywords")), strings(c.path("operatorSpellings")))
                        : PackageLayoutRules.checkPackage(layoutBytes, display.getBytes(StandardCharsets.UTF_8),
                            c.path("source").textValue(), c.path("layoutVersion").asInt(1),
                            strings(c.path("vocabularyLocalized")), strings(c.path("literalKeywords")), strings(c.path("operatorSpellings")));
                } catch (LayoutException e) {
                    got = e.rule();
                }
                assertEquals(c.path("expect").asText(), got);
                if (c.has("serialized")) {
                    assertEquals(c.path("serialized").textValue(), LayoutFormat.serialize(layout));
                }
            }));
        }
        return tests.stream();
    }

    /** 向量没给 displayText 时取 toDisplay（排版本身读不出时为空串，规则在 L1 先失败）。 */
    private static String displayOf(byte[] layoutBytes) {
        try {
            return LayoutFormat.readLayout(new LayoutFormat.StrictJson(new String(layoutBytes, StandardCharsets.UTF_8)).document()).toDisplay();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
