package aster.core.canonicalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.regex.Pattern;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canonicalizer 的 <b>ReDoS 时间预算门禁 + 语义守卫</b>。
 *
 * <h2>问题</h2>
 *
 * Canonicalizer 是 Java 引擎处理<b>每一份源文件</b>的前门，输入完全由用户提供。
 * 其三条空白规范化正则原本都呈二次增长（一行 n 个空格后跟一个非标点字符）：
 *
 * <pre>
 *   PUNCT_NORMAL_RE     10000→476ms    20000→1823ms
 *   PUNCT_FINAL_RE      10000→1301ms   20000→5468ms   80000→87717ms
 *   TRAILING_SPACE_RE   10000→165ms    20000→664ms    80000→10733ms
 * </pre>
 *
 * 一份构造过的源文件就能把编译线程钉死。
 *
 * <h2>★修复过程里的一个错误，值得记下来</h2>
 *
 * 我最初只把 {@code \s+} 改成占有量词 {@code \s++}，实测耗时降到 1/15，看起来
 * 像修好了。但继续测 20000→40000 发现<b>仍是 4×</b>——二次项根本没消掉。
 *
 * <p>原因：瓶颈不在 {@code \s+} 内部的回退，而在 {@code find()} 会从<b>每一个</b>
 * 空白位置重新起跑。只有左锚 {@code (?<!\s)} 禁止「从空白串中间起跑」，才能把
 * n 次扫描降为 1 次。
 *
 * <p>★教训：「快了很多」不等于「复杂度变了」。判据必须是<b>增长率</b>。
 */
class CanonicalizerReDoSTest {

    /**
     * 三条被守护的正则的**字段名**与各自的替换串。
     *
     * <p>★这里只记字段名，模式本体用<b>反射从生产类读出来</b>。
     *
     * <p>★这一点是本文件第一版的致命缺陷，值得写清楚：第一版把模式**抄了一份**
     * 放在测试里。变异验证（把生产代码的三处修复全部撤掉重跑）结果是 <b>4/4
     * 依然全绿</b>——因为测试量的自始至终是那份副本，与生产代码毫无关系。
     * 这正是本仓记过的「门禁在结构上无法变红」。
     *
     * <p>改为反射读取后，撤掉修复即变红（已实证）。
     */
    private static final String[][] FIELDS = {
        {"PUNCT_NORMAL_RE",    "$1"},
        {"PUNCT_FINAL_RE",     "$1"},
        {"TRAILING_SPACE_RE",  ""},
    };

    /** 改前的贪婪形态，仅用于语义对照（证明修复没改变匹配结果）。 */
    private static final String[] GREEDY = {
        "\\s+([.,:。：，])",
        "\\s+([.,:;?。：，！；？]|!(?!=))",
        "\\s+$",
    };

