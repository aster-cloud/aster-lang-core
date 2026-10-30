package aster.core.dualengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * parity CLI 输出诊断位置 {@code locs: [{code, line, col}]}（aster-lang-test#174 子项 2）。
 *
 * <p>parity 门对 meta 写了 {@code compareDiagnosticLines} 的样本比较两侧「码@起始行」，Java 侧缺 locs 判
 * {@code locs-missing}。locs 与 diagnostics / codes 一一对应、顺序相同。
 */
class CoreIrFingerprintCliLocsTest {

    private static String resource(String name) throws URISyntaxException {
        Path p = Paths.get(CoreIrFingerprintCliLocsTest.class.getResource("/dualengine/locs/" + name).toURI());
        return p.toString();
    }

    @Test
    void resolveFailureCarriesLocsAlignedWithCodes() throws Exception {
        JsonNode rec = CoreIrFingerprintCli.emitOne(
            new CoreIrFingerprintCli.Request(resource("e807.aster"), "de-DE", resource("e807.vocab.json")));

        assertFalse(rec.path("ok").asBoolean(true), rec.toString());
        assertEquals("resolve", rec.path("stage").asText(), rec.toString());
        JsonNode codes = rec.path("codes");
        JsonNode locs = rec.path("locs");
        assertTrue(locs.isArray(), "名称解析失败时必须输出 locs：" + rec);
        assertEquals(codes.size(), locs.size(), rec.toString());
        assertEquals("E807", locs.get(0).path("code").asText());
        // 第二个解析到 Driver 的声明（第 5 行 Definiere Driver）
        assertEquals(5, locs.get(0).path("line").asInt(), rec.toString());
        assertEquals(1, locs.get(0).path("col").asInt(), rec.toString());
    }

    @Test
    void successWithoutDiagnosticsCarriesEmptyLocs() throws Exception {
        JsonNode rec = CoreIrFingerprintCli.emitOne(
            new CoreIrFingerprintCli.Request(resource("ok.aster"), "de-DE", resource("e807.vocab.json")));

        assertTrue(rec.path("ok").asBoolean(false), rec.toString());
        assertTrue(rec.path("locs").isArray(), "成功时同样输出 locs（可为空）：" + rec);
        assertEquals(rec.path("diagnostics").size(), rec.path("locs").size());
    }
}
