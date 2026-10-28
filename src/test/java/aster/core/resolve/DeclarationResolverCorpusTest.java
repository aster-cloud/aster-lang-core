package aster.core.resolve;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R1 与 R1 → lower → R2 在无词汇时不得对既有语料产生诊断，也不改变 IR（ADR 0051 迁移安全网，对应 TS 端 0 诊断）。
 * 按每个文件的 {@code .meta.json} 驱动：仅纳入 engines 含 java 且非 {@code expect: reject} 的文件，
 * 以 meta 声明的 lexicon 解析；纳入的文件必须全部解析成功（显式登记的已知缺口除外），不允许静默跳过。
 * 语料来自同级仓库 aster-lang-test，缺失时跳过。
 */
class DeclarationResolverCorpusTest {

    private static final Path CORPUS = Path.of("..", "aster-lang-test", "corpus");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 已知的 Java 解析缺口（相对语料根路径 → 原因）。显式列出而非静默跳过：断言这些文件仍解析失败，
     * 缺口修复后本表须同步删除对应条目。
     */
    private static final Map<String, String> KNOWN_PARSE_GAPS = Map.of(
        "conformance/cjk-v2/03-v2-keywords-all.aster",
        "Java 语法不支持 for-each（对每个 … 属于 …）；该用例对 java 的契约仅为规范化一致性（normalize-cjk-conformance）");

    /** 语料文件的引擎声明；lexicon 缺省为 en-US。 */
    private record Meta(boolean java, boolean reject, String lexicon) {}

    /** 纳入且解析成功的语料文件及其 AST。 */
    private record Parsed(Path file, Module module) {}

    /** 按 meta 驱动纳入语料并解析；断言纳入的文件全部解析成功（已知缺口除外）。 */
    private static List<Parsed> parseCorpus(String label) throws IOException {
        assumeTrue(Files.isDirectory(CORPUS), "缺少 aster-lang-test/corpus");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CORPUS)) {
            files = walk.filter(p -> p.toString().endsWith(".aster")).sorted().toList();
        }
        int included = 0;
        List<String> parseFailures = new ArrayList<>();
        List<Parsed> out = new ArrayList<>();
        for (Path file : files) {
            Meta meta = readMeta(file);
            if (!meta.java() || meta.reject()) continue;
            included++;
            Module module = parse(file, meta.lexicon(), parseFailures);
            if (module != null) out.add(new Parsed(file, module));
        }
        System.out.println(label + " corpus: files=" + files.size() + " included=" + included
            + " parsed=" + out.size() + " knownGaps=" + KNOWN_PARSE_GAPS.size());
        assertTrue(included > 0, "语料未纳入任何 java 文件");
        assertEquals(KNOWN_PARSE_GAPS.keySet().stream().sorted().toList(), parseFailures.stream().sorted().toList(),
            "engines 含 java 的非 reject 语料须全部解析成功（已知缺口除外）");
        return out;
    }

    @Test
    void javaCorpusParsesAndHasNoDiagnosticsWithoutVocabulary() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Parsed p : parseCorpus("R1")) {
            DeclarationResolver.resolve(p.module(), ResolutionContext.none()).diagnostics()
                .forEach(d -> findings.add(describe(p.file(), d)));
        }
        assertEquals(List.of(), findings);
    }

    /**
     * R1 → lower → R2（无词汇）：0 诊断，且 Core IR 与未经名称解析的直接 lower 逐字节相同
     * （名称解析接入流水线不改变任何既有语料的 IR）。
     */
    @Test
    void javaCorpusR1R2HasNoDiagnosticsAndKeepsIrUnchanged() throws IOException {
        List<String> findings = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<Parsed> parsed = parseCorpus("R1+R2");
        for (Parsed p : parsed) {
            NameResolution.ResolvedCore resolved = NameResolution.resolveAndLower(p.module(), ResolutionContext.none());
            resolved.diagnostics().forEach(d -> findings.add(describe(p.file(), d)));
            String before = MAPPER.writeValueAsString(new CoreLowering().lowerModule(p.module()));
            if (!before.equals(MAPPER.writeValueAsString(resolved.core()))) changed.add(CORPUS.relativize(p.file()).toString());
        }
        System.out.println("R1+R2 corpus: diagnostics=" + findings.size() + " irChanged=" + changed.size() + " of " + parsed.size());
        assertEquals(List.of(), findings);
        assertEquals(List.of(), changed, "无词汇时名称解析不得改变 IR");
    }

    private static Meta readMeta(Path file) throws IOException {
        Path metaPath = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.aster$", ".meta.json"));
        assertTrue(Files.isRegularFile(metaPath), "缺少 meta：" + metaPath);
        JsonNode meta = MAPPER.readTree(metaPath.toFile());
        boolean java = StreamSupport.stream(meta.path("engines").spliterator(), false)
            .anyMatch(e -> "java".equals(e.asText()));
        boolean reject = "reject".equals(meta.path("expect").asText());
        return new Meta(java, reject, meta.path("lexicon").asText("en-US"));
    }

    private static String describe(Path file, Diagnostic d) {
        return CORPUS.relativize(file) + ": " + d.code().code() + " " + d.message();
    }

    /** 走生产 canonicalize→lex→parse 路径；语法错误或构建异常记入 failures 并返回 null。 */
    private static Module parse(Path file, String lexiconId, List<String> failures) throws IOException {
        String source = Files.readString(file);
        List<String> errors = new ArrayList<>();
        BaseErrorListener collect = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
                errors.add("L" + line + ":" + col + " " + msg);
            }
        };
        try {
            Canonicalizer canonicalizer = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow(lexiconId), null);
            AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonicalizer.canonicalize(source)));
            lexer.removeErrorListeners();
            lexer.addErrorListener(collect);
            AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(collect);
            AsterParser.ModuleContext tree = parser.module();
            if (errors.isEmpty()) return new AstBuilder().visitModule(tree);
        } catch (RuntimeException e) {
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        String key = CORPUS.relativize(file).toString().replace('\\', '/');
        failures.add(KNOWN_PARSE_GAPS.containsKey(key) ? key : key + " [" + lexiconId + "]: " + errors.get(0));
        return null;
    }
}