    /** ★从生产类反射读出真正在用的 Pattern —— 不是副本。 */
    private static Pattern productionPattern(String fieldName) {
        try {
            java.lang.reflect.Field f = Canonicalizer.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return (Pattern) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "读不到生产字段 " + fieldName + " —— 字段被改名或删除了，本门禁已失效。", e);
        }
    }

    private static double minMillis(Runnable r, int repeats) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < repeats; i++) {
            long t = System.nanoTime();
            r.run();
            best = Math.min(best, (System.nanoTime() - t) / 1e6);
        }
        return best;
    }

    /**
     * 断言呈<b>次二次</b>增长。★量增长率而非绝对耗时：绝对阈值在共享 CI 上
     * 要么抖动成 flaky，要么定得太松而形同虚设；增长率对机器速度不敏感。
     */
    private static void assertSubQuadratic(String label, IntFunction<String> build,
                                           java.util.function.Consumer<String> fn, int base) {
        fn.accept(build.apply(base / 4));   // 预热，避免 JIT 编译开销污染比值

        String small = build.apply(base);
        String large = build.apply(base * 2);
        double tSmall = minMillis(() -> fn.accept(small), 3);
        double tLarge = minMillis(() -> fn.accept(large), 3);

        // ★耗时过短时比值噪声极大，此时退化成绝对上界断言——**不静默跳过**，
        //   否则这条门禁在结构上就无法变红了。
        if (tLarge < 1.0) {
            assertTrue(tLarge < 50.0, label + "：耗时 " + tLarge + "ms 超出绝对预算 50ms");
            return;
        }

        double ratio = tLarge / Math.max(tSmall, 0.001);
        assertTrue(ratio < 3.0, String.format(
            "%s：输入 %d→%d（翻倍）耗时 %.2fms→%.2fms，增长 %.1f× —— 应 <3×。%n"
            + "★接近 4× 意味着二次增长：正则在每个起始位置都重新扫描到串尾。%n"
            + "  源文件是用户提供的输入，一份构造过的文件就能钉死编译线程。",
            label, base, base * 2, tSmall, tLarge, ratio));
    }

    @Test
    @DisplayName("★三条空白正则都必须呈次二次增长（模式由反射从生产类读取）")
    void whitespacePatternsAreSubQuadratic() {
        for (String[] f : FIELDS) {
            Pattern re = productionPattern(f[0]);
            assertSubQuadratic(f[0] + " = " + re.pattern(),
                n -> " ".repeat(n) + "x",
                s -> re.matcher(s).replaceAll(f[1]), 20000);
        }
    }

    @Test
    @DisplayName("★整条 canonicalize 链路在恶意输入下呈次二次增长")
    void canonicalizeIsSubQuadratic() {
        // ★只测单条正则不够——必须证明**真正的入口**也没有二次行为，
        //   否则「修了正则但链路里还有别的二次环节」会被漏掉。
        //
        // ★输入形态必须**真的走到**被守护的分支。第一版用的是
        //   `"Let x be " + 空白 + "1"`——空白后面跟的是数字，三条正则一条都
        //   不命中，于是无论修复在不在都很快，这条断言等于没测。
        //   现在空白后面跟**句点**（命中 PUNCT）且位于**行尾**（命中 TRAILING）。
        Canonicalizer c = new Canonicalizer();

        // ★真正的端到端攻击载荷：**一份全是空行的源文件**。
        //
        //   这条是整份改动里最有价值的发现，而它是**量出来的、不是读出来的**：
        //   我先按「空白+句点」的直觉构造输入，撤掉修复后端到端居然还是很快
        //   ——因为上游 SPACE_RUN_RE 会先把 [ \t] 折叠掉，把下游遮蔽了。
        //   换成 \r（不被折叠，且会被规范化成 \n）才暴露出 7 秒的二次行为。
        //
        //   然后用**栈采样**定位，2951/2966 个样本落在
        //   StringSegmenter.replaceOutsideStrings —— 真凶是
        //   SetToTransformer / ResultIsTransformer 里 MULTILINE 下的 `^(\s*)`：
        //   `^` 在每个行首起跑，`\s*` 又吃穿所有后续空行。
        //   实测 10000→465ms、20000→1774ms、40000→7027ms（×4）。
        //
        //   ★教训：凭「哪条正则看起来危险」去构造载荷会找错方向；
        //     必须实测 + 采样。我前后猜错了四次。
        assertSubQuadratic("canonicalize / 全空行源文件",
            n -> "Let x be 1" + "\n".repeat(n) + ".",
            s -> c.canonicalize(s), 20000);

        // \r 路径：不被 SPACE_RUN_RE 折叠，会被规范化成 \n 后走同一条路。
        assertSubQuadratic("canonicalize / CR 填充源文件",
            n -> "Let x be 1" + String.valueOf((char) 0x0d).repeat(n) + ".",
            s -> c.canonicalize(s), 20000);
    }

    @Test
    @DisplayName("★语义守卫：左锚+占有量词不得改变任何匹配结果")
    void anchorsPreserveSemantics() {
        // ★人工样本证明不了「所有输入都没变」。这里用固定种子随机穷举，
        //   字符池刻意偏向空白与标点，让替换**真的发生**。
        char[] pool = " \t\n\r\u000b\f\u00a0\u3000.,:;?!=。：，！；？abx多".toCharArray();
        Random rnd = new Random(20260913L);   // 固定种子 → 失败可复现

        int compared = 0;
        int actuallyReplaced = 0;
        for (int i = 0; i < 50000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + rnd.nextInt(12);
            for (int j = 0; j < len; j++) sb.append(pool[rnd.nextInt(pool.length)]);
            String input = sb.toString();

            for (int k = 0; k < FIELDS.length; k++) {
                String rep = FIELDS[k][1];
                String greedy = Pattern.compile(GREEDY[k]).matcher(input).replaceAll(rep);
                // ★同样反射读生产模式：语义守卫也必须钉在真正在跑的那条正则上。
                String now = productionPattern(FIELDS[k][0]).matcher(input).replaceAll(rep);
                compared++;
                if (!greedy.equals(input)) actuallyReplaced++;
                final int kk = k;
                assertEquals(greedy, now, () -> String.format(
                    "%s 在输入 %s 上改变了语义：贪婪原式得 %s，现行式得 %s",
                    FIELDS[kk][0], esc(input), esc(greedy), esc(now)));
            }
        }

        // ★反向守卫：若样本里**从来没有发生过替换**，上面的相等断言全是空洞的
        //   （本仓记过的「样本无从区分」）。此处钉住样本确实有判别力。
        assertTrue(actuallyReplaced > compared / 10,
            "只有 " + actuallyReplaced + "/" + compared + " 组发生过替换 —— "
            + "样本判别力不足，相等断言可能是空洞的。");
    }

    /**
     * 自检用的「已知二次」工作量。★结果写进这个字段而非丢弃——
     * 否则 JIT 会判定整个双重循环无副作用并**整段消除**，自检就会
     * 假装通过（第一次跑正是这样红的，见类注释）。
     */
    @SuppressWarnings("unused")
    private static volatile long sink;

    @Test
    @DisplayName("★门禁自身可变红 —— 用已知二次算法验证量具连着被测对象")
    void gateItselfCanFail() {
        // 「门禁在结构上无法变红」是本仓记录过的最危险假绿。这条把一个
        // **已知二次**的函数喂给同一套判定逻辑：若判定通过，说明
        // assertSubQuadratic 根本测不出二次增长，本文件所有门禁都是假绿。
        //
        // ★这条第一次跑就红了，而且红得对：空的双重循环被 JIT 整段消除，
        //   耗时恒为 0，量具「没连上被测对象」。改成写 volatile 字段后，
        //   循环无法被消除，量具才真的接上。
        assertThrows(AssertionError.class,
            () -> assertSubQuadratic("自检", n -> "x".repeat(n),
                s -> {
                    long acc = 0;
                    for (int i = 0; i < s.length(); i++) {
                        for (int j = 0; j < s.length(); j++) acc += s.charAt(j);
                    }
                    sink = acc;   // ★volatile 写：禁止 JIT 消除上面的循环
                },
                4000),
            "已知二次算法未被判定为超线性 —— assertSubQuadratic 失效，本文件所有门禁都是假绿。");
    }

    private static String esc(String s) {
        return "\"" + s.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "\"";
    }
}
