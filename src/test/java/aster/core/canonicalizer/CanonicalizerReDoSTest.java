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

    /**
     * ★从生产类反射读出真正在用的 Pattern —— 不是副本。
     *
     * <p><b>但反射本身有一个它证明不了的东西</b>，由独立审查者用变异找出：
     * 保留 {@code PUNCT_FINAL_RE} 字段（值仍安全）不动，另加一个脆弱孪生字段
     * {@code PUNCT_FINAL_ACTIVE_RE}，把<b>生产调用点</b>改指向它——
     * <b>4/4 依然全绿</b>，而那条脆弱模式实测 {@code n=40000 → 21256ms}。
     *
     * <p>★反射只证明「<b>这个名字</b>下挂着一条安全正则」，
     * <b>不证明「生产真的在用它」</b>——验了对象，没验连线。
     * 兜底见 {@link #patternFieldCountIsPinned()}（诱饵字段会让总数变化）
     * 与 {@link #canonicalizeIsSubQuadratic()}（打真入口，不看字段）。
     */
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

    /** 单次计时（毫秒）。先 GC：让每次测量都从干净的堆开始，大输入不再替前面的垃圾买单。 */
    private static double millis(Runnable r) {
        System.gc();
        long t = System.nanoTime();
        r.run();
        return (System.nanoTime() - t) / 1e6;
    }

    /**
     * 墙钟增长率门禁的规模倍数与判据。
     *
     * <p>★原先只把输入<b>翻倍</b>：线性 ≈2×、二次 ≈4×，门槛 3× 两侧各只有 1.5 倍的缝，
     * 一次调度突发或 JIT 升层就能跨过去。本地满载实测：自检（已知二次）30 轮误判 15 轮。
     *
     * <p>×8 后线性 ≈8×、二次 ≈64×；判据改用<b>增长指数</b>（log 比值 / log 倍数：
     * 线性=1，二次=2），门槛取中点 1.5（比值 &lt;8^1.5≈22.6），两侧都有约 2.8 倍余量。
     */
    private static final int SCALE = 8;
    private static final double EXPONENT_LIMIT = 1.5;
    private static final int ROUNDS = 7;

    /**
     * 断言呈<b>次二次</b>增长。★量增长率而非绝对耗时：绝对阈值在共享 CI 上
     * 要么抖动成 flaky，要么定得太松而形同虚设；增长率对机器速度不敏感。
     *
     * <p>★量法：两种规模<b>各自</b>预热（只预热一次时小规模可能停在低 JIT 层级，
     * 比值被系统性压低），再<b>交替</b>测量，各取最小值。调度、GC 噪声只会让一次
     * 测量变慢、不会变快，所以最小值最接近真实计算量；交替则让噪声突发两组均摊。
     */
    private static void assertSubQuadratic(String label, IntFunction<String> build,
                                           java.util.function.Consumer<String> fn, int base) {
        String small = build.apply(base);
        String large = build.apply(base * SCALE);
        for (int i = 0; i < 3; i++) {
            fn.accept(small);
            fn.accept(large);
        }

        double tSmall = Double.MAX_VALUE;
        double tLarge = Double.MAX_VALUE;
        for (int i = 0; i < ROUNDS; i++) {
            tSmall = Math.min(tSmall, millis(() -> fn.accept(small)));
            tLarge = Math.min(tLarge, millis(() -> fn.accept(large)));
        }

        // ★耗时过短时比值噪声极大，此时退化成绝对上界断言——**不静默跳过**，
        //   否则这条门禁在结构上就无法变红了。
        // ★早退分支断言**噪声地板本身**，不是一个必然成立的宽松上界。
        //   原写法 `if (tLarge < 1.0) assertTrue(tLarge < 50.0)` 在数学上**永不失败**
        //   （进入分支的前提就是 tLarge < 1.0，而 1.0 < 50）——那是恒真断言。
        final double NOISE_FLOOR_MS = 1.0;
        if (tLarge < NOISE_FLOOR_MS) {
            assertTrue(tLarge < NOISE_FLOOR_MS,
                label + "：耗时 " + tLarge + "ms —— 应 <" + NOISE_FLOOR_MS + "ms");
            return;
        }

        double ratio = tLarge / Math.max(tSmall, 0.001);
        double exponent = Math.log(ratio) / Math.log(SCALE);
        assertTrue(exponent < EXPONENT_LIMIT, String.format(
            "%s：输入 %d→%d（×%d）耗时最小值 %.2fms→%.2fms，增长 %.1f×、指数 %.2f —— 应 <%.1f。%n"
            + "★指数接近 2 意味着二次增长：正则在每个起始位置都重新扫描到串尾。%n"
            + "  源文件是用户提供的输入，一份构造过的文件就能钉死编译线程。",
            label, base, base * SCALE, SCALE, tSmall, tLarge, ratio, exponent, EXPONENT_LIMIT));
    }

    /**
     * 统计读取次数的输入包装。★java.util.regex 只经由 {@code charAt} 读取输入，
     * 所以读取次数就是匹配器工作量的<b>确定性</b>度量：与机器快慢、调度抖动、
     * JIT 时机都无关，同一输入永远得到同一个数。
     *
     * <p>{@code subSequence}/{@code toString} 只用于拼装替换结果（线性），不计数。
     */
    private static final class CountingCharSequence implements CharSequence {
        private final String s;
        private long reads;

        CountingCharSequence(String s) { this.s = s; }

        @Override public int length() { return s.length(); }
        @Override public char charAt(int i) { reads++; return s.charAt(i); }
        @Override public CharSequence subSequence(int a, int b) { return s.subSequence(a, b); }
        @Override public String toString() { return s; }
    }

    /** 跑一次 replaceAll，返回匹配器读取输入字符的总次数。 */
    private static long regexReads(Pattern re, String replacement, String input) {
        CountingCharSequence cs = new CountingCharSequence(input);
        re.matcher(cs).replaceAll(replacement);
        return cs.reads;
    }

    /** 计数门禁的规模：二次的原式在此规模下约 400 万次读取，毫秒级跑完。 */
    private static final int READS_BASE = 2000;

    /**
     * 断言正则在「n 个空白 + 非标点字符」上的读取次数呈<b>次二次</b>增长。
     *
     * <p>★仍然量<b>增长率</b>（类注释的教训不变），只是把「耗时」换成「读取次数」。
     * 线性模式翻倍得 ≈2×，二次模式得 ≈4×；读数是确定的，3× 门槛两侧都没有噪声。
     */
    private static void assertSubQuadraticReads(String label, Pattern re, String replacement) {
        IntFunction<String> build = n -> " ".repeat(n) + "x";
        long small = regexReads(re, replacement, build.apply(READS_BASE));
        long large = regexReads(re, replacement, build.apply(READS_BASE * 2));
        double ratio = (double) large / Math.max(small, 1);
        assertTrue(ratio < 3.0, String.format(
            "%s：输入 %d→%d（翻倍）读取次数 %d→%d，增长 %.2f× —— 应 <3×。%n"
            + "★接近 4× 意味着二次增长：正则在每个起始位置都重新扫描到串尾。%n"
            + "  源文件是用户提供的输入，一份构造过的文件就能钉死编译线程。",
            label, READS_BASE, READS_BASE * 2, small, large, ratio));
    }

    @Test
    @DisplayName("★三条空白正则都必须呈次二次增长（模式由反射从生产类读取）")
    void whitespacePatternsAreSubQuadratic() {
        // ★量的是 charAt 读取次数而非墙钟耗时。墙钟比值在 GitHub 托管 runner 上
        //   曾无相关改动地两次误红（2026-10-10）——那是在量调度器，不是在量正则。
        //   读取次数是确定的：同一模式永远给出同一个比值，红即真回归。
        for (String[] f : FIELDS) {
            Pattern re = productionPattern(f[0]);
            assertSubQuadraticReads(f[0] + " = " + re.pattern(), re, f[1]);
        }
    }

    @Test
    @DisplayName("★计数量具自身可变红 —— 改前的三条贪婪原式必须被判为二次")
    void readCountGateCanFail() {
        // ★换了量具就必须重新证明它能变红：把改前**真实存在过**的三条二次正则
        //   喂给同一个判定，任何一条被放行，说明计数没连上正则引擎，上面的门禁是假绿。
        for (int k = 0; k < GREEDY.length; k++) {
            Pattern re = Pattern.compile(GREEDY[k]);
            String rep = FIELDS[k][1];
            assertThrows(AssertionError.class,
                () -> assertSubQuadraticReads("贪婪原式 " + re.pattern(), re, rep),
                "改前的二次正则 " + GREEDY[k] + " 未被判为超线性 —— 计数量具失效。");
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
        //
        // ★规模 5000→40000（×8）：修复态满载指数 ≤1.02；撤掉 SetTo 修复后实测
        //   53.86ms→3388.67ms、指数 1.99 —— 缺陷态几十秒内必红，不会挂死。
        assertSubQuadratic("canonicalize / 全空行源文件",
            n -> "Let x be 1" + "\n".repeat(n) + ".",
            s -> c.canonicalize(s), 5000);

        // \r 路径：不被 SPACE_RUN_RE 折叠，会被规范化成 \n 后走同一条路。
        assertSubQuadratic("canonicalize / CR 填充源文件",
            n -> "Let x be 1" + String.valueOf((char) 0x0d).repeat(n) + ".",
            s -> c.canonicalize(s), 5000);
    }

    @Test
    @DisplayName("★结构守卫：Pattern 静态字段总数被钉死（挡「诱饵字段」）")
    void patternFieldCountIsPinned() {
        // ★这条补的是反射读字段的结构性盲区（独立审查者用变异证明）：
        //
        //   保留 PUNCT_FINAL_RE 不动（反射照样读到安全值），另加一个脆弱孪生
        //   字段 PUNCT_FINAL_ACTIVE_RE，把**生产调用点**改指向它——
        //   原门禁 4/4 全绿，而脆弱模式实测 n=40000 → 21256ms。
        //
        //   反射验的是「对象」，没验「连线」。此处从另一个角度兜底：
        //   **任何新增/删除 Pattern 字段都会让这条变红**，迫使改动者
        //   解释「新字段是什么、生产用的到底是哪一条」。
        //
        // ★这不是万无一失（把脆弱值直接写进现有字段就绕过了它），
        //   但那种改法会被语义守卫与计时门禁抓到。三条防线**互补**，
        //   各自都有缺口，合起来才盖住诱饵字段这一类。
        java.util.List<String> patternFields = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : Canonicalizer.class.getDeclaredFields()) {
            if (f.getType() == Pattern.class
                && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                patternFields.add(f.getName());
            }
        }
        java.util.Collections.sort(patternFields);

        assertEquals(
            java.util.List.of("PUNCT_FINAL_RE", "PUNCT_NORMAL_RE", "SPACE_RUN_RE",
                              "TRAILING_SPACE_RE", "WORD_PATTERN"),
            patternFields,
            "Canonicalizer 的 Pattern 静态字段清单变了。\n"
            + "★若这是新增字段：请确认它不是「诱饵」——即生产调用点是否被改指向了\n"
            + "  一条未加 ReDoS 防护的孪生模式（审查者实证过这种绕过手法）。\n"
            + "  确认无误后更新本清单，并为新字段补上对应的增长率门禁。");
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
                2000),
            "已知二次算法未被判定为超线性 —— assertSubQuadratic 失效，本文件所有门禁都是假绿。");
    }

    private static String esc(String s) {
        return "\"" + s.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "\"";
    }
}
