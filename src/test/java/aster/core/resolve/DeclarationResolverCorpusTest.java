package aster.core.resolve;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.governance.ControlRegistrySiblingParityTest;
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
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R1 与 R1 → lower → R2 在无词汇时不得对既有语料产生诊断，也不改变 IR（ADR 0051 迁移安全网，对应 TS 端 0 诊断）。
 * 按每个文件的 {@code .meta.json} 驱动：仅纳入 engines 含 java 且非 {@code expect: reject} 的文件，
 * 以 meta 声明的 lexicon 解析；纳入的文件必须全部解析成功（显式登记的已知缺口除外），不允许静默跳过。
 * 自带词汇的语料（同名 {@code .vocab.json}，ADR 0051 §8）同样纳入：多数在无词汇时也应 0 诊断、IR 不变；
 * 只有源码里的名字只在词汇中声明、离开词汇必然无法解析的样本，显式登记在 {@link #VOCABULARY_DEPENDENT}
 * 中排除（其名称解析由 aster-lang-test 的 tier1 parity 门带词汇锁定），并断言它们无词汇时确实仍有诊断；
 * 无词汇时 R2 仍按设计改写 IR 的样本登记在 {@link #IR_REWRITTEN_WITHOUT_VOCABULARY}。
 * 语料来自 aster-lang-test：本地为兄弟目录，CI 为工作区子目录（见 {@link #CORPUS_CANDIDATES}）；CI 中缺失即失败，本地才跳过。
 */
public class DeclarationResolverCorpusTest {

    /**
     * 本地为兄弟目录 ../aster-lang-test；CI（ci.yml 的 checkout-sibling）把它检出到工作区子目录 ./aster-lang-test。
     * 两处都不存在时：CI 中失败，本地跳过（与 CoreIrSchemaConformanceTest 同用 ControlRegistrySiblingParityTest.locate）。
     */
    private static final List<Path> CORPUS_CANDIDATES = List.of(
        Path.of("..", "aster-lang-test", "corpus"),
        Path.of("aster-lang-test", "corpus"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 已知的 Java 解析缺口（相对语料根路径 → 原因）。显式列出而非静默跳过：断言这些文件仍解析失败，
     * 缺口修复后本表须同步删除对应条目。
     */
    public static final Map<String, String> KNOWN_PARSE_GAPS = Map.of(
        "conformance/cjk-v2/03-v2-keywords-all.aster",
        "Java 语法不支持 for-each（对每个 … 属于 …）；该用例对 java 的契约仅为规范化一致性（normalize-cjk-conformance）");

    /**
     * 依赖自带词汇才能解析的语料（相对语料根路径 → 原因）。显式列出而非按“带 .vocab.json”通配豁免：
     * 断言这些文件无词汇时仍产生诊断，不再依赖词汇后本表须同步删除对应条目。
     */
    private static final Map<String, String> VOCABULARY_DEPENDENT = Map.of(
        "tier1-parity/vocab-zh/synonyms.aster",
        "引用用词汇主词 客户 / 分数，源码只声明了同义词 顾客 / 积分；无词汇时 R1 报 E800、R2 报 E801",
        "tier1-parity/vocab-en/literal-macro-escape.aster",
        "返回值只是字面量宏触发词 archivePath（aster-lang-ts#345）；无词汇时它是未声明的名字，R2 报 E802",
        "tier1-parity/vocab-zh/literal-macro-escape.aster",
        "返回值只是字面量宏触发词 archivePath（zh-CN，aster-lang-ts#345）；无词汇时它是未声明的名字，R2 报 E802");

    /**
     * 无词汇时 R2 仍按设计改写 IR 的语料（相对语料根路径 → 原因）：不产生诊断，但规范 IR 与直接 lower 不同。
     * 两引擎行为一致（TS 同样改写），故不是迁移回归；显式登记并断言恰好这些文件 IR 有变化。
     */
    private static final Map<String, String> IR_REWRITTEN_WITHOUT_VOCABULARY = Map.of(
        "tier1-parity/vocab-de/case-only-fields.aster",
        "引用 p.Alter 与声明字段 alter 仅大小写不同，R2 按 ADR 0051 §6.3 大小写折叠解析为 p.alter（written=p.Alter）",
        "tier1-equivalence/policies/pattern-nested-name-matches-enum-member.aster",
        "Java 把中文裸名词法化为 TYPE_IDENT、解析为无参 PatCtor；嵌套位置的 其余 不是成员，R2 按 ADR 0055 #16 改写为绑定 PatName",
        // 以下四份是 ADR 0051 裁决 R55 的裸模式名样本（aster-lang-core#227，aster-lang-test#183 引入），
        // 后经 ADR 0055 #16（aster-lang-test#199）改为“枚举成员一律 PatCtor、PatName 只表示绑定”。直接 lower 只能按
        // 词法猜（TYPE_IDENT → PatCtor，否则 PatName），R2 按主语枚举的成员判定；样本自带词汇只映射辅助规则名、
        // 不映射枚举成员，故无词汇时的改写与 tier1 parity 门（带词汇、两引擎逐字节）锁定的 IR 相同。
        "tier1-parity/vocab-de/umlaut-pattern-names.aster",
        "整数主语下的 Ärger 不是成员：直接 lower 按 TYPE_IDENT 产出 PatCtor，R2 按 ADR 0055 #16 改写为绑定 PatName",
        "tier1-parity/vocab-hi/devanagari-pattern-names.aster",
        "天城文无大小写、词法化为 IDENT：直接 lower 产出 PatName，R2 把主语枚举的成员 उच्च / निम्न 改写为 PatCtor（ADR 0055 #16）",
        "tier1-parity/vocab-zh/cjk-pattern-binding.aster",
        "当 其余 / 当 其他 不是主语枚举的成员：直接 lower 按 TYPE_IDENT 产出 PatCtor，R2 按 ADR 0055 #16 改写为绑定 PatName",
        "tier1-parity/vocab-zh/enum-variant-v2.aster",
        "当 其余 不是主语枚举的成员：直接 lower 按 TYPE_IDENT 产出 PatCtor，R2 按 ADR 0055 #16 改写为绑定 PatName");

    /** 语料文件的引擎声明；lexicon 缺省为 en-US。 */
    private record Meta(boolean java, boolean reject, String lexicon) {}

    /** 纳入且解析成功的语料文件及其 AST（NoProfileIrUnchangedTest 共用）。 */
    public record Parsed(Path file, Module module) {}

    /** 定位语料根目录：CI 中两个候选都缺失即失败，本地跳过。 */
    private static Path corpus() {
        Optional<Path> located = ControlRegistrySiblingParityTest.locate(
            CORPUS_CANDIDATES, System.getenv("CI"), "aster-lang-test 以运行语料回归");
        assumeTrue(located.isPresent(), "本地未并列 checkout aster-lang-test，跳过");
        return located.get();
    }

    /** 按 meta 驱动纳入语料并解析；断言纳入的文件全部解析成功（已知缺口除外）。 */
    public static List<Parsed> parseCorpus(String label) throws IOException {
        Path corpus = corpus();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(corpus)) {
            files = walk.filter(p -> p.toString().endsWith(".aster")).sorted().toList();
        }
        int included = 0;
        List<String> parseFailures = new ArrayList<>();
        List<Parsed> out = new ArrayList<>();
        for (Path file : files) {
            Meta meta = readMeta(file);
            if (!meta.java() || meta.reject() || VOCABULARY_DEPENDENT.containsKey(key(corpus, file))) continue;
            included++;
            Module module = parse(corpus, file, meta.lexicon(), parseFailures);
            if (module != null) out.add(new Parsed(file, module));
        }
        System.out.println(label + " corpus: files=" + files.size() + " included=" + included
            + " parsed=" + out.size() + " knownGaps=" + KNOWN_PARSE_GAPS.size()
            + " vocabularyDependent=" + VOCABULARY_DEPENDENT.size());
        assertTrue(included > 0, "语料未纳入任何 java 文件");
        assertEquals(KNOWN_PARSE_GAPS.keySet().stream().sorted().toList(), parseFailures.stream().sorted().toList(),
            "engines 含 java 的非 reject 语料须全部解析成功（已知缺口除外）");
        return out;
    }

    @Test
    void javaCorpusParsesAndHasNoDiagnosticsWithoutVocabulary() throws IOException {
        Path corpus = corpus();
        List<String> findings = new ArrayList<>();
        for (Parsed p : parseCorpus("R1")) {
            DeclarationResolver.resolve(p.module(), ResolutionContext.none()).diagnostics()
                .forEach(d -> findings.add(describe(corpus, p.file(), d)));
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
        Path corpus = corpus();
        List<Parsed> parsed = parseCorpus("R1+R2");
        for (Parsed p : parsed) {
            NameResolution.ResolvedCore resolved = NameResolution.resolveAndLower(p.module(), ResolutionContext.none());
            resolved.diagnostics().forEach(d -> findings.add(describe(corpus, p.file(), d)));
            // 直接 lower 同样经过 inputRefs 标注（ADR 0046 §11 D2，只影响档案模块），本测试只比较名称解析本身的影响
            String before = MAPPER.writeValueAsString(NameResolution.withInputRefs(new CoreLowering().lowerModule(p.module())));
            if (!before.equals(MAPPER.writeValueAsString(resolved.core()))) changed.add(key(corpus, p.file()));
        }
        System.out.println("R1+R2 corpus: diagnostics=" + findings.size() + " irChanged=" + changed.size() + " of " + parsed.size());
        assertEquals(List.of(), findings);
        assertEquals(IR_REWRITTEN_WITHOUT_VOCABULARY.keySet().stream().sorted().toList(), changed.stream().sorted().toList(),
            "无词汇时名称解析不得改变 IR（显式登记的按设计改写除外）");
    }

    /** 豁免表的每一条都必须是带词汇、java 可解析的样本，且无词汇时确实仍产生诊断（防止豁免过期后继续削弱安全网）。 */
    @Test
    void vocabularyDependentExemptionsStillNeedTheirVocabulary() throws IOException {
        Path corpus = corpus();
        for (Map.Entry<String, String> entry : VOCABULARY_DEPENDENT.entrySet()) {
            Path file = corpus.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(file), "豁免条目指向的语料不存在：" + entry.getKey());
            assertTrue(carriesVocabulary(file), "豁免只适用于自带词汇的语料：" + entry.getKey());
            Meta meta = readMeta(file);
            assertTrue(meta.java() && !meta.reject(), "豁免条目须为 java 引擎的非 reject 语料：" + entry.getKey());
            List<String> parseFailures = new ArrayList<>();
            Module module = parse(corpus, file, meta.lexicon(), parseFailures);
            assertEquals(List.of(), parseFailures, "豁免条目须能解析：" + entry.getKey());
            List<Diagnostic> diagnostics = NameResolution.resolveAndLower(module, ResolutionContext.none()).diagnostics();
            assertTrue(!diagnostics.isEmpty(),
                entry.getKey() + " 无词汇时已不再产生诊断，请从 VOCABULARY_DEPENDENT 删除（原因：" + entry.getValue() + "）");
        }
    }

    private static String key(Path corpus, Path file) {
        return corpus.relativize(file).toString().replace('\\', '/');
    }

    /** 样本旁有同名词汇文件（语料自带词汇）。 */
    private static boolean carriesVocabulary(Path file) {
        return Files.isRegularFile(file.resolveSibling(file.getFileName().toString().replaceFirst("\\.aster$", ".vocab.json")));
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

    private static String describe(Path corpus, Path file, Diagnostic d) {
        return corpus.relativize(file) + ": " + d.code().code() + " " + d.message();
    }

    /** 走生产 canonicalize→lex→parse 路径；语法错误或构建异常记入 failures 并返回 null。 */
    private static Module parse(Path corpus, Path file, String lexiconId, List<String> failures) throws IOException {
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
        String key = key(corpus, file);
        failures.add(KNOWN_PARSE_GAPS.containsKey(key) ? key : key + " [" + lexiconId + "]: " + errors.get(0));
        return null;
    }
}
