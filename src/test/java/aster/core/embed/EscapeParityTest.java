package aster.core.embed;

import aster.core.lexicon.SemanticTokenKind;
import aster.core.typecheck.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 名字转义（ADR 0064）两引擎共享夹具 {@code escape_parity.json}（§10 F1–F7）：词法与 E902、IR 中性、规范化不透明、
 * 关键词判定排除、§4 例子、E808 / W802 / E901 诊断与附录 A。夹具与 aster-lang-ts {@code shared/escape_parity.json}
 * 逐字节相同（由 ts 侧断言），ts {@code escape-parity.test.ts} 按同一夹具断言。
 */
class EscapeParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> sharedFixture() throws IOException {
        JsonNode cases = fixture().get("cases");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.get("group").asText() + " " + c.get("name").asText(), () -> assertCase(c)));
        }
        assertThat(tests).hasSize(119);
        return tests.stream();
    }

    /** 编译结果：成功时 {@code ir} 非空，失败时 {@code first} 为第一条诊断。 */
    private record Outcome(String ir, CnlErrorListener.Diagnostic first, List<aster.core.typecheck.model.Diagnostic> warnings) {}

    private static Outcome compile(String source, String locale, Map<SemanticTokenKind, List<String>> aliasSet) {
        try {
            InProcessCnlParser.ParseResult result = aliasSet.isEmpty()
                ? InProcessCnlParser.parse(source, locale)
                : InProcessCnlParser.parseWithUserAliases(source, locale, null, aliasSet, true);
            return new Outcome(aster.core.ir.CoreIrJson.serialize(result.core()), null, result.warnings());
        } catch (InProcessCnlParser.CnlParseException e) {
            return new Outcome(null, e.getDiagnostics().isEmpty() ? null : e.getDiagnostics().get(0), List.of());
        }
    }

    private static void assertCase(JsonNode c) throws Exception {
        String locale = c.get("lexicon").asText();
        String source = c.get("source").asText();
        Map<SemanticTokenKind, List<String>> aliasSet = aliases(c.get("aliasSet"));
        Outcome out = compile(source, locale, aliasSet);
        if (c.has("reject")) {
            String code = c.get("reject").get(0).asText();
            assertThat(out.ir()).as("应拒绝").isNull();
            if ("forged".equals(code)) {
                return;
            }
            assertThat(out.first()).isNotNull();
            assertThat(List.of(out.first().code(), out.first().line()))
                .isEqualTo(List.of(code, c.get("reject").get(1).asInt()));
            if (c.has("column")) {
                assertThat(out.first().column()).isEqualTo(c.get("column").asInt());
            }
            if (c.has("params")) {
                Map<String, String> want = new LinkedHashMap<>();
                c.get("params").fields().forEachRemaining(p -> want.put(p.getKey(), p.getValue().asText()));
                assertThat(out.first().params()).isEqualTo(want);
            }
            if (c.has("message") && "en-US".equals(locale)) {
                assertThat(out.first().message()).isEqualTo(c.get("message").asText());
            }
            return;
        }
        if (c.has("behavesAs")) {
            return;  // 见 behavesAs()
        }
        assertThat(out.ir()).as("应编译通过：%s", out.first()).isNotNull();
        if (c.has("names")) {
            for (JsonNode n : c.get("names")) {
                assertThat(out.ir()).as("Core IR 含名字 %s", n.asText()).contains("\"" + n.asText() + "\"");
            }
        }
        if (c.has("samePf")) {
            Outcome other = compile(c.get("samePf").asText(), locale, aliasSet);
            assertThat(other.ir()).as("对照源码应编译通过：%s", other.first()).isNotNull();
            assertThat(PolicySourceCompiler.positionFreeSha256(out.ir()))
                .isEqualTo(PolicySourceCompiler.positionFreeSha256(other.ir()));
        }
        if (c.has("warnings")) {
            List<List<Object>> warnings = out.warnings().stream()
                .filter(d -> d.code() == ErrorCode.KEYWORD_RENAMED_AS_NAME)
                .map(d -> List.<Object>of(d.code().code(), d.span().map(o -> o.start.line).orElse(0)))
                .toList();
            List<List<Object>> want = new ArrayList<>();
            c.get("warnings").forEach(w -> want.add(List.of(w.get(0).asText(), w.get(1).asInt())));
            assertThat(warnings).isEqualTo(want);
        }
    }

    /** behavesAs 的源码可能编译失败，单独断言（{@link #assertCase} 先按 reject / 通过分流）。 */
    @TestFactory
    Stream<DynamicTest> behavesAs() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            if (!c.has("behavesAs")) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                String locale = c.get("lexicon").asText();
                Outcome a = compile(c.get("source").asText(), locale, aliases(c.get("aliasSet")));
                Outcome b = compile(c.get("behavesAs").asText(), locale, aliases(c.get("aliasSet")));
                assertThat(a.ir() == null).as("两份源码同为通过或同为失败").isEqualTo(b.ir() == null);
                if (a.ir() != null) {
                    assertThat(PolicySourceCompiler.positionFreeSha256(a.ir()))
                        .isEqualTo(PolicySourceCompiler.positionFreeSha256(b.ir()));
                } else {
                    assertThat(List.of(a.first().code(), a.first().line()))
                        .isEqualTo(List.of(b.first().code(), b.first().line()));
                }
            }));
        }
        return tests.stream();
    }

    @org.junit.jupiter.api.Test
    void forgedMarkerInsideEscapeIsStillRejected() {
        assertThatThrownBy(() -> InProcessCnlParser.parse(
            "Module probe.sample.\n\nRule f given x as Int, produce Int:\n  Return `a`.\n", "en-US"))
            .isInstanceOf(InProcessCnlParser.CnlParseException.class);
    }

    private static Map<SemanticTokenKind, List<String>> aliases(JsonNode node) {
        Map<SemanticTokenKind, List<String>> out = new EnumMap<>(SemanticTokenKind.class);
        if (node == null) {
            return out;
        }
        node.fields().forEachRemaining(e -> {
            List<String> list = new ArrayList<>();
            e.getValue().forEach(v -> list.add(v.asText()));
            out.put(SemanticTokenKind.valueOf(e.getKey()), list);
        });
        return out;
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = EscapeParityTest.class.getResourceAsStream("/diagnostics/escape_parity.json")) {
            return MAPPER.readTree(in);
        }
    }
}
