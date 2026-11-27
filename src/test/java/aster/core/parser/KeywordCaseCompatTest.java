package aster.core.parser;

import aster.core.lexicon.LexiconRegistry;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.atn.Transition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词大小写（K）诊断兼容层 {@link KeywordCaseCompat} 的定向测试：旧 ATN 的结构、预测器的安装、出错路径上的诊断序列。
 * 诊断序列的期望值取自 K 之前的 core（aster-lang-core f431a6b），ParseDiagnostics 路径（LL 预测、逐个取词）给出的全部诊断。
 */
class KeywordCaseCompatTest {

    private static final String H = "Module probe.sample.\n\n";

    /** 旧 ATN 只差关键词辅助规则的谓词分支（删掉）与门控规则 kwGate* 的谓词（换成 ε 转移，K-b）。 */
    @Test
    void oldAtnDiffersOnlyByKeywordRulePredicates() {
        ATN real = AsterParser._ATN;
        ATN old = KeywordCaseCompat.oldAtn();
        assertThat(old.states).hasSameSizeAs(real.states);
        assertThat(old.getNumberOfDecisions()).isEqualTo(real.getNumberOfDecisions());
        int removed = 0;
        int gates = 0;
        for (int i = 0; i < real.states.size(); i++) {
            ATNState r = real.states.get(i);
            ATNState o = old.states.get(i);
            if (r == null) {
                assertThat(o).isNull();
                continue;
            }
            assertThat(o.getStateType()).isEqualTo(r.getStateType());
            assertThat(o.ruleIndex).isEqualTo(r.ruleIndex);
            List<String> kept = describe(r, false);
            assertThat(describe(o, true)).as("状态 %d", i).isEqualTo(kept);
            removed += r.getNumberOfTransitions() - o.getNumberOfTransitions();
            for (int t = 0; t < r.getNumberOfTransitions(); t++) {
                if (isGatePredicate(r.transition(t))) {
                    gates++;
                }
            }
        }
        // 22 条关键词辅助规则（K-a 11 条、K-b 11 条），各有一条谓词分支；K-b 的 9 条门控规则各有一个谓词
        assertThat(removed).isEqualTo(22);
        assertThat(gates).isEqualTo(9);
    }

    /** 状态的转移序列（目标状态号 + 类型）；{@code old} 为假时去掉关键词辅助规则里的谓词转移、把门控谓词记为 ε 转移。 */
    private static List<String> describe(ATNState s, boolean old) {
        return java.util.stream.IntStream.range(0, s.getNumberOfTransitions())
            .mapToObj(s::transition)
            .filter(t -> old || !isKwPredicate(t))
            .map(t -> (!old && isGatePredicate(t) ? "EpsilonTransition" : t.getClass().getSimpleName())
                + "->" + t.target.stateNumber)
            .collect(Collectors.toList());
    }

    private static boolean isGatePredicate(Transition t) {
        return t instanceof PredicateTransition p && AsterParser.ruleNames[p.ruleIndex].startsWith("kwGate");
    }

    private static boolean isKwPredicate(Transition t) {
        return t instanceof PredicateTransition p && AsterParser.ruleNames[p.ruleIndex].matches("kw[A-Z].*")
            && !AsterParser.ruleNames[p.ruleIndex].startsWith("kwGate");
    }

    @Test
    void defaultInterpreterIsReplacedOnceAndKeepsPredictionMode() {
        AsterParser parser = parser("Module m.\n");
        ParserATNSimulator first = parser.getInterpreter();
        assertThat(first).isInstanceOf(KeywordCaseCompat.Simulator.class);
        first.setPredictionMode(PredictionMode.SLL);
        assertThat(parser.getInterpreter()).isSameAs(first);
        assertThat(parser.getInterpreter().getPredictionMode()).isEqualTo(PredictionMode.SLL);
    }

    @Test
    void customInterpreterIsKept() {
        AsterParser parser = parser("Module m.\n");
        ParserATNSimulator custom = new ParserATNSimulator(parser, AsterParser._ATN, parser.getInterpreter().decisionToDFA,
            parser.getInterpreter().getSharedContextCache()) { };
        parser.setInterpreter(custom);
        assertThat(parser.getInterpreter()).isSameAs(custom);
    }

