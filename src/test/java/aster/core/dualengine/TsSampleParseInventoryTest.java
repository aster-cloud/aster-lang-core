package aster.core.dualengine;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import cloud.aster.test.CorpusLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3E follow-up: feed every sample that claims engine support for "ts"
 * (i.e. tier1 + tier2/ts-only) through the Java ANTLR parser. Per the
 * dual-engine bidirectional-equivalence principle (RFC §8.5), failures here
 * represent grammar features TS PEG accepts but Java does not.
 *
 * <p>Corpus source: {@code cloud.aster-lang:aster-lang-test} via {@link CorpusLoader}.
 *
 * <p>Tier3 fixtures are excluded — they are single-engine fixtures by design.
 *
 * <p>The test always passes; it dumps a markdown table to stdout for review.
 */
@Tag("inventory")
@DisplayName("Inventory: do tier1+ts-only samples parse under the Java parser?")
class TsSampleParseInventoryTest {

    @Test
    @DisplayName("Parse every TS-engine sample with Java ANTLR")
    void inventory() {
        List<CorpusLoader.Sample> all = CorpusLoader.listAll();
        // Only scan samples that TS accepts (tier1 + tier2/ts-only); tier3 is
        // single-engine fixture and would skew the equivalence number.
        List<CorpusLoader.Sample> samples = all.stream()
            .filter(s -> s.meta.engines.contains("ts") && s.meta.tier != 3)
            .toList();

        System.out.println("\n=== TS-engine sample → Java parser inventory ===");
        System.out.println("Corpus source: cloud.aster-lang:aster-lang-test");
        System.out.println("Discovered " + samples.size() + " samples (tier1 + tier2/ts-only)");
        System.out.println();
        System.out.println("| Sample | Java parse | First error |");
        System.out.println("|---|---|---|");

        int pass = 0, fail = 0;
        List<Map.Entry<String, String>> failures = new ArrayList<>();

        for (CorpusLoader.Sample sample : samples) {
            String source = sample.readSource();
            CollectingErrorListener listener = new CollectingErrorListener();
            try {
                AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(source));
                lexer.removeErrorListeners();
                lexer.addErrorListener(listener);

                CommonTokenStream tokens = new CommonTokenStream(lexer);
                tokens.fill();
                tokens.seek(0);

                AsterParser parser = new AsterParser(tokens);
                parser.removeErrorListeners();
                parser.addErrorListener(listener);
                parser.module();
            } catch (Throwable t) {
                listener.errors.add("THROWN: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

            if (listener.errors.isEmpty()) {
                pass++;
            } else {
                String first = listener.errors.get(0).replace("|", "\\|");
                if (first.length() > 140) first = first.substring(0, 137) + "...";
                System.out.printf("| %s | ❌ | %s |%n", sample.resourcePath, first);
                failures.add(Map.entry(sample.resourcePath, String.join(" / ", listener.errors)));
                fail++;
            }
        }
        System.out.println();
        System.out.printf("Total: %d, Pass: %d, Fail: %d, Pass-rate: %.1f%%%n",
            samples.size(), pass, fail,
            samples.isEmpty() ? 0.0 : (100.0 * pass / samples.size()));

        if (!failures.isEmpty()) {
            Map<String, Integer> tally = new LinkedHashMap<>();
            for (var entry : failures) {
                String sig = entry.getValue().split(" / ")[0];
                String key = sig.replaceAll("L\\d+:\\d+ ", "")
                                .replaceAll("'[^']+'", "'<TOKEN>'");
                if (key.length() > 100) key = key.substring(0, 97) + "...";
                tally.merge(key, 1, Integer::sum);
            }
            System.out.println("\n=== Failure clusters (root-cause tally) ===");
            tally.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %4d × %s%n", e.getValue(), e.getKey()));
        }

        // ── 断言（2026-07-29 审计修复）──────────────────────────────────
        //
        // 本测试原本**一条断言都没有**，Javadoc 自陈「The test always passes」。
        // 而 corpus-regression.yml 的过滤器 `--tests '*Corpus*'` 匹配 0 个类
        // （CorpusLoaderTest 在 aster-lang-test 仓），于是这个无断言的 inventory
        // 是该 workflow **唯一真正执行**的测试——整条 gate 只打印表格然后恒绿。
        //
        // 保留它的诊断性质（不追求 100%，4 个已知 TS-only 特性允许存在），
        // 但补两条不变量，让它至少能挡住「真回归」和「语料没加载」：

        assertFalse(samples.isEmpty(),
            "未发现任何 TS 引擎样本——语料未加载（空集合遍历会让所有统计恒真）。"
                + "corpus 依赖 cloud.aster-lang:aster-lang-test，检查它是否已发布到 mavenLocal。");

        // 棘轮：允许已知的 4 个失败，但不允许更多。数字只应下降，不应上升；
        // 若确有新的 TS-only 特性需要豁免，应显式改这个基线并在 PR 说明理由。
        assertTrue(fail <= MAX_KNOWN_PARSE_FAILURES,
            String.format("Java 解析失败数 %d 超过已知基线 %d —— 疑似语法回归。"
                + "失败样本见上方 failure clusters。若为有意新增的 TS-only 特性，"
                + "请调整 MAX_KNOWN_PARSE_FAILURES 并说明。", fail, MAX_KNOWN_PARSE_FAILURES));
    }

    /**
     * 已知的 Java 解析失败数基线（2026-07-29 实测 222 样本中 4 例失败，通过率 98.2%）。
     *
     * <p>这些是 TS PEG 接受而 Java ANTLR 尚不支持的语法特性，属已知差距而非回归。
     * ★本数字只应随着差距消解而**下降**。
     */
    private static final int MAX_KNOWN_PARSE_FAILURES = 4;

    private static final class CollectingErrorListener extends BaseErrorListener {
        final List<String> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            errors.add("L" + line + ":" + charPositionInLine + " " + msg);
        }
    }
}
