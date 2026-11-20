package aster.core.embed;

import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.ParseDiagnostic;
import aster.core.parser.ParseDiagnostics;
import aster.core.typecheck.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 「空源码」判定与 core 词法一致（core#288，aster-lang-ts#348）：core 只认 ASCII 空格与 tab 是词法空白（zh 另认
 * U+3000，由规范化转成半角空格）。解析入口曾用 {@link String#isBlank()}，把只含 U+3000 / U+2003 / U+2028 等
 * Unicode 空白的源码当空源码拒掉，词法本应对它们报 E050。
 *
 * <p>判定以 {@link ParseDiagnostics} 为准：它对某份源码报诊断时，{@link InProcessCnlParser} 必须报同码同行；
 * 它不报诊断（词法上没有 token）时，按空源码拒绝。与 aster-api {@code BlankSourceParityTest} 同一组源码。
 */
class BlankSourceParityTest {

    private static final List<String> LOCALES = List.of("en-US", "zh-CN", "de-DE", "hi-IN");

    /** 只含一种空白类字符的源码：ASCII 词法空白、其余 ASCII 控制空白、常见 Unicode 空白与格式字符。 */
    private static final List<String> SOURCES = List.of(
        "", " ", "  \n", "\t\n", "\r\n", "\n\n",
        "\u000B\n", "\f\n",
        "　\n", "　　", " \n", " \n", " \n", " \n", " \n",
        " \n", " \n", "\u0085\n", " \n", "​\n", "﻿\n", "\n﻿");

    @TestFactory
    Stream<DynamicTest> parserMatchesCoreOnWhitespaceOnlySources() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String locale : LOCALES) {
            for (String source : SOURCES) {
                tests.add(DynamicTest.dynamicTest(locale + " " + escape(source),
                    () -> assertParserMatchesCore(source, locale)));
            }
        }
        return tests.stream();
    }

    /**
     * 两引擎共享夹具（与 aster-lang-ts {@code shared/blank_source_parity.json} 逐字节相同）：本类的
     * 源码 × 词法表全集，期望为空源码或主诊断 {code, line}。ts {@code compileSource} 按同一夹具断言。
     */
    @TestFactory
    Stream<DynamicTest> sharedFixtureMatchesParser() throws IOException {
        JsonNode cases = fixture().get("cases");
        List<String> keys = new ArrayList<>();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String locale = c.get("locale").asText();
            String source = c.get("source").asText();
            JsonNode expect = c.get("expect");
            keys.add(locale + "|" + source);
            tests.add(DynamicTest.dynamicTest("fixture " + locale + " " + escape(source),
                () -> assertFixtureCase(source, locale, expect)));
        }
        List<String> all = new ArrayList<>();
        for (String locale : LOCALES) {
            for (String source : SOURCES) {
                all.add(locale + "|" + source);
            }
        }
        assertThat(keys).as("夹具须正好覆盖 SOURCES × LOCALES").containsExactlyElementsOf(all);
        return tests.stream();
    }

    @Test
    void sourcesCoverAllCases() {
        assertThat(SOURCES).hasSize(22).doesNotHaveDuplicates();
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = BlankSourceParityTest.class.getResourceAsStream("/diagnostics/blank_source_parity.json")) {
            assertThat(in).as("缺少共享夹具 blank_source_parity.json").isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    private static void assertFixtureCase(String source, String locale, JsonNode expect) {
        InProcessCnlParser.CnlParseException e = catchThrowableOfType(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(source, locale));
        assertThat(e).as("只含空白类字符的源码不应编译成功").isNotNull();
        if (expect.path("empty").asBoolean(false)) {
            assertThat(e.getDiagnostics()).as("期望按空源码拒绝：%s", e.getMessage()).isEmpty();
            return;
        }
        CnlErrorListener.Diagnostic primary = e.getDiagnostics().stream()
            .filter(d -> !"E063".equals(d.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("期望带码诊断：" + e.getMessage()));
        assertThat(List.of(primary.code(), primary.line()))
            .isEqualTo(List.of(expect.get("code").asText(), expect.get("line").asInt()));
    }

    @Test
    void isBlankSource_onlyAsciiLexicalWhitespace() {
        assertThat(InProcessCnlParser.isBlankSource(null)).isTrue();
        assertThat(InProcessCnlParser.isBlankSource("")).isTrue();
        assertThat(InProcessCnlParser.isBlankSource(" \t\r\n")).isTrue();
        for (String s : List.of("\u3000", "\u00A0", "\u2003", "\u2028", "\u205F", "\uFEFF", "\u000B", "x")) {
            assertThat(InProcessCnlParser.isBlankSource(" " + s + "\n")).as(escape(s)).isFalse();
        }
    }

    @Test
    void parser_asciiBlankSourceRejectedAsEmpty() {
        for (String locale : LOCALES) {
            assertThatThrownBy(() -> InProcessCnlParser.parse("  \n\t\n", locale))
                .as(locale)
                .isInstanceOf(InProcessCnlParser.CnlParseException.class)
                .hasMessage(InProcessCnlParser.EMPTY_SOURCE_MESSAGE);
        }
    }

    @Test
    void parser_zhFullWidthSpaceOnlyIsEmptyAfterCanonicalization() {
        assertThatThrownBy(() -> InProcessCnlParser.parse("\u3000\n\u3000\u3000\n", "zh-CN"))
            .isInstanceOf(InProcessCnlParser.CnlParseException.class)
            .hasMessage(InProcessCnlParser.EMPTY_SOURCE_MESSAGE);
    }

    /** core#288 复现：en 的 U+3000、U+2003，de 的 U+205F 只含它们时报 E050（第 1 行），不是空源码。 */
    @Test
    void parser_unicodeWhitespaceOnlyReportsE050() {
        for (String[] c : new String[][]{{"\u3000\n", "en-US"}, {"\u2003\n", "en-US"}, {"\u205F\n", "de-DE"}}) {
            InProcessCnlParser.CnlParseException e = catchThrowableOfType(InProcessCnlParser.CnlParseException.class,
                () -> InProcessCnlParser.parse(c[0], c[1]));
            assertThat(e).as(c[1] + " " + escape(c[0])).isNotNull();
            assertThat(e.getMessage()).isNotEqualTo(InProcessCnlParser.EMPTY_SOURCE_MESSAGE);
            assertThat(e.getDiagnostics()).extracting(CnlErrorListener.Diagnostic::code).contains("E050");
            assertThat(e.getDiagnostics()).filteredOn(d -> "E050".equals(d.code()))
                .extracting(CnlErrorListener.Diagnostic::line).containsOnly(1);
        }
    }

    private static void assertParserMatchesCore(String source, String locale) {
        Optional<ParseDiagnostic> core = corePrimary(source, locale);
        InProcessCnlParser.CnlParseException e = catchThrowableOfType(InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parse(source, locale));
        assertThat(e).as("只含空白类字符的源码不应编译成功").isNotNull();
        if (core.isEmpty()) {
            assertThat(e.getDiagnostics()).as("core 不报诊断时按空源码拒绝：%s", e.getMessage()).isEmpty();
            return;
        }
        List<CnlErrorListener.Diagnostic> ds = e.getDiagnostics();
        assertThat(ds).as("core 报 %s，aster-api 必须带码：%s", core.get().code().code(), e.getMessage()).isNotEmpty();
        CnlErrorListener.Diagnostic primary = ds.stream()
            .filter(d -> !"E063".equals(d.code()))
            .findFirst()
            .orElse(ds.get(0));
        assertThat(List.of(primary.code(), primary.line()))
            .isEqualTo(List.of(core.get().code().code(), core.get().line()));
    }

    /** core 参考实现的主诊断（与 {@code aster.core.parser.ParseDiagnosticsParityTest} 同一选取规则）。 */
    private static Optional<ParseDiagnostic> corePrimary(String source, String locale) {
        var result = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow(locale));
        return result.diagnostics().stream()
            .filter(d -> d.code() != ErrorCode.PARSE_NO_VIABLE)
            .findFirst()
            .or(() -> result.diagnostics().stream().findFirst());
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        s.chars().forEach(c -> sb.append(c < 0x20 || c > 0x7E ? String.format("\\u%04X", c) : String.valueOf((char) c)));
        return sb.isEmpty() ? "<empty>" : sb.toString();
    }
}
