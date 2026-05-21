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
 * Canonicalizer 阶段双引擎 SMOKE 测试（非 equivalence test）
 * <p>
 * <b>状态变更（2026-05-21）</b>：
 * 之前的 @Disabled 让 4 处 Java vs TS 对比一直处于 TODO。本轮启用的是
 * <b>smoke 校验</b>：仅断言两侧引擎对同一 fixture 各自不抛异常且返回 non-null。
 * <b>不</b>做结构化 JSON 对比 —— canonicalize 阶段的两侧输出格式差异较大
 * （TS 返回 AST 节点 JSON，Java 返回 canonicalized source 字符串），
 * 需要在 lowered Core IR 那一层才有可比较的 schema。完整 equivalence 校验
 * 由 {@code DualEngineGoldenTest} 和 {@code DualEngineCrossLangTest} 在
 * Core IR 阶段负责。
 * <p>
 * 这意味着：本测试不会发现 canonicalize 阶段的语义漂移 —— 它只能证明两侧
 * 实现都能跑通管线。要捕获漂移，需要在 Core IR 阶段加 normalization
 * + JSON-equal 断言（见 codex Round-3 后续建议）。
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
