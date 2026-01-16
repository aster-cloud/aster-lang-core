package aster.core.test;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonicalizer 阶段黄金测试
 * <p>
 * 对比 TypeScript 和 Java 版本的 Canonicalizer 输出，确保行为一致。
 * <p>
 * <b>当前状态</b>：占位实现，等待 Canonicalizer Java 版本完成后激活。
 */
@Tag("golden")
@Disabled("等待 Canonicalizer Java 实现完成")
public class CanonicalizeGoldenTest {

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
        Path input = Path.of("test/cnl/examples/hello.aster");

        // 运行 TypeScript 版本
        JsonNode tsOutput = runner.runTypeScript("canonicalize", input);
        assertNotNull(tsOutput, "TypeScript Canonicalizer 应返回有效输出");

        // 运行 Java 版本（当前会抛出 UnsupportedOperationException）
        // JsonNode javaOutput = runner.runJava("canonicalize", input);

        // 对比输出
        // runner.assertJsonEquals(tsOutput, javaOutput);

        // 临时验证：确保 TypeScript 版本可以运行
        assertTrue(tsOutput.has("kind"), "输出应包含 kind 字段");
    }

    /**
     * 测试：数据类型声明的规范化
     */
    @Test
    void testDataDeclCanonicalize() throws Exception {
        Path input = Path.of("test/cnl/examples/person.aster");

        // 运行 TypeScript 版本
        JsonNode tsOutput = runner.runTypeScript("canonicalize", input);
        assertNotNull(tsOutput);

        // TODO: 激活 Java 版本对比
        // JsonNode javaOutput = runner.runJava("canonicalize", input);
        // runner.assertJsonEquals(tsOutput, javaOutput);
    }

    /**
     * 测试：函数声明的规范化
     */
    @Test
    void testFuncDeclCanonicalize() throws Exception {
        Path input = Path.of("test/cnl/examples/math.aster");

        // 运行 TypeScript 版本
        JsonNode tsOutput = runner.runTypeScript("canonicalize", input);
        assertNotNull(tsOutput);

        // TODO: 激活 Java 版本对比
    }

    /**
     * 测试：枚举声明的规范化
     */
    @Test
    void testEnumDeclCanonicalize() throws Exception {
        Path input = Path.of("test/cnl/examples/status.aster");

        // 运行 TypeScript 版本
        JsonNode tsOutput = runner.runTypeScript("canonicalize", input);
        assertNotNull(tsOutput);

        // TODO: 激活 Java 版本对比
    }

    /**
     * 测试：复杂表达式的规范化
     */
    @Test
    void testComplexExprCanonicalize() throws Exception {
        Path input = Path.of("test/cnl/examples/calculator.aster");

        // 运行 TypeScript 版本
        JsonNode tsOutput = runner.runTypeScript("canonicalize", input);
        assertNotNull(tsOutput);

        // TODO: 激活 Java 版本对比
    }
}
