package aster.core.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TS↔Java canonical JSON parity gate（P0-A 地基，ADR 0030 附录 A.2）。
 *
 * <p>共享 fixture {@code canonical-json-fixtures.json} 由 aster-cloud 的 TS 参考实现
 * （{@code src/lib/canonical-json.ts}，已穷尽单测）生成，含每个用例的 input + 期望
 * canonical 字符串 + 期望 hash。本测试用 Java {@link CanonicalJson} 跑同样 input，断言
 * 产出与 fixture **字节级一致** —— 证明两引擎对同一决策输入产同一 canonical/hash，回归
 * 工具的 old（Java 权威）↔ new toolchain hash 比对才不会因表示差异误报/漏报。
 *
 * <p>fixture 是两侧共享同源副本（cloud 侧 {@code src/lib/__fixtures__/} 与本 test resources
 * 字节一致）。TS 侧另有测试断言其产出 == fixture，Java 侧本测试断言其产出 == fixture，
 * 传递即 TS == Java。
 */
@DisplayName("Canonical JSON TS↔Java parity")
class CanonicalJsonParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Fixture(String name, JsonNode input, List<String> decimalPaths, String canonical, String hash) {}

    static Stream<Fixture> fixtures() throws Exception {
        try (InputStream in = CanonicalJsonParityTest.class.getResourceAsStream(
                "/canonical/canonical-json-fixtures.json")) {
            assertNotNull(in, "共享 fixture 缺失: /canonical/canonical-json-fixtures.json");
            JsonNode root = MAPPER.readTree(in);
            assertEquals("aster-canonical-json/v1", root.get("version").asText(),
                    "fixture 版本须与 CanonicalJson.CANONICALIZATION_VERSION 一致");
            List<Fixture> out = new ArrayList<>();
            for (JsonNode c : root.get("cases")) {
                List<String> paths = new ArrayList<>();
                c.get("decimalPaths").forEach(p -> paths.add(p.asText()));
                out.add(new Fixture(
                        c.get("name").asText(),
                        c.get("input"),
                        paths,
                        c.get("canonical").asText(),
                        c.get("hash").asText()));
            }
            return out.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("Java canonical 产出与 TS fixture 字节级一致")
    void javaMatchesTsFixture(Fixture f) {
        CanonicalJson.TypeContext ctx = CanonicalJson.TypeContext.of(f.decimalPaths().toArray(new String[0]));

        String javaCanonical = CanonicalJson.canonicalJson(f.input(), ctx);
        assertEquals(f.canonical(), javaCanonical,
                "canonical 字符串不一致（TS vs Java）用例=" + f.name());

        String javaHash = CanonicalJson.canonicalHash(f.input(), ctx);
        assertEquals(f.hash(), javaHash,
                "canonical hash 不一致（TS vs Java）用例=" + f.name());
    }

    @Test
    @DisplayName("fixture 非空（防 corpus 删除后假通过）")
    void fixtureNotEmpty() throws Exception {
        long n = fixtures().count();
        org.junit.jupiter.api.Assertions.assertTrue(n >= 20,
                "共享 fixture 用例数异常偏少(" + n + ")，疑似 corpus 损坏");
    }

    record NegativeFixture(String name, JsonNode input, List<String> decimalPaths, String expectedReason) {}

    static Stream<NegativeFixture> negativeFixtures() throws Exception {
        try (InputStream in = CanonicalJsonParityTest.class.getResourceAsStream(
                "/canonical/canonical-json-fixtures.json")) {
            assertNotNull(in);
            JsonNode root = MAPPER.readTree(in);
            List<NegativeFixture> out = new ArrayList<>();
            for (JsonNode c : root.get("negativeCases")) {
                List<String> paths = new ArrayList<>();
                c.get("decimalPaths").forEach(p -> paths.add(p.asText()));
                out.add(new NegativeFixture(
                        c.get("name").asText(), c.get("input"), paths, c.get("expectedReason").asText()));
            }
            return out.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativeFixtures")
    @DisplayName("Java 对非法输入的拒绝原因与 TS 一致")
    void javaRejectionMatchesTs(NegativeFixture f) {
        CanonicalJson.TypeContext ctx = CanonicalJson.TypeContext.of(f.decimalPaths().toArray(new String[0]));
        CanonicalJson.CanonicalJsonException ex = org.junit.jupiter.api.Assertions.assertThrows(
                CanonicalJson.CanonicalJsonException.class,
                () -> CanonicalJson.canonicalJson(f.input(), ctx),
                "Java 应拒绝非法输入，用例=" + f.name());
        assertEquals(f.expectedReason(), ex.reason().name(),
                "拒绝原因不一致（TS vs Java）用例=" + f.name());
    }
}
