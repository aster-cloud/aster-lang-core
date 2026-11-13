package aster.core.ir;

import aster.core.resolve.DeclarationResolverCorpusTest;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 对 aster-lang-test 语料的输出全部符合 Core IR v2（ADR 0055 §3「合规检查」；plan Task C5）。
 * 语料纳入口径与 {@link DeclarationResolverCorpusTest} 相同（meta 声明 java 引擎、非 reject）。每份输出经规范序列化后：
 * schema 与不变式 0 个问题；{@link CoreIrJson#read} 能严格读回；读回后再序列化与原字节相同。
 */
class CoreIrSchemaConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void corpus_output_conforms() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (DeclarationResolverCorpusTest.Parsed p : DeclarationResolverCorpusTest.parseCorpus("conformance")) {
            NameResolution.ResolvedCore resolved = NameResolution.resolveAndLower(p.module(), ResolutionContext.none());
            if (NameResolution.hasErrors(resolved.diagnostics())) {
                problems.add(p.file() + "：名称解析失败 " + resolved.diagnostics());
                continue;
            }
            String canonical = CoreIrJson.serialize(resolved.core());
            checked++;
            List<CoreIrProblem> found = CoreIrJson.validate(MAPPER.readTree(canonical));
            if (!found.isEmpty()) {
                problems.add(p.file() + "：" + found);
                continue;
            }
            try {
                if (!canonical.equals(CoreIrJson.serialize(CoreIrJson.read(canonical)))) {
                    problems.add(p.file() + "：读回后再序列化的字节不同");
                }
            } catch (CoreIrFormatException e) {
                problems.add(p.file() + "：严格读回失败 " + e.getMessage());
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(checked > 300, "纳入的语料过少：" + checked);
    }
}
