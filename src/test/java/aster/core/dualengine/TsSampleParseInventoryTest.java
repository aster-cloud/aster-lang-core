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
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;

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
            String rawSource = sample.readSource();
            // ★按样本声明的 lexicon 规范化（2026-08-19 修复 issue #107 的元缺陷）。
            //
            //   此前这里直接把**原始源码**喂给英语词法器，完全不看 meta.lexicon。
            //   后果：非英语样本（zh-CN 的「模块 …」等）本该由对应词法表翻译成
            //   canonical 英语后再解析，却被当英语硬解 —— 于是这份「清单」对
            //   非英语样本给出的通过/失败结论**与真实解析能力无关**。
            //   实测加一个 zh-CN 样本后 parse parity 报 220/220 通过，
            //   而 Java 侧根本没能解析它。
            //
            //   现改为 fail-closed：声明了 lexicon 就必须能取到，取不到即失败，
            //   而不是静默退回英语（静默退回正是这个缺口长期存在的原因）。
            String source = canonicalizeFor(sample, rawSource);
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
     * 已知的 Java 解析失败数基线。
     *
     * <p>2026-07-29：222 样本中 4 例失败（98.2%）。
     * <p>2026-08-19：**降到 1**（223 样本，99.6%）。
     *
     * <p>★这次下降不是新增了语法支持，而是修掉了本测试自己的缺陷：
     * 此前它把原始源码直接喂英语词法器、不看 {@code meta.lexicon}，
     * 于是 4 个 zh-CN 样本必然失败——而它们恰好占满了这个基线，
     * 把「测试没按词法表规范化」伪装成了「Java 语法能力不足」。
     * 按词法表规范化后实测：219/223 → 222/223，Java **本来就能解析**中文样本。
     *
     * <p>本数字只应随着差距消解而**下降**。
     */
    private static final int MAX_KNOWN_PARSE_FAILURES = 1;

    private static final class CollectingErrorListener extends BaseErrorListener {
        final List<String> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            errors.add("L" + line + ":" + charPositionInLine + " " + msg);
        }
    }

    /**
     * 按样本声明的 {@code meta.lexicon} 规范化源码。
     *
     * <p>未声明或声明为 en-US → 用默认（英语）Canonicalizer；
     * 声明了其它词法表 → 必须能从 {@link LexiconRegistry} 取到，**取不到即失败**。
     * 绝不静默退回英语——那正是「非英语样本假通过」这个元缺陷的成因。
     */
    private static String canonicalizeFor(CorpusLoader.Sample sample, String rawSource) {
        String id = sample.meta.lexicon;
        if (id == null || id.isBlank() || "en-US".equals(id)) {
            return new Canonicalizer().canonicalize(rawSource);
        }
        Lexicon lexicon = LexiconRegistry.getInstance().get(id).orElseThrow(
            () -> new AssertionError(
                "样本 " + sample.resourcePath + " 声明 lexicon=" + id
                + "，但 LexiconRegistry 取不到该词法表。"
                + "不得静默退回英语——那会让本清单对非英语样本给出与真实解析能力无关的结论。"));
        return new Canonicalizer(lexicon).canonicalize(rawSource);
    }
}
