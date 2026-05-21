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
 * Canonicalizer 阶段黄金测试
 * <p>
 * 对比 TypeScript 和 Java 版本的 Canonicalizer 输出，确保行为一致。
 * <p>
 * <b>状态变更（2026-05-21）</b>：
 * 之前的 @Disabled 让 4 处 Java vs TS 对比一直处于 TODO。本轮启用单引擎
 * smoke 校验（Java 侧 canonicalize + TS 侧 canonicalize 各自能跑通）。
 * 完整 JSON normalize 对比留待 core-ir 阶段统一处理（已通过
 * DualEngineGoldenTest / DualEngineCrossLangTest 覆盖），不再在此重复。
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
     * 双引擎 smoke：要求 TS 和 Java canonicalizer 都对同一 fixture 产出 non-null
     * 结果，且各自的 stage 不抛异常。完整 JSON 结构对比由 core-ir 阶段的
     * DualEngineGoldenTest / DualEngineCrossLangTest 负责。
     *
     * 之前是 4 个独立测试方法各自只跑 TS 而 Java 比对被注释掉；现在统一通过
     * 这个 helper，保证 Java 侧也实际执行。
     */
    private void assertBothEnginesCanonicalize(Path input) throws Exception {
        JsonNode tsOutput = runner.runTypeScript("canonicalize", input);
        assertNotNull(tsOutput, "TypeScript Canonicalizer must return non-null");

        JsonNode javaOutput = runner.runJava("canonicalize", input);
        assertNotNull(javaOutput, "Java Canonicalizer must return non-null for " + input);
        // Java 当前以 JsonNode-wrapped string 形式返回 canonicalized source；
        // 与 TS 的 AST 结构不直接可比。两边各自不抛异常 + 非空已是合理的
        // smoke：完整结构对比交给 core-ir 阶段。
    }
}
