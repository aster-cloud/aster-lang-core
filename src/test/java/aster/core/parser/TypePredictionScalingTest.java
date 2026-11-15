package aster.core.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 类型的 ANTLR 预测代价随输入线性增长（aster-lang-core#254）。
 *
 * <p>修复前的两类病态输入：
 * <ul>
 *   <li>{@code result of} 成功 / 失败分量交替嵌套：LL 预测（core 自身各入口与 truffle 的默认模式）下每层的贪婪
 *       {@code and} 都触发全上下文预测，16 / 20 / 24 层约 0.1 / 0.2 / 0.7 秒，32 层约 9 秒；</li>
 *   <li>{@code prediction-pathological-types.aster}（取自 aster-api 的 parse-budget 夹具，随机混合嵌套、4.6KB、末尾截断）：
 *       函数类型与括号类型是两个以 {@code (} 开头的分支，选分支要越过整段括号内容，SLL 下约 46 秒、LL 同样。</li>
 * </ul>
 *
 * <p>判据不用墙钟毫秒（CI 机器快慢不一），而用预测的工作量：预测 closure 的调用次数。它只取决于文法与输入，
 * 每次测量用新的 DFA 缓存，与执行顺序、JIT、机器负载无关。断言输入规模翻倍时工作量至多涨到 {@link #MAX_GROWTH} 倍
 * （线性约为 2 倍；修复前同样的翻倍涨数千倍以上）。回归时一次解析可能长达数分钟，故 closure 次数另设硬上限
 * {@link #WORK_CAP}，超过即在模拟器内抛出中止，不留下停不掉的解析线程。
 */
class TypePredictionScalingTest {

  /** 规模翻倍时预测工作量允许的最大增幅：线性为 2，留出常数项与 DFA 构建的余量。 */
  private static final double MAX_GROWTH = 4.0;

  /** 单次解析的预测工作量硬上限：修复后各输入约 6–7 千次，修复前病态输入达数千万次以上。 */
  private static final long WORK_CAP = 2_000_000;

  /** 统计预测 closure 调用次数的模拟器；每个实例自带全新的 DFA 缓存，计数只取决于文法与输入。 */
  private static final class CountingSimulator extends ParserATNSimulator {
    long closures;

    CountingSimulator(AsterParser parser) {
      super(parser, parser.getATN(), freshDfa(parser.getATN()), new PredictionContextCache());
    }

    private static DFA[] freshDfa(ATN atn) {
      DFA[] dfa = new DFA[atn.getNumberOfDecisions()];
      for (int i = 0; i < dfa.length; i++) {
        dfa[i] = new DFA(atn.getDecisionState(i), i);
      }
      return dfa;
    }

    @Override
    protected void closure_(ATNConfig config, ATNConfigSet configs, Set<ATNConfig> closureBusy,
        boolean collectPredicates, boolean fullCtx, int depth, boolean treatEofAsEpsilon) {
      if (++closures > WORK_CAP) {
        throw new AssertionError("预测 closure 超过 " + WORK_CAP + " 次，预测代价回归（#254）");
      }
      super.closure_(config, configs, closureBusy, collectPredicates, fullCtx, depth, treatEofAsEpsilon);
    }
  }

  /** 解析一份源码（允许语法错误：病态夹具本身就是截断的），返回预测 closure 次数。 */
  private static long predictionWork(String source, PredictionMode mode) {
    String canonical = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), null).canonicalize(source);
    AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    lexer.removeErrorListeners();
    AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    CountingSimulator simulator = new CountingSimulator(parser);
    simulator.setPredictionMode(mode);
    parser.setInterpreter(simulator);
    parser.module();
    return simulator.closures;
  }

  /** result 成功 / 失败分量交替嵌套 {@code depth} 层，放在参数位置（后接逗号，预测最贵）。 */
  static String alternatingResult(int depth) {
    String type = "Int";
    for (int i = 0; i < depth; i++) {
      type = i % 2 == 0 ? "result of " + type + " and Text" : "result of Int and " + type;
    }
    return "Module m.\n\nRule f given x as " + type + ", produce Int:\n  Return 1.\n";
  }

  /** 病态夹具的参数类型截到 {@code fraction}（在空格处截断），其后补上合法的结尾。 */
  static String pathologicalPrefix(double fraction) throws IOException {
    String full;
    try (InputStream in = TypePredictionScalingTest.class.getResourceAsStream("/parser/prediction-pathological-types.aster")) {
      full = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int typeStart = full.indexOf(" as ") + 4;
    int typeEnd = full.indexOf(", produce");
    int cut = full.lastIndexOf(' ', typeStart + (int) ((typeEnd - typeStart) * fraction));
    return full.substring(0, cut) + full.substring(typeEnd);
  }

  private static void assertLinear(long small, long large, String what) {
    assertTrue(small > 0, what + "：小规模输入没有任何预测工作，测量无效");
    double growth = (double) large / small;
    assertTrue(growth <= MAX_GROWTH,
        what + "：规模翻倍后预测工作量增长 " + String.format("%.1f", growth) + " 倍（" + small + " → " + large
            + "），超过 " + MAX_GROWTH + " 倍，预测不再线性（#254）");
  }

  @ParameterizedTest(name = "result 交替嵌套，{0} 预测")
  @EnumSource(value = PredictionMode.class, names = {"SLL", "LL"})
  void alternatingResultIsLinear(PredictionMode mode) {
    long d16 = predictionWork(alternatingResult(16), mode);
    long d32 = predictionWork(alternatingResult(32), mode);
    long d62 = predictionWork(alternatingResult(62), mode);
    assertLinear(d16, d32, "16 → 32 层");
    assertLinear(d32, d62, "32 → 62 层");
  }

  @ParameterizedTest(name = "随机混合嵌套夹具，{0} 预测")
  @EnumSource(value = PredictionMode.class, names = {"SLL", "LL"})
  void pathologicalMixedTypesAreLinear(PredictionMode mode) throws IOException {
    long quarter = predictionWork(pathologicalPrefix(0.25), mode);
    long half = predictionWork(pathologicalPrefix(0.5), mode);
    long full = predictionWork(pathologicalPrefix(1.0), mode);
    assertLinear(quarter, half, "1/4 → 1/2 长度");
    assertLinear(half, full, "1/2 → 全长");
  }
}
