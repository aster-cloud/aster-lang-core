package aster.core.lowering;

import aster.core.typecheck.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lowering 诊断码 E600–E606 双引擎 parity（core 侧，aster-lang-ts#302）。
 *
 * <p>夹具 {@code diagnostics/lowering_diagnostics_parity.json} 与 aster-lang-ts 的
 * {@code shared/lowering_diagnostics_parity.json} byte-identical（由 ts 侧断言）。ts 侧按夹具 trigger
 * 把合法 AST 的节点改成未知种类触发守卫；Java 侧 {@link CoreLowering} 对 sealed AST 做穷尽 switch，
 * 没有可达的发出点（夹具里所有 trigger 都是 ts 结构上才有的守卫），因此本测试守「渲染 parity」：
 * 每例的码存在于码表、category=lowering、用 params 渲染模板得到与夹具相同的英文 message——
 * aster-api 按码 + params 渲染 ts 产出的 lowering 诊断时，两端文本一致。
 */
class LoweringDiagnosticsParityTest {

    private static JsonNode fixture() throws Exception {
        try (InputStream in = LoweringDiagnosticsParityTest.class.getResourceAsStream("/diagnostics/lowering_diagnostics_parity.json")) {
            assertNotNull(in, "diagnostics/lowering_diagnostics_parity.json 应在 test classpath 中");
            return new ObjectMapper().readTree(in);
        }
    }

    private static ErrorCode byCode(String code) {
        return Arrays.stream(ErrorCode.values())
            .filter(e -> e.code().equals(code))
            .findFirst()
            .orElseThrow(() -> new AssertionError("码表中没有 " + code));
    }

    private static Map<String, Object> params(JsonNode node) {
        Map<String, Object> out = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> sharedFixtureRendersIdentically() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                assertEquals(1, c.get("codes").size(), "lowering 守卫一次只抛一条诊断");
                ErrorCode code = byCode(c.get("codes").get(0).get(0).asText());
                assertEquals(ErrorCode.Category.LOWERING, code.category());
                assertEquals(ErrorCode.Severity.ERROR, code.severity());
                Map<String, Object> params = params(c.get("params"));
                // 模板占位符集合必须与夹具 params 键一致（params 值 "*" 表示 ts 侧只断言键存在）
                Set<String> placeholders = new HashSet<>();
                var m = java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(code.messageTemplate());
                while (m.find()) {
                    placeholders.add(m.group(1));
                }
                assertEquals(placeholders, params.keySet(), "params 键应与模板占位符一致");
                if (c.hasNonNull("message")) {
                    assertEquals(c.get("message").asText(), code.render(params), "按 params 渲染模板应得到夹具 message");
                }
            }));
        }
        return tests.stream();
    }

    @Test
    void everyLoweringCodeHasAFixture() throws Exception {
        Set<String> covered = new HashSet<>();
        fixture().get("cases").forEach(c -> c.get("codes").forEach(p -> covered.add(p.get(0).asText())));
        List<String> lowering = Arrays.stream(ErrorCode.values())
            .filter(e -> e.category() == ErrorCode.Category.LOWERING)
            .map(ErrorCode::code)
            .sorted()
            .toList();
        assertEquals(List.of("E600", "E601", "E602", "E603", "E604", "E605", "E606"), lowering);
        assertEquals(List.of(), lowering.stream().filter(code -> !covered.contains(code)).toList());
    }

    @Test
    void javaLoweringHasNoReachableLoweringGuard() {
        // Java 侧 CoreLowering 不抛 lowering 诊断：AST 为 sealed 记录，穷尽 switch 在编译期保证。
        // 本断言锁定「夹具里每例都是 ts 侧守卫」这一前提；若将来 Java 侧新增发出点，须改为触发式断言。
        assertTrue(Arrays.stream(ErrorCode.values())
            .filter(e -> e.category() == ErrorCode.Category.LOWERING)
            .allMatch(e -> e.code().compareTo("E600") >= 0 && e.code().compareTo("E606") <= 0));
    }
}
