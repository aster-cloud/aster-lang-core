package aster.core.embed;

import aster.core.ir.CoreIrJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0056 修订 A8 第 3 条（产品裁决 2026-11-28「处处照 core」）：aster-lang-ts 照 core 收紧或放宽的六项一侧差异——构造里首字母
 * 大写的字段名、顶层省略 Define 的数据类型、字段列表里换行起头的逗号、点之后的 core 大写关键词记号（Set / Use / On / It / Map /
 * Module）、裸表达式语句（降为 {@code Let _ be …}，非名字表达式之后的成员访问与方法调用）、规则头里 with 引导的形参（E062）。
 * core 的行为不变，本测试把它钉住。共享夹具 {@code follow_core_parity.json} 与 aster-lang-ts {@code shared/follow_core_parity.json}
 * 逐字节相同，ts {@code follow-core-parity.test.ts} 按同一夹具断言：带 pf 的例两引擎 Core IR 的 positionFreeSha256 恰为 pf；
 * 带 reject 的例两引擎都拒，带 code / line / column（及 params、message）的第一条诊断逐项相同。
 */
class FollowCoreParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        int rejects = 0;
        int diagnostics = 0;
        for (JsonNode c : fixture().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertCase(c)));
            if (c.has("reject")) rejects++;
            if (c.has("code")) diagnostics++;
        }
        assertThat(tests).hasSize(105);
        assertThat(rejects).isEqualTo(40);
        assertThat(diagnostics).isEqualTo(23);
        return tests.stream();
    }

    private static void assertCase(JsonNode c) throws Exception {
        String source = c.get("source").asText();
        String locale = c.has("locale") ? c.get("locale").asText() : "en-US";
        if (!c.has("reject")) {
            String ir = CoreIrJson.serialize(InProcessCnlParser.parse(source, locale).core());
            assertThat(PolicySourceCompiler.positionFreeSha256(ir)).isEqualTo(c.get("pf").asText());
            return;
        }
        CnlErrorListener.Diagnostic first;
        try {
            InProcessCnlParser.parse(source, locale);
            throw new AssertionError("应拒绝：" + c.get("name").asText());
        } catch (InProcessCnlParser.CnlParseException e) {
            first = e.getDiagnostics().isEmpty() ? null : e.getDiagnostics().get(0);
        }
        if (!c.has("code")) return;
        assertThat(first).isNotNull();
        assertThat(List.of(first.code(), first.line(), first.column()))
            .isEqualTo(List.of(c.get("code").asText(), c.get("line").asInt(), c.get("column").asInt()));
        if (c.has("params")) {
            Map<String, String> want = new LinkedHashMap<>();
            c.get("params").fields().forEachRemaining(p -> want.put(p.getKey(), p.getValue().asText()));
            assertThat(first.params()).isEqualTo(want);
        }
        if (c.has("message")) {
            assertThat(first.message()).isEqualTo(c.get("message").asText());
        }
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = FollowCoreParityTest.class.getResourceAsStream("/diagnostics/follow_core_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
