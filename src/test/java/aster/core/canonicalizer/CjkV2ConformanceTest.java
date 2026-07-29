package aster.core.canonicalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CJK v2 标点规范化的**跨实现字节等价**（Java 端）。
 *
 * <h2>背景：它替换掉了一个从未真正运行过的测试</h2>
 *
 * <p>原 {@code CanonicalizerGoldenTest} 号称验收标准是「Java↔TS canonicalizer 全语料
 * 100% 输出一致」，实际上因为三个独立原因从未执行过一次：
 * <ol>
 *   <li>仓库根路径硬编码成 {@code /Users/rpang/IdeaProjects/aster-lang-ts}
 *       ——任何 CI runner 上都不存在；</li>
 *   <li>它探测的构建产物 {@code dist/src/canonicalizer.js} 早已移动到
 *       {@code dist/src/frontend/canonicalizer.js}；</li>
 *   <li>它遍历的语料目录 {@code test/cnl/programs/examples} 里的 {@code .aster}
 *       源文件已迁走，只剩期望 JSON。</li>
 * </ol>
 * 三处都用 {@code Assumptions.assumeTrue} 静默跳过，于是测试常年「通过」。
 *
 * <p>本测试改打**真实存在**的语料：{@code aster-lang-test/corpus/conformance/cjk-v2}
 * ——每个用例是 {@code X.aster}（输入）+ {@code X.expected.txt}（期望输出）+
 * {@code X.meta.json}（声明 {@code engines: ["ts","java"]}，即两个引擎都必须字节一致）。
 * TS 侧早有对应的 conformance 测试，Java 侧此前是空缺。
 *
 * <p>不再 shell-out 到 Node：直接比对同一份 {@code .expected.txt}，两侧各自与它字节
 * 相等即等价于彼此相等。这比原来的「跑 node 再比字符串」更快也更稳。
 */
@DisplayName("CJK v2 标点规范化跨实现一致性（Java 端）")
class CjkV2ConformanceTest {

    /** 期望的最少用例数——防止语料路径写错时「零用例」伪装成通过。 */
    private static final int MIN_EXPECTED_CASES = 4;

    /**
     * 定位 aster-lang-test 仓根：系统属性 → 环境变量 → 兄弟目录回退。
     * 与 corpus-regression.yml 注入的 {@code ASTER_LANG_TEST_PATH} 对齐。
     */
    private static Path resolveCorpusRoot() {
        String sysProp = System.getProperty("aster.test.root");
        if (sysProp != null && !sysProp.isBlank()) {
            return Paths.get(sysProp);
        }
        String envVar = System.getenv("ASTER_LANG_TEST_PATH");
        if (envVar != null && !envVar.isBlank()) {
            return Paths.get(envVar);
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path parent = cwd.getParent();
        return parent == null ? cwd.resolve("aster-lang-test") : parent.resolve("aster-lang-test");
    }

    @TestFactory
    @DisplayName("每个 conformance 用例的输出与 .expected.txt 字节相等")
    List<DynamicTest> cjkV2Conformance() throws IOException {
        Path dir = resolveCorpusRoot().resolve("corpus/conformance/cjk-v2");

        // ★不返回空列表：JUnit 把「零个动态测试」记为通过，语料找不到会伪装成全绿
        // ——这正是本文件所替换的那个测试犯的错。
        assertTrue(Files.isDirectory(dir),
            "CJK v2 conformance 语料目录不存在: " + dir.toAbsolutePath()
                + "；请置于 aster-lang-test 兄弟目录，或设 ASTER_LANG_TEST_PATH。");

        List<Path> cases = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".aster"))
                 .sorted()
                 .forEach(cases::add);
        }

        assertTrue(cases.size() >= MIN_EXPECTED_CASES,
            String.format("期望 ≥%d 个 .aster 用例，实际 %d ——语料可能未同步",
                MIN_EXPECTED_CASES, cases.size()));

        List<DynamicTest> tests = new ArrayList<>();
        for (Path src : cases) {
            String name = src.getFileName().toString().replace(".aster", "");
            Path expectedPath = src.resolveSibling(name + ".expected.txt");

            tests.add(DynamicTest.dynamicTest(name, () -> {
                assertTrue(Files.exists(expectedPath),
                    "用例缺少期望文件: " + expectedPath.getFileName());

                String input = Files.readString(src, StandardCharsets.UTF_8);
                String expected = Files.readString(expectedPath, StandardCharsets.UTF_8);
                String actual = Canonicalizer.normalizeCJKPunctuationOnly(input);

                // 字节级相等：两个引擎共用同一份 .expected.txt，各自与它相等
                // 即等价于彼此相等。CJK 标点规范化差一个字符就会让审计链的
                // 源码哈希在两个引擎间分叉。
                assertEquals(expected, actual,
                    "用例 " + name + " 的规范化输出与 .expected.txt 不一致"
                        + "（TS 侧对同一份期望文件有对应断言，两侧必须同时满足）");
            }));
        }
        return tests;
    }
}
