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
    @DisplayName("数组路径 items[0].label 与 backbone 相同时精确产 2 条 OVERLAY_VALUE_UNTRANSLATED (Critical-6 回归)")
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

        long untranslated = report.issues().stream()
            .filter(i -> "OVERLAY_VALUE_UNTRANSLATED".equals(i.code()))
            .count();
        // Precise count: items[0].label + items[1].label = 2.
        // (version is a number, not isTextual(), so it should NOT contribute.)
        assertThat(untranslated).isEqualTo(2L);
    }

    @Test
    @DisplayName("collectLeaves: dot + [N] 混合路径正确收集 JsonNode 引用")
    void collectLeavesArrayIndices() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = m.readTree(
            """
            {"a":{"b":[{"c":"X"},{"c":"Y"}]}}
            """);
        var leaves = OverlayValidator.collectLeaves(root, "");
        // Display strings match what consumers see in diagnostics.
        var displays = leaves.keySet().stream().map(OverlayValidator.LeafPath::display).toList();
        assertThat(displays).containsExactlyInAnyOrder("a.b[0].c", "a.b[1].c");
        // Value lookup goes through the structured key, not the display string.
        var targetKey = leaves.keySet().stream()
            .filter(k -> k.display().equals("a.b[1].c"))
            .findFirst().orElseThrow();
        assertThat(leaves.get(targetKey).asText()).isEqualTo("Y");
    }

    @Test
    @DisplayName("collectLeaves: 字段名含 '.' 的 key 与等价嵌套结构不碰撞 (R2-M1)")
    void collectLeavesDoesNotCollideOnDotKey() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        // Two structurally different JSONs that would have produced the SAME
        // display string under the previous String-keyed implementation:
        //   {"a.b":"X"}      → display "a.b"
        //   {"a":{"b":"Y"}}  → display "a.b"
        // With LeafPath keys these are distinct.
        var flat = m.readTree("""
            {"a.b":"X"}
            """);
        var nested = m.readTree("""
            {"a":{"b":"Y"}}
            """);
        var flatLeaves = OverlayValidator.collectLeaves(flat, "");
        var nestedLeaves = OverlayValidator.collectLeaves(nested, "");

        // Same display string …
        assertThat(flatLeaves.keySet().iterator().next().display()).isEqualTo("a.b");
        assertThat(nestedLeaves.keySet().iterator().next().display()).isEqualTo("a.b");
        // … but different structural keys.
        assertThat(flatLeaves.keySet()).doesNotContainAnyElementsOf(nestedLeaves.keySet());
    }

    @Test
    @DisplayName("comparePerFile: {a.b:X} vs {a:{b:X}} 互为 MISSING + EXTRA，绝不当成同 key")
    void overlayHonestlyDistinguishesDotKeyFromNested(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("de");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        Files.writeString(backbone.resolve("ambig.json"), "{\"a.b\":\"X\"}");
        Files.writeString(candidate.resolve("ambig.json"), "{\"a\":{\"b\":\"X\"}}");

        var report = validator.validate(candidate, backbone, "de-DE");

        long missing = report.issues().stream()
            .filter(i -> "OVERLAY_KEY_MISSING".equals(i.code())).count();
        long extra = report.issues().stream()
            .filter(i -> "OVERLAY_KEY_NOT_IN_BACKBONE".equals(i.code())).count();
        // backbone has a.b but candidate doesn't → MISSING.
        assertThat(missing).isEqualTo(1L);
        // candidate has a.b (nested) but backbone doesn't → EXTRA.
        assertThat(extra).isEqualTo(1L);
    }

    @Test
    @DisplayName("collectLeaves: 字段名含 '.' / '[' / ']' / 空字符串也能正确比较")
    void collectLeavesHandlesSyntaxCharsInKeys(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("de");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        // Field names containing '.', '[', ']' and an empty key.
        Files.writeString(backbone.resolve("weird.json"),
            """
            {"a.b":"X","c[0]":"Y","":"Z"}
            """);
        Files.writeString(candidate.resolve("weird.json"),
            """
            {"a.b":"X","c[0]":"Y","":"Z"}
            """);

        var report = validator.validate(candidate, backbone, "de-DE");

        // Each leaf is byte-identical → 3 OVERLAY_VALUE_UNTRANSLATED warnings.
        long untranslated = report.issues().stream()
            .filter(i -> "OVERLAY_VALUE_UNTRANSLATED".equals(i.code()))
            .count();
        assertThat(untranslated).isEqualTo(3L);
    }

    @Test
    @DisplayName("pathGet: 越界、未闭合括号、负数下标、字段名含语法字符全部安全返回 null")
    void pathGetEdgeCases() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = m.readTree(
            """
            {"a":{"b":[{"c":"X"},{"c":"Y"}]}}
            """);
        // happy path still works
        assertThat(OverlayValidator.pathGet(root, "a.b[1].c").asText()).isEqualTo("Y");
        // out-of-range index
        assertThat(OverlayValidator.pathGet(root, "a.b[99].c")).isNull();
        // non-numeric inside brackets
        assertThat(OverlayValidator.pathGet(root, "a.b[abc].c")).isNull();
        // negative index
        assertThat(OverlayValidator.pathGet(root, "a.b[-1].c")).isNull();
        // unclosed bracket
        assertThat(OverlayValidator.pathGet(root, "a.b[1")).isNull();
        // empty token (root-level empty key not present)
        assertThat(OverlayValidator.pathGet(root, ".a.b")).isNull();
    }

    @Test
    @DisplayName("backbone 目录不可枚举时报具名 OVERLAY_LIST_FAILED (区分 backbone vs candidate)")
    @org.junit.jupiter.api.condition.EnabledOnOs({
        org.junit.jupiter.api.condition.OS.LINUX,
        org.junit.jupiter.api.condition.OS.MAC,
    })
    void listJsonFilesPropagatesIoError(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("de");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        // Drop all permissions on backbone so Files.list() throws
        // AccessDeniedException — exercises the listJsonFiles try/catch.
        // isDirectory() still returns true, so we get past the directory check
        // and into the IOException branch.
        java.util.Set<java.nio.file.attribute.PosixFilePermission> none =
            java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
        try {
            Files.setPosixFilePermissions(backbone, none);
            var report = validator.validate(candidate, backbone, "de-DE");

            // Specifically OVERLAY_LIST_FAILED with the backbone role named.
            var listFailed = report.issues().stream()
                .filter(i -> "OVERLAY_LIST_FAILED".equals(i.code()))
                .toList();
            assertThat(listFailed).hasSize(1);
            assertThat(listFailed.get(0).message()).contains("backbone overlays").contains(backbone.toString());
        } finally {
            // Restore permissions so @TempDir can clean up.
            Files.setPosixFilePermissions(
                backbone,
                java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    @DisplayName("candidate 目录不可枚举时 OVERLAY_LIST_FAILED 错误消息标 candidate 角色")
    @org.junit.jupiter.api.condition.EnabledOnOs({
        org.junit.jupiter.api.condition.OS.LINUX,
        org.junit.jupiter.api.condition.OS.MAC,
    })
    void listJsonFilesPropagatesIoErrorOnCandidate(@TempDir Path tmp) throws IOException {
        Path backbone = tmp.resolve("en");
        Path candidate = tmp.resolve("de");
        Files.createDirectories(backbone);
        Files.createDirectories(candidate);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> none =
            java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
        try {
            Files.setPosixFilePermissions(candidate, none);
            var report = validator.validate(candidate, backbone, "de-DE");
            var listFailed = report.issues().stream()
                .filter(i -> "OVERLAY_LIST_FAILED".equals(i.code()))
                .toList();
            assertThat(listFailed).hasSize(1);
            assertThat(listFailed.get(0).message()).contains("candidate overlays").contains(candidate.toString());
        } finally {
            Files.setPosixFilePermissions(
                candidate,
                java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        }
    }
}
