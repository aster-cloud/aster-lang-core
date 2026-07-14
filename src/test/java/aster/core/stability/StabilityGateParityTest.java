package aster.core.stability;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TS↔Java StabilityGate parity gate（P0-C，ADR 0031 M1 exit 硬门）。
 *
 * <p>共享 fixture {@code stability-gate-fixtures.json} 由 aster-lang-ts 的 TS 参考实现
 * （{@code src/stability/stability_gate.ts}，18 测）生成，含每个 {@code .aster} 源码 +
 * 期望 featureId multiset + nodeKind multiset。本测试把同一源码经 Java 完整管线
 * （Canonicalize → ANTLR → AstBuilder → CoreLowering）lower 到 CoreModel，跑 Java
 * {@link StabilityGate}，断言产出的 featureId/nodeKind 集与 fixture 一致 —— 证明两引擎
 * 对同一源码检出同一组 Experimental 特性（Experimental 边界跨引擎一致，才是可信门禁）。
 */
@DisplayName("StabilityGate TS↔Java parity")
class StabilityGateParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Fixture(String name, String src, List<String> features, List<String> nodeKinds) {}

    static Stream<Fixture> fixtures() throws Exception {
        try (InputStream in = StabilityGateParityTest.class.getResourceAsStream(
                "/stability/stability-gate-fixtures.json")) {
            assertNotNull(in, "共享 fixture 缺失: /stability/stability-gate-fixtures.json");
            JsonNode root = MAPPER.readTree(in);
            assertEquals("stability-gate/v1", root.get("version").asText());
            List<Fixture> out = new ArrayList<>();
            for (JsonNode c : root.get("cases")) {
                out.add(new Fixture(
                        c.get("name").asText(),
                        c.get("src").asText(),
                        jsonArrayToSortedList(c.get("features")),
                        jsonArrayToSortedList(c.get("nodeKinds"))));
            }
            return out.stream();
        }
    }

    private static List<String> jsonArrayToSortedList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        Collections.sort(out);
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("Java StabilityGate 检出的 feature/nodeKind 集与 TS fixture 一致")
    void javaMatchesTsFixture(Fixture f) {
        CoreModel.Module core = compileToCore(f.src());
        List<StabilityGate.Diagnostic> diags = StabilityGate.scan(core, StabilityGate.Options.warnMode());

        List<String> features = new ArrayList<>();
        List<String> nodeKinds = new ArrayList<>();
        for (StabilityGate.Diagnostic d : diags) {
            features.add(d.featureId().id());
            nodeKinds.add(d.nodeKind());
        }
        Collections.sort(features);
        Collections.sort(nodeKinds);

        assertEquals(f.features(), features,
                "featureId 集不一致（TS vs Java）用例=" + f.name());
        assertEquals(f.nodeKinds(), nodeKinds,
                "nodeKind 集不一致（TS vs Java）用例=" + f.name());
    }

    @Test
    @DisplayName("fixture 非空（防 corpus 删除后假通过）")
    void fixtureNotEmpty() throws Exception {
        long n = fixtures().count();
        assertTrue(n >= 8, "fixture 用例数异常偏少(" + n + ")");
    }

    @Test
    @DisplayName("strict 语义：severity 恒 warning，strict 走 blocking")
    void strictSemantics() {
        CoreModel.Module core = compileToCore("""
                Module test.strict.

                @deprecated
                Rule oldRule, produce Text:
                  Return "x".
                """);
        List<StabilityGate.Diagnostic> warn = StabilityGate.scan(core, StabilityGate.Options.warnMode());
        List<StabilityGate.Diagnostic> strict = StabilityGate.scan(core, StabilityGate.Options.strictMode());

        assertTrue(warn.stream().allMatch(d -> "warning".equals(d.severity())));
        assertTrue(strict.stream().allMatch(d -> "warning".equals(d.severity())), "strict 也是 warning severity");
        assertTrue(strict.stream().allMatch(StabilityGate.Diagnostic::blocking), "strict 时 blocking=true");
        assertTrue(warn.stream().noneMatch(StabilityGate.Diagnostic::blocking), "warn 时 blocking=false");
        assertTrue(StabilityGate.shouldReject(strict, true));
        assertEquals(false, StabilityGate.shouldReject(strict, false));
        assertEquals(false, StabilityGate.shouldReject(List.of(), true));
    }

    @Test
    @DisplayName("allowExperimental=true 返回空")
    void allowExperimental() {
        CoreModel.Module core = compileToCore("""
                Module test.allow.

                @deprecated
                Rule oldRule, produce Text:
                  Return "x".
                """);
        List<StabilityGate.Diagnostic> diags = StabilityGate.scan(core, new StabilityGate.Options(true, true));
        assertTrue(diags.isEmpty(), "allowExperimental 应返回空");
    }

    /** Java 完整编译管线：源码 → CoreModel（Canonicalize → ANTLR → AstBuilder → CoreLowering）。 */
    private static CoreModel.Module compileToCore(String source) {
        String canonicalized = new Canonicalizer().canonicalize(source);
        var charStream = CharStreams.fromString(canonicalized);
        var lexer = new AsterCustomLexer(charStream);
        var tokens = new CommonTokenStream(lexer);
        var parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new FailFastErrorListener());
        AsterParser.ModuleContext moduleCtx = parser.module();
        aster.core.ast.Module ast = new AstBuilder().visitModule(moduleCtx);
        return new CoreLowering().lowerModule(ast);
    }

    private static final class FailFastErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new IllegalStateException("解析错误 @" + line + ":" + charPositionInLine + " " + msg);
        }
    }
}
