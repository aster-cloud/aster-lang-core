package aster.core.test;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 黄金测试运行器
 * <p>
 * 用于对比 TypeScript 和 Java 编译器的输出，确保迁移后的 Java 实现与原 TypeScript 版本行为一致。
 * <p>
 * <b>工作流程</b>：
 * <ol>
 *   <li>运行 TypeScript 编译器生成基线输出（JSON 格式）</li>
 *   <li>运行 Java 编译器生成实际输出（JSON 格式）</li>
 *   <li>使用 JSONAssert 对比两者差异，忽略字段顺序</li>
 * </ol>
 * <p>
 * <b>使用示例</b>：
 * <pre>{@code
 * var runner = new GoldenTestRunner(Path.of("."));
 * JsonNode tsOutput = runner.runTypeScript("canonicalize", Path.of("test/cnl/examples/hello.aster"));
 * JsonNode javaOutput = runner.runJava("canonicalize", Path.of("test/cnl/examples/hello.aster"));
 * runner.assertJsonEquals(tsOutput, javaOutput);
 * }</pre>
 */
public class GoldenTestRunner {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path projectRoot;

    /**
     * 创建黄金测试运行器
     *
     * @param projectRoot 项目根目录
     */
    public GoldenTestRunner(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * 运行 TypeScript 编译器生成输出
     * <p>
     * 调用 npm 命令运行 TypeScript 编译管线的指定阶段。
     *
     * @param stage     编译阶段名称（如 "canonicalize", "parse", "typecheck"）
     * @param inputFile 输入源文件路径（相对于项目根目录）
     * @return JSON 输出节点
     * @throws IOException          如果文件读取或进程执行失败
     * @throws InterruptedException 如果进程等待被中断
     */
    public JsonNode runTypeScript(String stage, Path inputFile) throws IOException, InterruptedException {
        // 构造 npm 命令（例如：npm run canonicalize -- test/cnl/examples/hello.aster）
        ProcessBuilder pb = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb.command("cmd", "/c", "npm", "run", stage, "--", inputFile.toString());
        } else {
            pb.command("npm", "run", stage, "--", inputFile.toString());
        }

        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = readProcessOutput(process);

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("TypeScript 编译器超时（30秒）");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("TypeScript 编译器执行失败（退出码 " + exitCode + "）：\n" + output);
        }

        return mapper.readTree(output);
    }

    /**
     * 运行 Java 编译器生成输出
     * <p>
     * <b>注意</b>：当前为占位实现，实际实现将在各编译阶段迁移完成后填充。
     *
     * @param stage     编译阶段名称
     * @param inputFile 输入源文件路径
     * @return JSON 输出节点
     * @throws IOException 如果文件读取失败
     */
    public JsonNode runJava(String stage, Path inputFile) throws IOException {
        String source = Files.readString(inputFile);

        return switch (stage) {
            case "canonicalize" -> {
                var canonicalizer = new Canonicalizer();
                String canonicalized = canonicalizer.canonicalize(source);
                yield mapper.valueToTree(canonicalized);
            }
            case "core-ir" -> {
                // 完整管线：Canonicalize → ANTLR Parse → AstBuilder → CoreLowering → JSON
                var canonicalizer = new Canonicalizer();
                String canonicalized = canonicalizer.canonicalize(source);

                var charStream = CharStreams.fromString(canonicalized);
                var lexer = new AsterCustomLexer(charStream);
                var errorCollector = new FailFastErrorListener();
                lexer.removeErrorListeners();
                lexer.addErrorListener(errorCollector);

                var tokens = new CommonTokenStream(lexer);
                tokens.fill();
                tokens.seek(0);

                var parser = new AsterParser(tokens);
                parser.removeErrorListeners();
                parser.addErrorListener(errorCollector);
                AsterParser.ModuleContext moduleCtx = parser.module();
                errorCollector.throwIfErrors();

                var builder = new AstBuilder();
                aster.core.ast.Module ast = builder.visitModule(moduleCtx);

                var lowering = new CoreLowering();
                CoreModel.Module coreModule = lowering.lowerModule(ast);

                yield mapper.valueToTree(coreModule);
            }
            default -> throw new UnsupportedOperationException(
                "Java 编译器尚未实现阶段：" + stage +
                "。请在迁移对应编译阶段后实现此方法。"
            );
        };
    }

    /**
     * 对比两个 JSON 输出是否等价
     * <p>
     * 使用 JSONAssert 进行宽松对比，忽略字段顺序但严格检查值。
     *
     * @param expected 期望输出（TypeScript 生成）
     * @param actual   实际输出（Java 生成）
     * @throws AssertionError 如果两个 JSON 不等价
     */
    public void assertJsonEquals(JsonNode expected, JsonNode actual) {
        try {
            String expectedJson = mapper.writeValueAsString(expected);
            String actualJson = mapper.writeValueAsString(actual);

            // 使用 LENIENT 模式：忽略数组顺序和额外字段，但严格检查值类型
            JSONAssert.assertEquals(expectedJson, actualJson, JSONCompareMode.LENIENT);
        } catch (JSONException | IOException e) {
            throw new AssertionError("JSON 对比失败", e);
        }
    }

    /**
     * 读取进程输出流
     *
     * @param process 进程对象
     * @return 输出字符串
     * @throws IOException 如果读取失败
     */
    private String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString();
        }
    }

    /**
     * 获取项目根目录
     *
     * @return 项目根目录路径
     */
    public Path getProjectRoot() {
        return projectRoot;
    }

    /**
     * ANTLR 错误监听器：收集所有语法错误，解析结束后抛出 IOException
     */
    private static final class FailFastErrorListener extends BaseErrorListener {
        private final StringBuilder errors = new StringBuilder();
        private int count = 0;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            count++;
            errors.append(String.format("  第 %d 行第 %d 列: %s%n", line, charPositionInLine, msg));
        }

        void throwIfErrors() throws IOException {
            if (count > 0) {
                throw new IOException("ANTLR 解析器发现 " + count + " 个语法错误：\n" + errors);
            }
        }
    }
}