    /**
     * 出错源码的全部诊断与 K 之前逐条相同：名字记号上的 sync 报错（E062 与期望集合）、单记号插入（E060）与删除，
     * 以及报过第一条错误之后的级联（之后的诊断按旧文法：后一条规则里的 GIVEN 仍报 E062）。
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "规则名之后的名字记号 Version | Rule r Version x as Int, produce Int:\\n  Return x.\\n | E062@3:8 E052@4:1 E061@5:1",
        "缺 performs（单记号插入） | Rule f given x as Int, produce Int. It does io:\\n  Return 1.\\n | E060@3:40 E061@3:45",
        "形参类型之后多一个 as | Rule f given x as Int as Int, produce Int:\\n  Return x.\\n | E062@3:23 E052@4:1 E061@5:1",
        "第一条错误之后按旧文法级联 | Rule f given x as Int, produce Int:\\n  Return x +.\\n\\nRule g GIVEN y AS Int, PRODUCE Int:\\n  Return y.\\n | E063@4:13 E062@4:13 E062@6:8 E052@7:1 E061@8:1",
    })
    void errorDiagnosticsMatchPreKeywordCaseGrammar(String name, String body, String expected) {
        String source = H + body.replace("\\n", "\n");
        var result = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow("en-US"));
        String actual = result.diagnostics().stream()
            .map(d -> d.code().code() + "@" + d.line() + ":" + d.column())
            .collect(Collectors.joining(" "));
        assertThat(actual).isEqualTo(expected);
    }

    /** 60 份变异错误源码（首条诊断 E052 / E055 / E060 / E061 / E062 / E063 各 10 份）：全部诊断连同参数与 K 之前逐条相同。 */
    @TestFactory
    Stream<DynamicTest> mutatedSourcesKeepPreKeywordCaseDiagnostics() throws IOException {
        return mutatedSources("/keyword-case/compat_diagnostics.json", 60);
    }

    /** K-b：首条诊断所在行含 K-b 关键词的 50 份变异错误源码，全部诊断连同参数与 K-b 之前（f995b84）逐条相同。 */
    @TestFactory
    Stream<DynamicTest> mutatedSourcesKeepPreStatementKeywordCaseDiagnostics() throws IOException {
        return mutatedSources("/keyword-case/compat_diagnostics_kb.json", 50);
    }

    private static Stream<DynamicTest> mutatedSources(String path, int count) throws IOException {
        JsonNode fixture;
        try (InputStream in = KeywordCaseCompatTest.class.getResourceAsStream(path)) {
            fixture = new ObjectMapper().readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture.get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                var result = ParseDiagnostics.parse(c.get("source").asText(), LexiconRegistry.getInstance().getOrThrow("en-US"));
                List<String> actual = result.diagnostics().stream().map(KeywordCaseCompatTest::render).toList();
                List<String> want = new ArrayList<>();
                for (JsonNode d : c.get("diagnostics")) {
                    Map<String, String> params = new TreeMap<>();
                    d.get("params").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
                    want.add(d.get("code").asText() + "@" + d.get("line").asInt() + ":" + d.get("column").asInt() + " " + params);
                }
                assertThat(actual).isEqualTo(want);
            }));
        }
        assertThat(fixture.get("cases")).hasSize(count);
        return tests.stream();
    }

    private static String render(ParseDiagnostic d) {
        return d.code().code() + "@" + d.line() + ":" + d.column() + " " + new TreeMap<>(d.params());
    }

    @Test
    void expectedSetIsTheOldOne() {
        var result = ParseDiagnostics.parse(H + "Rule r Version x as Int, produce Int:\n  Return x.\n",
            LexiconRegistry.getInstance().getOrThrow("en-US"));
        assertThat(result.diagnostics().get(0).params())
            .containsEntry("expected", "'.' | ':' | ',' | '<' | kw.given | kw.produce | kw.of");
    }

    private static AsterParser parser(String source) {
        return new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(source))));
    }
}
