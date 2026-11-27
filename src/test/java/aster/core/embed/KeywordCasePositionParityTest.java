package aster.core.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 关键词大小写（ADR 0059 A4 §1.1）在列表分隔符与构造位置（aster-lang-ts#411，产品裁决 2026-11-28）：形参之间的 and（规则头与
 * lambda）、变体之间的 or / and、apply 的 to、构造里 and 之后的 set 不区分大小写；字段列表里换行起头（不缩进）的 and 与 has 构造
 * （与 with 同义）两引擎都接受。共享夹具 {@code keyword_case_position_parity.json} 与 aster-lang-ts
 * {@code shared/keyword_case_position_parity.json} 逐字节相同，ts {@code keyword-case-position-parity.test.ts} 按同一夹具断言：
 * 带 pf 的例两引擎的 Core IR positionFreeSha256 都恰为 pf。
 */
class KeywordCasePositionParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        int rejects = 0;
        for (JsonNode c : fixture().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertCase(c)));
            if (c.has("reject")) rejects++;
        }
        assertThat(tests).hasSize(98);
        assertThat(rejects).isEqualTo(19);
        return tests.stream();
    }

    private static void assertCase(JsonNode c) throws Exception {
        String source = c.get("source").asText();
        if (c.has("reject")) {
            assertThrows(InProcessCnlParser.CnlParseException.class, () -> pfOf(source));
            return;
        }
        String pf = pfOf(source);
        assertThat(pf).isEqualTo(c.get("pf").asText());
        if (c.has("equivalent")) {
            assertThat(pf).isEqualTo(pfOf(c.get("equivalent").asText()));
        }
    }

    private static String pfOf(String source) throws Exception {
        return PolicySourceCompiler.positionFreeSha256(PolicySourceCompiler.compileCanonical(source, "en-US", null, null, false));
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = KeywordCasePositionParityTest.class.getResourceAsStream("/diagnostics/keyword_case_position_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
