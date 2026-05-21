package aster.core.lexicon.tools;

import aster.core.lexicon.tools.LexiconValidationReport.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OverlayValidator — 跨 locale overlay parity")
class OverlayValidatorTest {

    private final OverlayValidator validator = new OverlayValidator();

    @Test
    @DisplayName("backbone overlay file 缺失时报 OVERLAY_FILE_MISSING")
    void detectsMissingFile(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("de");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        Files.writeString(backbone.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"Foo"}}
            """);
        // candidate has no lsp-ui-texts.json at all

        var report = validator.validate(candidate, backbone, "de-DE");

        assertThat(report.passed()).isFalse();
        assertThat(report.issues())
            .extracting(LexiconValidationReport.Issue::code)
            .contains("OVERLAY_FILE_MISSING");
    }

    @Test
    @DisplayName("key 缺失时报 OVERLAY_KEY_MISSING")
    void detectsMissingKey(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("zh");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        Files.writeString(backbone.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"Foo","bar":"Bar"}}
            """);
        Files.writeString(candidate.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"富"}}
            """);

        var report = validator.validate(candidate, backbone, "zh-CN");

        assertThat(report.passed()).isFalse();
        assertThat(report.issues())
            .filteredOn(i -> i.code().equals("OVERLAY_KEY_MISSING"))
            .extracting(LexiconValidationReport.Issue::message)
            .anyMatch(m -> m.contains("texts.bar"));
    }

    @Test
    @DisplayName("byte-identical value 标记为 OVERLAY_VALUE_UNTRANSLATED warning")
    void warnsOnUntranslatedValue(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("de");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        Files.writeString(backbone.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"Function"}}
            """);
        Files.writeString(candidate.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"Function"}}
            """);

        var report = validator.validate(candidate, backbone, "de-DE");

        // Untranslated is a warning, not error — report.passed() may still be true
        assertThat(report.issues())
            .filteredOn(i -> i.severity() == Severity.WARNING)
            .extracting(LexiconValidationReport.Issue::code)
            .contains("OVERLAY_VALUE_UNTRANSLATED");
    }

    @Test
    @DisplayName("candidate-only extra key 标记为 INFO，不影响 passed()")
    void infoOnExtraKey(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("zh");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        Files.writeString(backbone.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"Foo"}}
            """);
        Files.writeString(candidate.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"富","extra":"额外"}}
            """);

        var report = validator.validate(candidate, backbone, "zh-CN");

        // Extra key is INFO; no ERROR raised
        assertThat(report.passed()).isTrue();
        assertThat(report.issues())
            .filteredOn(i -> i.code().equals("OVERLAY_KEY_NOT_IN_BACKBONE"))
            .isNotEmpty();
    }

    @Test
    @DisplayName("complete + correctly translated overlay 通过")
    void passesOnCompleteOverlay(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("zh");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        Files.writeString(backbone.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"Function","bar":"Module"}}
            """);
        Files.writeString(candidate.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"texts":{"foo":"函数","bar":"模块"}}
            """);

        var report = validator.validate(candidate, backbone, "zh-CN");

        assertThat(report.passed()).isTrue();
        assertThat(report.issues())
            .filteredOn(i -> i.severity() == Severity.ERROR)
            .isEmpty();
    }

    @Test
    @DisplayName("数组路径 items[0].label 与 backbone 相同时报 OVERLAY_VALUE_UNTRANSLATED (Critical-6 回归)")
    void detectsUntranslatedArrayElement(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("de");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        Files.writeString(backbone.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"items":[{"label":"Function"},{"label":"Module"}]}
            """);
        // candidate copies backbone byte-for-byte → both array elements should be flagged.
        Files.writeString(candidate.resolve("lsp-ui-texts.json"),
            """
            {"version":1,"items":[{"label":"Function"},{"label":"Module"}]}
            """);

        var report = validator.validate(candidate, backbone, "de-DE");

        // Without the pathGet fix, the array-element comparison was silently
        // returning null and skipping the byte-equality check. Now both
        // elements must surface as warnings.
        long untranslated = report.issues().stream()
            .filter(i -> "OVERLAY_VALUE_UNTRANSLATED".equals(i.code()))
            .count();
        assertThat(untranslated).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("pathGet 直接单元测试 — 支持 dot + [N] 混合路径")
    void pathGetHandlesArrayIndices() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = m.readTree(
            """
            {"a":{"b":[{"c":"X"},{"c":"Y"}]}}
            """);
        var got = OverlayValidator.pathGet(root, "a.b[1].c");
        assertThat(got).isNotNull();
        assertThat(got.asText()).isEqualTo("Y");

        // Out-of-range index returns null (defense-in-depth).
        assertThat(OverlayValidator.pathGet(root, "a.b[99].c")).isNull();
        // Malformed bracket returns null.
        assertThat(OverlayValidator.pathGet(root, "a.b[abc].c")).isNull();
    }
}
