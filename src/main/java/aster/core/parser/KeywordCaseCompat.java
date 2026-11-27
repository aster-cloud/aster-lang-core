package aster.core.parser;

import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.BlockStartState;
import org.antlr.v4.runtime.atn.DecisionState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LL1Analyzer;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键词大小写（K，ADR 0056 修订 A4 §1.1）的诊断兼容层：K 只放宽关键词位置，错误源码的诊断尽量与 K 之前相同。
 *
 * <p>K 的关键词辅助规则（{@code kwGiven : GIVEN | {isKwAt(1, "given")}? (TYPE_IDENT | IDENT)} 等）让名字记号出现在
 * 这些位置的 LL(1) 集合里。ANTLR 的错误处理看不到谓词：名字记号（如 {@code Rule r Version x} 的 {@code Version}）
 * 以前在可选子规则入口由 {@code sync} 直接报「意外的 X，期望 …」（E062），现在 {@code sync} 放行、预测时谓词全部失败，
 * 报成笼统的 E063；期望集合里也会多出名字记号。修复前后两个引擎对同一份错误源码的诊断因此会漂移。
 *
 * <p>这里保留一份「去掉 K 谓词分支」的 ATN（与 K 之前的文法同构：关键词辅助规则只剩硬记号分支，状态编号不变），
 * 只在出错路径上用它：
 * <ul>
 *   <li>{@link Simulator}：真实预测无可行分支时，以及报过第一条语法错误之后，按旧 ATN 的决策选分支（旧文法是 LL(1)
 *       的决策按 LL(1) 生成代码的行为：取下一记号所在分支，可选块 / 循环退出，否则无可行分支），错误随后在与旧文法相同的
 *       位置、以相同方式报出，错误恢复与级联诊断也与 K 之前相同；</li>
 *   <li>{@link ErrorStrategy}：{@code sync}、单记号插入、恢复集合按旧 ATN 计算；只有下一记号的文本恰是此处 K 谓词
 *       认可的关键词（且尚未报错）时才按新文法放行；</li>
 *   <li>{@link #expectedTokens}：诊断里的期望集合按旧 ATN 计算。</li>
 * </ul>
 * 能解析的源码不经过这里（真实预测成功即返回），所以兼容层不改变任何接受集合与解析结果。仍会变的只有一类：K 的谓词在预测
 * 深处按成立处理，真实预测因此可能越过旧文法会失败的位置，第一条错误落在更靠后、真正出错的记号上（多为 E063 变成更具体的码）。
 *
 * <p>K-b 另外：
 * <ul>
 *   <li>左边缘门控规则 {@code kwGate*}（只含一个谓词）的谓词换成 ε 转移而不是删掉：门控对 K 之前能走通的记号序列恒为真；</li>
 *   <li>SLL 预测走到出错记号、本该退而取「已走完决策规则」的分支时（{@link Simulator#getSynValidOrSemInvalidAltThatFinishedDecisionEntryRule}），
 *       改按无可行分支交给旧文法预测：K 的名字分支在 SLL 的全局 FOLLOW 里造出的假路径会让这类分支与 K 之前不同。</li>
 * </ul>
 */
public final class KeywordCaseCompat {

    /** 关键词辅助规则的规则名前缀；其中的谓词分支就是 K 新增的读法。 */
    private static final String KW_RULE_PREFIX = "kw";

    /** 左边缘门控规则的规则名前缀（K-b）：只含一个谓词、不消费记号，对 K 之前的记号序列恒为真。 */
    private static final String GATE_RULE_PREFIX = "kwGate";

    /** 标记记号的起点：{@link KeywordAnalyzer} 用「起点 + 规则序号」在 LOOK 集合里标出可达的关键词谓词。 */
    private static final int KW_MARKER_BASE = 1 << 20;

    private static final ATN REAL_ATN = AsterParser._ATN;
    private static final BitSet KW_RULES = kwRules();
    private static final BitSet GATE_RULES = gateRules();
    /** 旧 ATN：真实 ATN 去掉关键词辅助规则里的谓词分支。 */
    private static final ATN OLD_ATN = buildOldAtn();
    private static final DFA[] OLD_DFA = buildDfa(OLD_ATN);
    private static final PredictionContextCache OLD_CACHE = new PredictionContextCache();
    /** 旧 ATN 上各决策的 LL(1) 前瞻；不是 LL(1) 决策的记为空数组。 */
    private static final Map<Integer, IntervalSet[]> OLD_LL1 = new ConcurrentHashMap<>();
    /** 真实 ATN 上各状态在一个记号之内可达的 K 关键词。 */
    private static final Map<Integer, Set<String>> KW_WORDS_AT = new ConcurrentHashMap<>();
    private static final IntervalSet[] NOT_LL1 = new IntervalSet[0];

    private KeywordCaseCompat() {
    }

    private static BitSet kwRules() {
        BitSet rules = new BitSet();
        String[] names = AsterParser.ruleNames;
        for (int i = 0; i < names.length; i++) {
            if (names[i].startsWith(KW_RULE_PREFIX) && names[i].length() > KW_RULE_PREFIX.length()
                    && Character.isUpperCase(names[i].charAt(KW_RULE_PREFIX.length()))
                    && !names[i].startsWith(GATE_RULE_PREFIX)) {
                rules.set(i);
            }
        }
        return rules;
    }

    private static BitSet gateRules() {
        BitSet rules = new BitSet();
        String[] names = AsterParser.ruleNames;
        for (int i = 0; i < names.length; i++) {
            if (names[i].startsWith(GATE_RULE_PREFIX)) {
                rules.set(i);
            }
        }
        return rules;
    }

    /** 关键词辅助规则对应的关键词：{@code kwPerforms} → {@code performs}。 */
    private static String kwWord(int ruleIndex) {
        return AsterParser.ruleNames[ruleIndex].substring(KW_RULE_PREFIX.length()).toLowerCase(Locale.ROOT);
    }

    private static boolean isKwPredicate(Transition t) {
        return t instanceof PredicateTransition p && KW_RULES.get(p.ruleIndex);
    }

    private static boolean isGatePredicate(Transition t) {
        return t instanceof PredicateTransition p && GATE_RULES.get(p.ruleIndex);
    }

    /** 旧 ATN：删掉关键词辅助规则的谓词分支；门控规则的谓词换成 ε 转移（恒为真，见 {@link #GATE_RULE_PREFIX}）。 */
    private static ATN buildOldAtn() {
        ATN atn = new ATNDeserializer().deserialize(AsterParser._serializedATN.toCharArray());
        for (ATNState s : atn.states) {
            if (s == null) {
                continue;
            }
            for (int i = s.getNumberOfTransitions() - 1; i >= 0; i--) {
                Transition t = s.transition(i);
                if (isKwPredicate(t)) {
                    s.removeTransition(i);
                } else if (isGatePredicate(t)) {
                    s.setTransition(i, new EpsilonTransition(t.target));
                }
            }
        }
        return atn;
    }

    private static DFA[] buildDfa(ATN atn) {
        DFA[] dfa = new DFA[atn.getNumberOfDecisions()];
        for (int i = 0; i < dfa.length; i++) {
            dfa[i] = new DFA(atn.getDecisionState(i), i);
        }
        return dfa;
    }

    /** 旧 ATN（只读，供结构校验测试）。 */
    static ATN oldAtn() {
        return OLD_ATN;
    }

    /** 旧 ATN 上从 {@code state} 起、在 {@code ctx} 下的期望记号（与 {@link ATN#getExpectedTokens} 同义）。 */
    public static IntervalSet expectedTokens(int state, RuleContext ctx) {
        return OLD_ATN.getExpectedTokens(state, ctx);
    }

    /** 诊断用的期望集合：解析器异常按旧 ATN 计算，其余照旧。 */
    public static IntervalSet expectedTokens(RecognitionException e) {
        if (e.getRecognizer() instanceof AsterParser && e.getOffendingState() >= 0) {
            return expectedTokens(e.getOffendingState(), e.getCtx());
        }
        return e.getExpectedTokens();
    }

    /** 下一记号是名字记号，且其文本是 {@code state} 处 K 谓词认可的关键词。 */
    static boolean acceptsKeywordText(Parser parser, int state) {
        Token t = parser.getInputStream().LT(1);
        if (t == null || (t.getType() != AsterParser.IDENT && t.getType() != AsterParser.TYPE_IDENT)
                || EscapedToken.isEscaped(t) || t.getText() == null) {
            return false;
        }
        Set<String> words = KW_WORDS_AT.computeIfAbsent(state, KeywordCaseCompat::kwWordsAt);
        if (words.isEmpty()) {
            return false;
        }
        String lower = t.getText().toLowerCase(Locale.ROOT);
        return words.contains(lower);
    }

    private static Set<String> kwWordsAt(int state) {
        IntervalSet look = new KeywordAnalyzer(REAL_ATN).LOOK(REAL_ATN.states.get(state), null, (RuleContext) null);
        Set<String> words = new HashSet<>();
        for (int type : look.toList()) {
            if (type >= KW_MARKER_BASE) {
                int rule = type - KW_MARKER_BASE;
                words.add(kwWord(rule));
            }
        }
        return Set.copyOf(words);
    }

    /** LOOK 走到关键词谓词分支时不越过谓词，改为记一个标记记号（起点 + 规则序号）。 */
    private static final class KeywordAnalyzer extends LL1Analyzer {
        KeywordAnalyzer(ATN atn) {
            super(atn);
        }

        @Override
        protected void _LOOK(ATNState s, ATNState stopState, PredictionContext ctx, IntervalSet look,
                             Set<ATNConfig> lookBusy, BitSet calledRuleStack, boolean seeThruPreds, boolean addEOF) {
            if (s.getNumberOfTransitions() == 1 && isKwPredicate(s.transition(0))) {
                look.add(KW_MARKER_BASE + s.ruleIndex);
                return;
            }
            super._LOOK(s, stopState, ctx, look, lookBusy, calledRuleStack, seeThruPreds, addEOF);
        }
    }

    /**
     * 旧文法在这个决策上的选择。关键词辅助规则自身的决策在旧文法里不存在（直接匹配硬记号）：取下一记号所在的分支，
     * 否则取第一个分支，由随后的匹配按旧文法报错。
     */
    static int oldPrediction(Parser parser, ParserATNSimulator old, TokenStream input, int decision,
                             ParserRuleContext outerContext) {
        DecisionState ds = OLD_ATN.getDecisionState(decision);
        IntervalSet[] look = OLD_LL1.computeIfAbsent(decision, d -> ll1Lookahead(ds));
        int la = input.LA(1);
        if (KW_RULES.get(ds.ruleIndex)) {
            for (int i = 0; i < look.length; i++) {
                if (look[i] != null && look[i].contains(la)) {
                    return i + 1;
                }
            }
            return 1;
        }
        if (look.length == 0) {
            return old.adaptivePredict(input, decision, outerContext);
        }
        for (int i = 0; i < look.length; i++) {
            if (look[i].contains(la)) {
                return i + 1;
            }
        }
        int exit = exitAlt(ds);
        if (exit > 0) {
            return exit;
        }
        // 旧文法 LL(1) 必选块的生成代码在这里抛出以当前记号为起止的无可行分支
        Token current = parser.getCurrentToken();
        throw new StacklessNoViableAlt(parser, input, current, current, null, parser.getContext());
    }

    /** 旧 ATN 上的 LL(1) 前瞻（同 ANTLR 工具的判定：各分支集合非空、无谓词、两两不交）；否则 {@link #NOT_LL1}。 */
    private static IntervalSet[] ll1Lookahead(DecisionState ds) {
        IntervalSet[] look = new LL1Analyzer(OLD_ATN).getDecisionLookahead(ds);
        if (KW_RULES.get(ds.ruleIndex)) {
            return look;
        }
        for (int i = 0; i < look.length; i++) {
            if (look[i] == null) {
                return NOT_LL1;
            }
            for (int j = 0; j < i; j++) {
                if (!look[i].and(look[j]).isNil()) {
                    return NOT_LL1;
                }
            }
        }
        return look;
    }

    /** 可选块 / 循环的退出分支序号（贪婪时是最后一个分支）；必选块返回 0。 */
    private static int exitAlt(DecisionState ds) {
        int n = ds.getNumberOfTransitions();
        return switch (ds.getStateType()) {
            case ATNState.STAR_LOOP_ENTRY, ATNState.PLUS_LOOP_BACK -> ds.nonGreedy ? 1 : n;
            case ATNState.BLOCK_START -> {
                BlockStartState bs = (BlockStartState) ds;
                Transition last = ds.transition(n - 1);
                yield !ds.nonGreedy && last instanceof EpsilonTransition && last.target == bs.endState ? n : 0;
            }
            default -> 0;
        };
    }

    /**
     * 预测器：K 文法的预测成功即返回；无可行分支时、以及已报过语法错误之后（错误恢复与级联诊断），按旧文法选分支
     * （见 {@link #oldPrediction}）。旧文法的预测器由
     * {@link #oldGrammarSimulator} 建出（带时间预算的子类让它同样受预算约束），自身不再回退。
     *
     * <p>无可行分支异常不填栈：出错路径上真实预测与旧文法预测各可能抛出一次，填栈的开销会让错误源码的解析明显变慢，
     * 而这些异常只经错误监听器转成诊断，从不打印栈。
     */
    public static class Simulator extends ParserATNSimulator {
        private final boolean fallback;
        private Simulator old;

        public Simulator(Parser parser, ATN atn, DFA[] decisionToDFA, PredictionContextCache sharedContextCache) {
            this(parser, atn, decisionToDFA, sharedContextCache, true);
        }

        protected Simulator(Parser parser, ATN atn, DFA[] decisionToDFA, PredictionContextCache sharedContextCache,
                            boolean fallback) {
            super(parser, atn, decisionToDFA, sharedContextCache);
            this.fallback = fallback;
        }

        /** 旧文法（{@code atn} 是旧 ATN）的预测器；不回退。 */
        protected Simulator oldGrammarSimulator(Parser parser, ATN atn, DFA[] decisionToDFA,
                                                PredictionContextCache sharedContextCache) {
            return new Simulator(parser, atn, decisionToDFA, sharedContextCache, false);
        }

        @Override
        public int adaptivePredict(TokenStream input, int decision, ParserRuleContext outerContext) {
            if (!fallback) {
                return super.adaptivePredict(input, decision, outerContext);
            }
            // 已报过语法错误：之后的错误恢复与级联诊断一律按旧文法，与 K 之前逐条相同
            if (parser.getNumberOfSyntaxErrors() > 0) {
                return predictOld(input, decision, outerContext);
            }
            try {
                return super.adaptivePredict(input, decision, outerContext);
            } catch (NoViableAltException e) {
                return predictOld(input, decision, outerContext);
            }
        }

        private int predictOld(TokenStream input, int decision, ParserRuleContext outerContext) {
            if (old == null) {
                old = oldGrammarSimulator(parser, OLD_ATN, OLD_DFA, OLD_CACHE);
            }
            old.setPredictionMode(getPredictionMode());
            return oldPrediction(parser, old, input, decision, outerContext);
        }

        /** 正在做全上下文（LL）预测的层数：SLL 阶段为 0。 */
        private int fullContextDepth;

        @Override
        protected int execATNWithFullContext(DFA dfa, DFAState d, ATNConfigSet s0, TokenStream input, int startIndex,
                                             ParserRuleContext outerContext) {
            fullContextDepth++;
            try {
                return super.execATNWithFullContext(dfa, d, s0, input, startIndex, outerContext);
            } finally {
                fullContextDepth--;
            }
        }

        /**
         * SLL 预测在某个记号上走不通时，ANTLR 退而取「已走完决策所在规则」的分支，由外层报错。SLL 走出决策规则后按全局 FOLLOW
         * 继续，K 的关键词辅助规则又让名字记号在外层许多关键词位置（and / or 续行、构造字段…）都能被消费，这类假路径比 K 之前多：
         * K 之前 SLL 在这些出错位置先报全冲突、改用全上下文 LL 精确预测，K 之后假路径打破了全冲突，SLL 一路走到出错记号，
         * 再按假路径选分支，出错源码因而报出别的诊断（`Return WITH flag.` 从软比较词的 E063 变成 E061）。
         * 这里在 SLL 阶段不取这类分支，按无可行分支交给旧文法预测（{@link #predictOld}）：旧 ATN 没有这些假路径，SLL 在 K 之前的
         * 位置报全冲突（LL 模式随之改用全上下文预测，全程 SLL 取最小分支），或同样退而取走完决策规则的分支。
         * LL 阶段与类型之内照旧。
         *
         * <p>只影响出错源码：SLL 的每一步 reach 集都是全上下文 LL 的超集（SLL 合并调用栈、在决策规则之外按全局 FOLLOW 继续，
         * 谓词一律当作成立），SLL 在某个记号上 reach 为空，说明该记号在任何调用上下文里都接不上，源码在此处必然有语法错误。
         * KeywordCaseTest 的接受夹具同时走生产路径（InProcessCnlParser，全程 SLL）与 ParseDiagnostics（LL），锁定这一点。
         */
        @Override
        protected int getSynValidOrSemInvalidAltThatFinishedDecisionEntryRule(ATNConfigSet configs,
                                                                              ParserRuleContext outerContext) {
            if (!fallback || fullContextDepth > 0 || insideType(outerContext)) {
                return super.getSynValidOrSemInvalidAltThatFinishedDecisionEntryRule(configs, outerContext);
            }
            return ATN.INVALID_ALT_NUMBER;
        }

        /** 决策是否在类型之内（annotatedType 规则之下）：类型内的出错路径照旧（K-a 起即如此，诊断夹具按它记录）。 */
        private static boolean insideType(RuleContext ctx) {
            for (RuleContext c = ctx; c != null; c = c.parent) {
                if (c.getRuleIndex() == AsterParser.RULE_annotatedType) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected NoViableAltException noViableAlt(TokenStream input, ParserRuleContext outerContext,
                                                   ATNConfigSet configs, int startIndex) {
            return new StacklessNoViableAlt(parser, input, input.get(startIndex), input.LT(1), configs, outerContext);
        }
    }

    /** 不填栈的无可行分支异常（见 {@link Simulator}）。 */
    private static final class StacklessNoViableAlt extends NoViableAltException {
        StacklessNoViableAlt(Parser parser, TokenStream input, Token startToken, Token offendingToken,
                             ATNConfigSet deadEndConfigs, ParserRuleContext ctx) {
            super(parser, input, startToken, offendingToken, deadEndConfigs, ctx);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * 错误策略：与 {@link DefaultErrorStrategy} 相同，只是 {@code sync}、单记号插入、恢复集合与报错文案里的期望集合按旧 ATN
     * 计算；下一记号恰是此处 K 谓词认可的关键词、且尚未报过语法错误时 {@code sync} 照新文法放行。
     */
    public static class ErrorStrategy extends DefaultErrorStrategy {

        @Override
        public void sync(Parser recognizer) throws RecognitionException {
            if (inErrorRecoveryMode(recognizer)) {
                return;
            }
            int state = recognizer.getState();
            ATNState s = OLD_ATN.states.get(state);
            // 关键词辅助规则在旧文法里只是一个硬记号的匹配，没有 sync 点：出错时由随后的匹配做单记号插入 / 删除
            if (KW_RULES.get(s.ruleIndex)) {
                return;
            }
            int la = recognizer.getInputStream().LA(1);
            IntervalSet nextTokens = OLD_ATN.nextTokens(s);
            boolean keyword = recognizer.getNumberOfSyntaxErrors() == 0 && acceptsKeywordText(recognizer, state);
            if (nextTokens.contains(la) || keyword) {
                nextTokensContext = null;
                nextTokensState = ATNState.INVALID_STATE_NUMBER;
                return;
            }
            if (nextTokens.contains(Token.EPSILON)) {
                if (nextTokensContext == null) {
                    nextTokensContext = recognizer.getContext();
                    nextTokensState = state;
                }
                return;
            }
            switch (s.getStateType()) {
                case ATNState.BLOCK_START, ATNState.STAR_BLOCK_START, ATNState.PLUS_BLOCK_START,
                     ATNState.STAR_LOOP_ENTRY -> {
                    if (singleTokenDeletion(recognizer) != null) {
                        return;
                    }
                    throw new InputMismatchException(recognizer);
                }
                case ATNState.PLUS_LOOP_BACK, ATNState.STAR_LOOP_BACK -> {
                    reportUnwantedToken(recognizer);
                    IntervalSet expecting = recognizer.getExpectedTokens();
                    consumeUntil(recognizer, expecting.or(getErrorRecoverySet(recognizer)));
                }
                default -> {
                }
            }
        }

        @Override
        protected void reportInputMismatch(Parser recognizer, InputMismatchException e) {
            String msg = "mismatched input " + getTokenErrorDisplay(e.getOffendingToken())
                + " expecting " + expectedTokens(e).toString(recognizer.getVocabulary());
            recognizer.notifyErrorListeners(e.getOffendingToken(), msg, e);
        }

        @Override
        protected boolean singleTokenInsertion(Parser recognizer) {
            int current = recognizer.getInputStream().LA(1);
            ATNState next = OLD_ATN.states.get(recognizer.getState()).transition(0).target;
            if (OLD_ATN.nextTokens(next, recognizer.getContext()).contains(current)) {
                reportMissingToken(recognizer);
                return true;
            }
            return false;
        }

        @Override
        protected IntervalSet getErrorRecoverySet(Parser recognizer) {
            RuleContext ctx = recognizer.getContext();
            IntervalSet recoverSet = new IntervalSet();
            while (ctx != null && ctx.invokingState >= 0) {
                RuleTransition rt = (RuleTransition) OLD_ATN.states.get(ctx.invokingState).transition(0);
                recoverSet.addAll(OLD_ATN.nextTokens(rt.followState));
                ctx = ctx.parent;
            }
            recoverSet.remove(Token.EPSILON);
            return recoverSet;
        }
    }
}
