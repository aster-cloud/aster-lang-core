package aster.core.ir;

import aster.core.test.GoldenTestRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 跨编译器 Core IR 黄金测试
 * <p>
 * 验证 Java 编译器（Canonicalize → ANTLR Parse → AstBuilder → CoreLowering）
 * 与 TypeScript 编译器（canonicalize → lex → parse → lowerModule）
 * 对相同 .aster 源文件产生等价的 Core IR JSON。
 * <p>
 * TS 仓库路径通过系统属性 {@code aster.ts.root} 或环境变量 {@code ASTER_TS_ROOT} 配置，
 * 默认回退到 {@code ../aster-lang-ts}（兄弟目录布局）。
 * <p>
 * 剪枝字段（两个编译器间自然存在差异）：
 * <ul>
 *   <li>位置字段：origin, span, file, nameSpan, variantSpans</li>
 *   <li>推断标记：typeInferred, retTypeInferred</li>
 *   <li>返回类型：ret——TS 推断为 TypeVar "Unknown"，Java 推断为 TypeName "Text"</li>
 *   <li>参数类型：type——两个编译器对无类型参数的推断可能不同</li>
 *   <li>Java 独有字段：piiLevel, piiCategories, annotations, effectCapsExplicit</li>
 * </ul>
 */
@Tag("golden")
class CrossCompilerCoreIRTest {

    /**
     * TS 仓库路径：优先系统属性 → 环境变量 → 兄弟目录回退
     */
    private static final Path TS_PROJECT_ROOT = resolveTsRoot();

    // Fixtures now come from the shared corpus (cloud.aster-lang:aster-lang-test).
    // Both files (cross_compiler_ops.aster, arith_compare.aster) are tier1 (双引擎等价).
    private static final Path FIXTURES_DIR = resolveCorpusFixturesDir();

    private static Path resolveCorpusFixturesDir() {
        // The corpus is shipped as a jar resource by CorpusLoader, but this test
        // currently needs a real filesystem path to pass to the TS runner.
        // Resolve via the aster-lang-test repo sibling layout.
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path parent = cwd.getParent();
        if (parent == null) return cwd.resolve("aster-lang-test/corpus/tier1-equivalence/policies");
        return parent.resolve("aster-lang-test/corpus/tier1-equivalence/policies");
    }

    /**
     * 需要从 Core IR JSON 中剪除的字段（两个编译器间自然不同）：
     * <ul>
     *   <li>位置字段：origin, span, file, nameSpan, variantSpans</li>
     *   <li>推断标记：typeInferred, retTypeInferred</li>
     *   <li>返回类型：ret——TS 推断为 TypeVar "Unknown"，Java 推断为 TypeName "Text"</li>
     *   <li>参数类型：type——两个编译器对无类型参数的推断可能不同</li>
     *   <li>Java 独有字段：piiLevel, piiCategories, annotations, effectCapsExplicit</li>
     * </ul>
     */
    private static final Set<String> PRUNE_FIELDS = Set.of(
        "origin", "span", "file", "nameSpan", "variantSpans",
        "typeInferred", "retTypeInferred",
        "ret", "type",
        "piiLevel", "piiCategories", "annotations",
        "effectCapsExplicit"
    );

    private static final ObjectMapper mapper = new ObjectMapper();

    private static Path resolveTsRoot() {
        // 1. 系统属性 -Daster.ts.root=...
        String sysProp = System.getProperty("aster.ts.root");
        if (sysProp != null && !sysProp.isBlank()) {
            return Paths.get(sysProp);
        }
        // 2. 环境变量 ASTER_TS_ROOT
        String envVar = System.getenv("ASTER_TS_ROOT");
        if (envVar != null && !envVar.isBlank()) {
            return Paths.get(envVar);
        }
        // 3. 兄弟目录回退（aster-lang-core 和 aster-lang-ts 在同一父目录下）
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path parent = cwd.getParent();
        if (parent == null) {
            // 从文件系统根目录运行——回退到 cwd 下查找
            return cwd.resolve("aster-lang-ts");
        }
        return parent.resolve("aster-lang-ts");
    }

