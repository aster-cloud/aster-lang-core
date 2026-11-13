package aster.core.dualengine;

import aster.core.ir.CoreIrFormatException;
import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreIrProblem;
import aster.core.resolve.DeclarationResolverCorpusTest;
import aster.core.typecheck.governance.ControlRegistrySiblingParityTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Java 对 aster-lang-test 语料的输出全部符合 Core IR v2（ADR 0055 §3「合规检查」；plan Task C5）。
 *
 * <p>与 parity 门禁走同一条路径：{@link CoreIrFingerprintCli#emitOne}，按 meta 声明的词法表规范化，样本自带
 * {@code .vocab.json} 时以该词汇做名称解析。纳入 meta 声明 java 引擎、非 reject 的全部样本：除登记在
 * {@link DeclarationResolverCorpusTest#KNOWN_PARSE_GAPS} 的已知解析缺口外都必须编译成功，每份 {@code irCanonical}
 * 都必须通过 schema 与不变式，严格读回后再序列化逐字节相同。
 */
class CoreIrSchemaConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 本地为兄弟目录 ../aster-lang-test；CI（ci.yml 的 checkout-sibling）把它检出到工作区子目录 ./aster-lang-test。
     * 两处都不存在时：CI 中失败，本地跳过（ControlRegistrySiblingParityTest.locate 同一规则）。
     */
    private static final List<Path> CORPUS_CANDIDATES = List.of(
        Path.of("..", "aster-lang-test", "corpus"),
        Path.of("aster-lang-test", "corpus"));

    @Test
    void corpus_output_conforms() throws Exception {
        java.util.Optional<Path> located = ControlRegistrySiblingParityTest.locate(CORPUS_CANDIDATES, System.getenv("CI"));
        assumeTrue(located.isPresent(), "本地未并列 checkout aster-lang-test，跳过");
        Path corpus = located.get();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        int withVocabulary = 0;
        List<String> failed = new ArrayList<>();
        for (Path file : javaSamples(corpus)) {
            JsonNode meta = MAPPER.readTree(sibling(file, ".meta.json").toFile());
            Path vocab = sibling(file, ".vocab.json");
            boolean hasVocab = Files.isRegularFile(vocab);
            JsonNode rec = CoreIrFingerprintCli.emitOne(new CoreIrFingerprintCli.Request(
                file.toAbsolutePath().toString(), meta.path("lexicon").asText(null),
                hasVocab ? vocab.toAbsolutePath().toString() : null), true);
            String key = corpus.relativize(file).toString().replace('\\', '/');
            if (!rec.path("ok").asBoolean(false)) {
                failed.add(key);
                if (!DeclarationResolverCorpusTest.KNOWN_PARSE_GAPS.containsKey(key)) {
                    problems.add(key + "：编译失败 " + rec);
                }
                continue;
            }
            checked++;
            if (hasVocab) withVocabulary++;
            String canonical = rec.path("irCanonical").asText();
            List<CoreIrProblem> found = CoreIrJson.validate(MAPPER.readTree(canonical));
            if (!found.isEmpty()) {
                problems.add(corpus.relativize(file) + "：" + found);
                continue;
            }
            try {
                if (!canonical.equals(CoreIrJson.serialize(CoreIrJson.read(canonical)))) {
                    problems.add(corpus.relativize(file) + "：读回后再序列化的字节不同");
                }
            } catch (CoreIrFormatException e) {
                problems.add(corpus.relativize(file) + "：严格读回失败 " + e.getMessage());
            }
        }
        System.out.println("conformance: checked=" + checked + " withVocabulary=" + withVocabulary);
        assertEquals(List.of(), problems);
        // 已知解析缺口必须仍然失败；缺口修复后应从 DeclarationResolverCorpusTest.KNOWN_PARSE_GAPS 删除
        assertEquals(DeclarationResolverCorpusTest.KNOWN_PARSE_GAPS.keySet().stream().sorted().toList(), failed.stream().sorted().toList());
        // 2026-11-14（aster-lang-test b595d6a）java 引擎、非 reject 的样本中 323 份编译成功（含带词汇的 26 份）；
        // 低于此数说明纳入口径或编译回退
        assertTrue(checked >= 320, "纳入的语料过少：" + checked);
        assertTrue(withVocabulary > 20, "带词汇的样本过少：" + withVocabulary);
    }

    /** meta 声明 java 引擎且 expect 不是 reject 的样本。 */
    private static List<Path> javaSamples(Path corpus) throws IOException {
        try (Stream<Path> walk = Files.walk(corpus)) {
            return walk.filter(p -> p.toString().endsWith(".aster")).sorted().filter(p -> {
                try {
                    Path metaPath = sibling(p, ".meta.json");
                    if (!Files.isRegularFile(metaPath)) return false;
                    JsonNode meta = MAPPER.readTree(metaPath.toFile());
                    return StreamSupport.stream(meta.path("engines").spliterator(), false).anyMatch(e -> "java".equals(e.asText()))
                        && !"reject".equals(meta.path("expect").asText());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName().toString().replaceFirst("\\.aster$", suffix));
    }
}
