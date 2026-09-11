package aster.core.ir;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core IR 确定性门禁 —— 同一份源码反复编译必须产出**字节相同**的 IR JSON。
 *
 * <h2>为什么需要这条门禁</h2>
 *
 * 「可追溯性」（OriginMap / MappingIR / IR hash / 回归比对）全部建立在一个
 * 未经验证的假设上：<b>同一份源码编译两次得到同一个 IR</b>。审计发现该假设
 * 当前<b>为假</b>，且此前没有任何测试会因此变红：
 *
 * <ul>
 *   <li><b>注解参数顺序</b>：{@code AstBuilder.visitAnnotation} 用
 *       {@code LinkedHashMap} 按源码顺序收集参数后，末尾用 {@code Map.copyOf}
 *       转不可变——JDK 不可变 Map 的迭代序按启动期 SALT 随机化，于是
 *       {@code CoreModel.Annotation.params} 的序列化顺序<b>每次 JVM 启动都可能不同</b>。
 *       落点覆盖 {@code Func.annotations} / {@code Func.retAnnotations} /
 *       {@code Field.annotations} / {@code Param.annotations} 四处 IR 字段。</li>
 *   <li><b>多模块拓扑序</b>：{@code ModuleGraph.topologicalOrder()} 以
 *       {@code modules.keySet()} 为遍历起点，而该 Map 同样是 {@code Map.copyOf}
 *       的随机迭代序，导致等价拓扑解之间的选择不稳定 → 合并后 {@code decls} 漂移。</li>
 * </ul>
 *
 * <h2>为什么以前抓不到</h2>
 *
 * 不是「测试通过了」，而是<b>结构上不存在能触发它的样本</b>：实测全语料中
 * 带 ≥2 个参数的注解<b>命中数为 0</b>。因此本测试<b>自带</b>触发样本
 * （{@link #MULTI_PARAM_ANNOTATION_SOURCE}）——语料不覆盖时，门禁必须自己造。
 *
 * <h2>比较口径（关键）</h2>
 *
 * 必须用 <b>Jackson 原生序列化</b>逐字节比较，<b>不能</b>用：
 * <ul>
 *   <li>{@code JSONAssert}（宽松模式忽略字段顺序——恰好忽略掉本缺陷）；</li>
 *   <li>{@link aster.core.canonical.CanonicalJson}（它对 object 键<b>排序</b>，
 *       会把乱序的 {@code params} 重新排齐，从而<b>掩盖</b>缺陷）。</li>
 * </ul>
 * 换言之：canonical 化是「让不同来源可比」的工具，而这里要验的恰恰是
 * 「同一来源是否自我一致」，两者口径相反。
 *
 * <h2>单 JVM 内复现的局限（诚实标注）</h2>
 *
 * 不可变 Map 的 SALT 在<b>单个 JVM 生命周期内固定</b>，因此同一进程里编译 N 次
 * 得到的顺序是一致的——单靠本测试<b>无法</b>在一次运行中复现 SALT 漂移。
 * 本测试锁住的是「同一 JVM 内多次编译自我一致」这条较弱但必要的不变量，
 * 外加一条<b>结构性</b>断言（见 {@link #annotationParamsMustPreserveSourceOrder()}）：
 * 直接断言参数顺序等于<b>源码书写顺序</b>——这条与 SALT 无关，任何一次运行中
 * 只要实现回退到 {@code Map.copyOf} 就会变红（实测：6 次独立 JVM 跑出 4 种顺序）。
 */
class IrDeterminismTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 触发样本：带 ≥2 个具名参数的注解。
     * <p>
     * ★全语料对此形态的覆盖为 0，故门禁自带样本。参数名刻意选用不同首字母
     * 且与字典序<b>不一致</b>的书写顺序（zeta → alpha → mid），这样一旦实现
     * 改成「按键排序」也不会被误判为通过——我们要的是<b>源码顺序</b>，
     * 不是任何一种稳定顺序。
     */
    private static final String MULTI_PARAM_ANNOTATION_SOURCE = """
        Module demo.determinism.

        @audit(zeta: 1, alpha: 2, mid: 3) Rule main given x, produce:
          Return x plus 1.
        """;

    private static final String ANNOTATED_FIELD_SOURCE = """
        Module demo.entry.

        @entry Rule main given x, produce:
          Return x plus 1.

        Rule helper given y, produce:
          Return y times 2.
        """;

    @Test
    @DisplayName("同一 JVM 内重复编译：IR JSON 必须逐字节相同")
    void repeatedCompilationMustBeByteIdentical() {
        for (String source : new String[]{MULTI_PARAM_ANNOTATION_SOURCE, ANNOTATED_FIELD_SOURCE}) {
            Set<String> distinct = new LinkedHashSet<>();
            for (int i = 0; i < 20; i++) {
                distinct.add(serializeIr(source));
            }
            assertEquals(1, distinct.size(),
                "同一份源码编译 20 次产出了 " + distinct.size() + " 种不同的 IR JSON。"
                    + "IR 不可复现 ⇒ OriginMap / IR hash / 回归比对全部失去意义。源码：\n" + source);
        }
    }

    /**
     * 结构性断言：注解参数的序列化顺序必须等于<b>源码书写顺序</b>。
     * <p>
     * ★这条是本测试的核心守卫，且<b>与 SALT 无关</b>——它不依赖「多次运行碰巧
     * 撞出不同顺序」，而是直接钉死正确口径。若实现回退为
     * {@code Map.copyOf(params)}，本断言在任意一次运行中都有极高概率变红
     * （实测 6 次 JVM 出现 4 种顺序，只有其中一种与源码序巧合一致）。
     */
    @Test
    @DisplayName("注解参数顺序必须等于源码书写顺序（不是字典序，也不是随机序）")
    void annotationParamsMustPreserveSourceOrder() {
        String json = serializeIr(MULTI_PARAM_ANNOTATION_SOURCE);

        // 先确认样本确实带上了多参数注解——否则本门禁会在「没有被测对象」的
        // 情况下空转通过（本仓踩过多次的假绿形态）。
        int zeta = json.indexOf("\"zeta\"");
        int alpha = json.indexOf("\"alpha\"");
        int mid = json.indexOf("\"mid\"");
        assertTrue(zeta >= 0 && alpha >= 0 && mid >= 0,
            "样本未产出预期的三个注解参数（zeta/alpha/mid），门禁失去针对性。实际 IR：\n" + json);

        // 源码书写顺序是 zeta → alpha → mid，刻意与字典序（alpha<mid<zeta）
        // 不同：这样「按键排序」的实现也会变红，我们要的是源码序本身。
        assertTrue(zeta < alpha && alpha < mid,
            "注解参数顺序与源码书写顺序不符。期望 zeta < alpha < mid，实际偏移 "
                + "zeta=" + zeta + " alpha=" + alpha + " mid=" + mid
                + "。\n★若顺序看起来像字典序，说明实现改成了排序（掩盖问题而非修复）；"
                + "\n★若顺序每次运行都不同，说明 params 仍走 Map.copyOf 的随机迭代序。"
                + "\n实际 IR：\n" + json);
    }

    /** Java 完整编译管线：源码 → CoreModel → Jackson 原生 JSON（保留 Map 迭代序）。 */
    private static String serializeIr(String source) {
        String canonicalized = new Canonicalizer().canonicalize(source);
        var charStream = CharStreams.fromString(canonicalized);
        var lexer = new AsterCustomLexer(charStream);
        var tokens = new CommonTokenStream(lexer);
        var parser = new aster.core.parser.AsterParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new FailFastErrorListener());
        var moduleCtx = parser.module();
        aster.core.ast.Module ast = new AstBuilder().visitModule(moduleCtx);
        CoreModel.Module core = new CoreLowering().lowerModule(ast);
        try {
            return MAPPER.writeValueAsString(core);
        } catch (Exception e) {
            throw new IllegalStateException("IR 序列化失败", e);
        }
    }

    private static final class FailFastErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new IllegalStateException("解析错误 @" + line + ":" + charPositionInLine + " " + msg);
        }
    }
}
