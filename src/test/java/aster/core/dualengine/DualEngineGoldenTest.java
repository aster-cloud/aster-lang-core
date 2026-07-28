package aster.core.dualengine;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import cloud.aster.test.CorpusLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check for the shared dual-engine corpus.
 *
 * <p>Corpus source: {@code cloud.aster-lang:aster-lang-test} via {@link CorpusLoader}.
 *
 * <p>This used to live alongside an embedded {@code src/test/resources/dual-engine/}
 * directory; that directory has been migrated to {@code aster-lang-test} and
 * the assertions here reference it through the Maven artifact.
 */
@DisplayName("Dual-engine golden corpus (sanity)")
class DualEngineGoldenTest {

    @Test
    @DisplayName("Corpus contains at least the historical baseline of tier1 goldens")
    void baselineSize() {
        // Historical baseline: 23 of 30 Java corpus samples had .cases.json.
        // After post-migration reclassify, 7 of those moved to tier2/ts-only
        // because Java actually rejects their `If ... :` form. The remaining
        // 15 are the true bidirectionally-equivalent golden subset; assert
        // ≥ 15 to catch corpus deletion.
        long withGolden = CorpusLoader.listTier(CorpusLoader.Tier.TIER1).stream()
            .filter(s -> s.readCases() != null)
            .count();
        assertThat(withGolden).isGreaterThanOrEqualTo(15);
    }

    @TestFactory
    @DisplayName("Corpus sanity: every tier1 sample with golden is well-formed")
    Stream<DynamicContainer> corpusIsWellFormed() {
        List<CorpusLoader.Sample> samples = CorpusLoader.listTier(CorpusLoader.Tier.TIER1);
        List<DynamicContainer> containers = new ArrayList<>();

        for (CorpusLoader.Sample sample : samples) {
            JsonNode cases = sample.readCases();
            if (cases == null) continue; // syntax-only tier1 samples have no golden

            String displayName = sample.resourcePath
                .replaceFirst("^corpus/tier1-equivalence/policies/", "");

            containers.add(DynamicContainer.dynamicContainer(displayName, Stream.of(
                DynamicTest.dynamicTest("source non-empty", () -> {
                    assertThat(sample.readSource()).isNotEmpty();
                }),
                DynamicTest.dynamicTest("cases.entry present", () -> {
                    assertThat(cases.path("entry").asText()).isNotEmpty();
                }),
                DynamicTest.dynamicTest("cases array non-empty", () -> {
                    assertThat(cases.path("cases").size()).isGreaterThan(0);
                }),
                DynamicTest.dynamicTest("every case has name/input + expectedOutput 或 expectError", () -> {
                    for (JsonNode c : cases.path("cases")) {
                        assertThat(c.has("name")).isTrue();
                        assertThat(c.has("input")).isTrue();
                        // 错误路径用例（aster-lang-test#69）用 expectError:true 断言
                        // "两个引擎都必须拒绝该输入"，此时刻意**不写** expectedOutput
                        // （没有正常返回值可断言）。两者二选一，且互斥——与 JS 侧
                        // scripts/lib/eval-cases.mjs 的 validateEvalCasesDocument 同一约定。
                        boolean hasOutput = c.has("expectedOutput");
                        boolean expectsError = c.path("expectError").asBoolean(false);
                        assertThat(hasOutput || expectsError)
                            .as("case '%s' 必须有 expectedOutput 或 expectError:true", c.path("name").asText())
                            .isTrue();
                        assertThat(hasOutput && expectsError)
                            .as("case '%s' 不得同时有 expectedOutput 与 expectError（互斥）", c.path("name").asText())
                            .isFalse();
                    }
                })
            )));
        }

        return containers.stream();
    }
}