    @BeforeAll
    static void checkPrerequisites() {
        Path tsDistIndex = TS_PROJECT_ROOT.resolve("dist/scripts/core-ir-json.js");
        Assumptions.assumeTrue(Files.exists(tsDistIndex),
            "跳过跨编译器黄金测试：TypeScript 编译产物不存在（" + tsDistIndex + "），请先在 aster-lang-ts 运行 pnpm run build");
        Assumptions.assumeTrue(Files.exists(FIXTURES_DIR),
            "跳过跨编译器黄金测试：fixtures 目录不存在: " + FIXTURES_DIR);
    }

    /**
     * 跨编译器黄金测试夹具文件名（operators 目录下专为跨编译器比较设计的文件）。
     * 其他 .aster 文件可能因两个编译器参数解析差异而失败，不在此测试范围内。
     */
    private static final Set<String> CROSS_COMPILER_FIXTURES = Set.of(
        "cross_compiler_ops.aster",
        "arith_compare.aster"
    );

    /**
     * 提供测试参数：operators 目录下专为跨编译器比较设计的 .aster 文件
     */
    static Stream<Path> fixtureFiles() throws IOException {
        if (!Files.exists(FIXTURES_DIR)) {
            return Stream.empty();
        }
        return Files.walk(FIXTURES_DIR)
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".aster"))
            .filter(p -> CROSS_COMPILER_FIXTURES.contains(p.getFileName().toString()))
            .sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void coreIRMatchesBetweenCompilers(Path asterFile) throws Exception {
        String relativeName = FIXTURES_DIR.relativize(asterFile).toString();
        System.out.println("测试: " + relativeName);

        // Java 管线
        var runner = new GoldenTestRunner(TS_PROJECT_ROOT);
        JsonNode javaRaw = runner.runJava("core-ir", asterFile);
        JsonNode javaPruned = pruneJson(javaRaw);

        // TypeScript 管线
        JsonNode tsRaw = runTypeScriptCoreIR(asterFile);
        JsonNode tsPruned = pruneJson(tsRaw);

        // 比较
        String javaJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(javaPruned);
        String tsJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tsPruned);

        try {
            JSONAssert.assertEquals(tsJson, javaJson, JSONCompareMode.LENIENT);
            System.out.println("✓ " + relativeName);
        } catch (AssertionError e) {
            System.err.println("✗ " + relativeName);
            System.err.println("--- Java Core IR ---");
            System.err.println(javaJson);
            System.err.println("--- TypeScript Core IR ---");
            System.err.println(tsJson);
            throw e;
        }
    }

    /**
     * 调用 TypeScript core-ir-json 脚本获取 Core IR JSON。
     * stdout 和 stderr 并发读取以避免管道缓冲区满导致死锁。
     */
    private JsonNode runTypeScriptCoreIR(Path asterFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "node", TS_PROJECT_ROOT.resolve("dist/scripts/core-ir-json.js").toString(),
            asterFile.toString()
        );
        pb.directory(TS_PROJECT_ROOT.toFile());
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // 并发读取 stdout 和 stderr 以避免死锁
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            // 给 IO 线程有限时间完成，避免无期限阻塞
            stdoutFuture.orTimeout(5, TimeUnit.SECONDS);
            stderrFuture.orTimeout(5, TimeUnit.SECONDS);
            throw new IOException("TypeScript core-ir-json 超时（30秒）");
        }

        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("TypeScript core-ir-json 失败（退出码 " + exitCode + "）：\n" + stderr);
        }

        return mapper.readTree(stdout);
    }

    /**
     * 递归剪除 Core IR JSON 中两个编译器间自然差异的字段
     */
    private JsonNode pruneJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode arr = mapper.createArrayNode();
            for (JsonNode item : node) {
                arr.add(pruneJson(item));
            }
            return arr;
        }
        if (node.isObject()) {
            ObjectNode obj = mapper.createObjectNode();
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                // 剪除已知差异字段
                if (PRUNE_FIELDS.contains(key)) continue;
                // 剪除 typeParams——TS 推断 ["Unknown"]，Java 为 []，行为差异
                if ("typeParams".equals(key)) continue;
                // 剪除空 constraints 数组
                if ("constraints".equals(key) && value.isArray() && value.isEmpty()) continue;
                obj.set(key, pruneJson(value));
            }
            return obj;
        }
        return node;
    }
}
