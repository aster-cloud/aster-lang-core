package aster.core.ir;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lexicon.DynamicLexicon;
import aster.core.lexicon.Lexicon;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ADR 0028 待办：**显式块的跨编译器 Core IR parity**（TS ↔ Java 逐字节）。
 *
 * <p>tier1-parity harness 结构上测不了显式块（它 canonicalize 用默认 lexicon、样本是预
 * canonical 英文，无 block-end 词不触发 explicit 路径）。故本测试专门做：同一份**单行化**
 * 显式块源码 + 同一 block-end 配置（en-US + fin），两引擎各自产出 Core IR，剪除自然差异
 * 字段后 JSONAssert 比对——证明「显式块（单行化）在两引擎编译到同一 Core IR」。
 *
 * <p>TS 侧走 `node dist/scripts/core-ir-json.js <file> --block-end=fin`；Java 侧内联管线
 * （Canonicalizer(en-US+fin) → ANTLR → AstBuilder → CoreLowering）。剪除字段与
 * {@link CrossCompilerCoreIRTest} 的 PRUNE_FIELDS 契约一致。
 */
@Tag("golden")
class ExplicitBlockCrossCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path TS_ROOT = resolveTsRoot();

    // 与 CrossCompilerCoreIRTest.PRUNE_FIELDS 保持同步。
    private static final Set<String> PRUNE_FIELDS = Set.of(
        "origin", "span", "file", "nameSpan", "variantSpans",
        "typeInferred", "retTypeInferred", "ret", "type",
        "piiLevel", "piiCategories", "annotations", "effectCapsExplicit"
    );

    // 单行化的显式块源码（ADR 0028）：整个函数体在一行，以 fin 收尾——证明源码可单行化。
    private static final String EXPLICIT_SINGLE_LINE =
        "Module Detective.\n\n"
        + "Rule solve given clueA as Bool, produce Text:if clueA then return \"guilty\" else return \"innocent\".fin";

    /** en-US builtin 叠加 blockDelimiters.end=["fin"]（同脚本：en 配英文词，ADR 0028 §6）。 */
    private static Lexicon enWithFin() throws Exception {
        String json = new String(
            ExplicitBlockCrossCompilerTest.class.getClassLoader()
                .getResourceAsStream("builtin/en-US.json").readAllBytes(),
            StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        ArrayNode end = root.putObject("blockDelimiters").putArray("end");
        end.add("fin");
        return DynamicLexicon.fromJsonString(MAPPER.writeValueAsString(root));
    }

    @Test
    void explicitBlockCoreIRMatchesBetweenCompilers() throws Exception {
        Path tsScript = TS_ROOT.resolve("dist/scripts/core-ir-json.js");
        Assumptions.assumeTrue(Files.exists(tsScript),
            "跳过：TS 编译产物不存在（" + tsScript + "），先在 aster-lang-ts 运行 pnpm build");

        // Java 侧：内联管线，用 en-US + fin lexicon。
        JsonNode javaPruned = prune(javaCoreIR(EXPLICIT_SINGLE_LINE, enWithFin()));

        // TS 侧：spawn node 脚本，--block-end=fin。写临时文件传给脚本。
        Path tmp = Files.createTempFile("explicit-block", ".aster");
        try {
            Files.writeString(tmp, EXPLICIT_SINGLE_LINE, StandardCharsets.UTF_8);
            JsonNode tsPruned = prune(tsCoreIR(tsScript, tmp));

            String javaJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(javaPruned);
            String tsJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tsPruned);
            try {
                JSONAssert.assertEquals(tsJson, javaJson, JSONCompareMode.LENIENT);
            } catch (AssertionError e) {
                System.err.println("--- Java Core IR ---\n" + javaJson);
                System.err.println("--- TS Core IR ---\n" + tsJson);
                throw e;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Java 完整管线（Canonicalize(lex) → ANTLR → AstBuilder → CoreLowering）→ Core IR JSON。 */
    private JsonNode javaCoreIR(String source, Lexicon lexicon) {
        String canonical = new Canonicalizer(lexicon).canonicalize(source);
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        AsterParser parser = new AsterParser(tokens);
        AsterParser.ModuleContext ctx = parser.module();
        if (parser.getNumberOfSyntaxErrors() > 0) {
            throw new IllegalStateException("Java parse errors for canonical:\n" + canonical);
        }
        aster.core.ast.Module ast = new AstBuilder().visitModule(ctx);
        CoreModel.Module core = new CoreLowering().lowerModule(ast);
        return MAPPER.valueToTree(core);
    }

    /** 调 TS core-ir-json.js --block-end=fin。 */
    private JsonNode tsCoreIR(Path tsScript, Path asterFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "node", tsScript.toString(), asterFile.toString(), "--block-end=fin");
        pb.directory(TS_ROOT.toFile());
        Process p = pb.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()));
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("TS core-ir-json 超时");
        }
        if (p.exitValue() != 0) {
            throw new IOException("TS core-ir-json 失败（退出码 " + p.exitValue() + "）：\n" + err.join());
        }
        return MAPPER.readTree(out.join());
    }

    private static String readAll(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 剪除两编译器自然差异字段（与 CrossCompilerCoreIRTest 契约一致）。 */
    private JsonNode prune(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            node.forEach(e -> arr.add(prune(e)));
            return arr;
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (PRUNE_FIELDS.contains(k)) return;
                if (k.equals("typeParams")) return; // TS 推断 ["Unknown"] / Java []，剪除
                out.set(k, prune(e.getValue()));
            });
            return out;
        }
        return node;
    }

    private static Path resolveTsRoot() {
        String sys = System.getProperty("aster.ts.root");
        if (sys != null && !sys.isBlank()) return Path.of(sys);
        String env = System.getenv("ASTER_TS_ROOT");
        if (env != null && !env.isBlank()) return Path.of(env);
        // 兄弟目录回退
        return Path.of(System.getProperty("user.dir")).resolveSibling("aster-lang-ts");
    }
}
