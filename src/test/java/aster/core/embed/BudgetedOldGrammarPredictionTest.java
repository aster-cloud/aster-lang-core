package aster.core.embed;

import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.parser.KeywordCaseCompat;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 带时间预算的预测器在出错路径上按 K 之前的文法重新预测（KeywordCaseCompat）时，旧文法的预测器同样带预算，
 * 且自身不再回退。
 */
class BudgetedOldGrammarPredictionTest {

    @Test
    void oldGrammarPredictionIsBudgetedToo() {
        AsterParser parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString("Module m.\n"))));
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        BudgetedParserATNSimulator budgeted = new BudgetedParserATNSimulator(parser, parser.getInterpreter(),
            ParseBudget.start(Duration.ofSeconds(5)));
        assertThat(budgeted.getPredictionMode()).isEqualTo(PredictionMode.SLL);
        KeywordCaseCompat.Simulator old = budgeted.oldGrammarSimulator(parser, AsterParser._ATN,
            new DFA[0], new PredictionContextCache());
        assertThat(old).isInstanceOf(BudgetedParserATNSimulator.class);
    }

    /** 预算用完后，出错路径上的旧文法预测同样中止（不会绕过预算继续做 closure）。 */
    @Test
    void exhaustedBudgetAbortsOldGrammarPrediction() throws Exception {
        CommonTokenStream tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString("Module m.\n")));
        AsterParser parser = new AsterParser(tokens);
        ParseBudget budget = ParseBudget.start(Duration.ofNanos(1));
        Thread.sleep(2);
        BudgetedParserATNSimulator budgeted = new BudgetedParserATNSimulator(parser, parser.getInterpreter(), budget);
        DFA[] dfa = new DFA[AsterParser._ATN.getNumberOfDecisions()];
        for (int i = 0; i < dfa.length; i++) {
            dfa[i] = new DFA(AsterParser._ATN.getDecisionState(i), i);
        }
        KeywordCaseCompat.Simulator old = budgeted.oldGrammarSimulator(parser, AsterParser._ATN, dfa, new PredictionContextCache());
        assertThatThrownBy(() -> old.adaptivePredict(tokens, 0, parser.getContext()))
            .isInstanceOf(ParseBudget.Exceeded.class);
    }
}
