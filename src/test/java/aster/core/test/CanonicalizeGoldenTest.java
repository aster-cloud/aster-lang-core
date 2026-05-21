package aster.core.test;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonicalizer 阶段双引擎 EQUIVALENCE 测试
 * <p>
 * <b>D13 升级（2026-05-21）</b>：从 SMOKE（仅 non-null）升级为真实结构对比。
 * 两侧 canonicalizer 都产出 canonicalized source 字符串，封装在 JsonNode 中。
 * 比较时通过 {@link #normalizeCanonicalSource} 抹平已知的格式差异：
 * <ul>
 *   <li>trailing whitespace / newlines</li>
 *   <li>多余空白行（CRLF vs LF）</li>
 *   <li>UTF-8 BOM</li>
 * </ul>
 * 抹平后剩余的差异即为真实语义漂移，会让测试失败并在错误消息中打出 diff。
 * <p>
 * 完整 IR 层 equivalence 仍由 {@code DualEngineGoldenTest} 和
 * {@code DualEngineCrossLangTest} 在 Core IR 阶段补充，覆盖 lowering /
 * runtime 维度的 divergence（见 aster-lang-test/DIVERGENT-MANIFEST.md）。
 * <p>
 * 测试在 fixture 文件存在时启用；CI 中未挂 fixture 目录时自动跳过。
 */
@Tag("golden")
@EnabledIf("fixturesPresent")
public class CanonicalizeGoldenTest {

    static boolean fixturesPresent() {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        return root != null && Files.isDirectory(root.resolve("test/cnl/examples"));
    }

    private static GoldenTestRunner runner;
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @BeforeAll
    static void setUp() {
        runner = new GoldenTestRunner(PROJECT_ROOT);
    }

    /**
     * 测试：hello.aster 的 Canonicalizer 输出应与 TypeScript 版本一致
     */
    @Test
    void testHelloAsterCanonicalize() throws Exception {
        assertBothEnginesCanonicalize(Path.of("test/cnl/examples/hello.aster"));
    }

    /** 数据类型声明的规范化 */
    @Test
    void testDataDeclCanonicalize() throws Exception {
        assertBothEnginesCanonicalize(Path.of("test/cnl/examples/person.aster"));
    }

    /** 函数声明的规范化 */
    @Test
    void testFuncDeclCanonicalize() throws Exception {
        assertBothEnginesCanonicalize(Path.of("test/cnl/examples/math.aster"));
    }

    /** 枚举声明的规范化 */
    @Test
    void testEnumDeclCanonicalize() throws Exception {
        assertBothEnginesCanonicalize(Path.of("test/cnl/examples/status.aster"));
    }

    /** 复杂表达式的规范化 */
    @Test
    void testComplexExprCanonicalize() throws Exception {
        assertBothEnginesCanonicalize(Path.of("test/cnl/examples/calculator.aster"));
    }

    /**
     * 双引擎 equivalence：要求 TS 和 Java canonicalizer 对同一 fixture
     * 产出经 {@link #normalizeCanonicalSource} 抹平后相等的 canonical 文本。
     * 任何残余差异 = 真实语义漂移，必须修复或显式记录到 manifest。
     */
    private void assertBothEnginesCanonicalize(Path input) throws Exception {
        JsonNode tsOutput = runner.runTypeScript("canonicalize", input);
        assertNotNull(tsOutput, "TypeScript Canonicalizer must return non-null for " + input);

        JsonNode javaOutput = runner.runJava("canonicalize", input);
        assertNotNull(javaOutput, "Java Canonicalizer must return non-null for " + input);

        // Both engines wrap the canonicalized source string in a JsonNode.
        // Extract and normalize before comparing; surface a diff-style error
        // message so reviewers can see exactly what diverged.
        String tsText = extractCanonicalText(tsOutput, "TypeScript", input);
        String javaText = extractCanonicalText(javaOutput, "Java", input);
        String tsNorm = normalizeCanonicalSource(tsText);
        String javaNorm = normalizeCanonicalSource(javaText);

        assertEquals(
            tsNorm,
            javaNorm,
            () -> String.format(
                "Canonicalize divergence for %s%n--- TypeScript ---%n%s%n--- Java ---%n%s%n",
                input, tsText, javaText
            )
        );
    }

    /**
     * Both engines historically wrap their canonicalized source string in
     * a JsonNode. If a node is already textual, return its asText(); if
     * it's an object/array (future enhancement) we fall back to the raw
     * JSON serialization so callers can still diff something meaningful.
     */
    private static String extractCanonicalText(JsonNode node, String engineName, Path input) {
        if (node.isTextual()) return node.asText();
        // Defensive: future versions of either runner may return objects.
        // Use the JSON form so the diff in assertEquals message remains useful.
        return node.toString();
    }

    /**
     * Normalize canonicalize-stage output so that legitimate engine-side
     * formatting differences don't flag as divergence. Strips:
     *   - UTF-8 BOM
     *   - trailing whitespace on each line
     *   - leading/trailing blank lines
     *   - CRLF → LF
     */
    static String normalizeCanonicalSource(String s) {
        if (s == null) return "";
        String out = s;
        if (out.startsWith("﻿")) out = out.substring(1);
        out = out.replace("\r\n", "\n").replace("\r", "\n");
        // strip trailing whitespace per line
        StringBuilder sb = new StringBuilder(out.length());
        for (String line : out.split("\n", -1)) {
            // remove trailing spaces/tabs only (preserve indentation)
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) end--;
            sb.append(line, 0, end).append('\n');
        }
        // collapse trailing blank lines
        String joined = sb.toString();
        int j = joined.length();
        while (j > 0 && joined.charAt(j - 1) == '\n') j--;
        return joined.substring(0, j);
    }
}
