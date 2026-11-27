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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词大小写（K）诊断兼容层 {@link KeywordCaseCompat} 的定向测试：旧 ATN 的结构、预测器的安装、出错路径上的诊断序列。
 * 诊断序列的期望值取自 K 之前的 core（aster-lang-core f431a6b），ParseDiagnostics 路径（LL 预测、逐个取词）给出的全部诊断。
 */
class KeywordCaseCompatTest {

    private static final String H = "Module probe.sample.\n\n";

    @Test
    void oldAtnDiffersOnlyByKeywordRulePredicates() {
        ATN real = AsterParser._ATN;
        ATN old = KeywordCaseCompat.oldAtn();
        assertThat(old.states).hasSameSizeAs(real.states);
        assertThat(old.getNumberOfDecisions()).isEqualTo(real.getNumberOfDecisions());
        int removed = 0;
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
        }
        // 11 条关键词辅助规则，各有一条谓词分支
        assertThat(removed).isEqualTo(11);
    }

    /** 状态的转移序列（目标状态号 + 类型）；{@code old} 为假时去掉关键词辅助规则里的谓词转移。 */
    private static List<String> describe(ATNState s, boolean old) {
        return java.util.stream.IntStream.range(0, s.getNumberOfTransitions())
            .mapToObj(s::transition)
            .filter(t -> old || !isKwPredicate(t))
            .map(t -> t.getClass().getSimpleName() + "->" + t.target.stateNumber)
            .collect(Collectors.toList());
    }

    private static boolean isKwPredicate(Transition t) {
        return t instanceof PredicateTransition p && AsterParser.ruleNames[p.ruleIndex].matches("kw[A-Z].*");
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
