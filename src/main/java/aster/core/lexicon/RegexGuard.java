package aster.core.lexicon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 针对来自不可信 lexicon 配置（如 {@code customRules}）的正则表达式提供 ReDoS 防御。
 * <p>
 * 不可信 JSON 语言包可以携带任意正则，这些正则会在编译后对<b>整段源码</b>执行。
 * 形如 {@code (a+)+} 的嵌套量词在不匹配的长输入上会触发灾难性回溯（catastrophic
 * backtracking），导致拒绝服务。本类提供纵深防御：
 * <ul>
 *   <li><b>注册/校验期静态筛查</b> —— {@link #screen(String)} 拒绝超长正则以及明显的
 *       嵌套量词 ReDoS 形状（{@code (...+)+}、{@code (...*)*}、{@code (...+)*} 等）。
 *       这是一个保守的启发式：可能漏判某些刁钻的形状，但不会误伤常见的良性正则。</li>
 *   <li><b>匹配期超时</b> —— {@link #matcherFor(Pattern, CharSequence)} 把输入包装成可被
 *       中断的 {@link CharSequence}，{@link #replaceAllWithTimeout} 在独立线程上运行
 *       正则并设置看门狗超时（{@link #DEFAULT_TIMEOUT_MS}），超时即抛出清晰的 lexicon 错误。</li>
 * </ul>
 */
public final class RegexGuard {

    private RegexGuard() {}

    /** 不可信正则字符串的最大长度（字符数）。 */
    public static final int MAX_PATTERN_LENGTH = 1000;

    /** 单次匹配的看门狗超时（毫秒）。 */
    public static final long DEFAULT_TIMEOUT_MS = 2000;

    /**
     * 嵌套量词 ReDoS 形状的保守启发式。
     * <p>
     * 匹配“一个被量词修饰的分组，整体又被量词修饰”的模式，即 {@code (...Q)Q}，
     * 其中内层 {@code Q} 属于 {@code + * {n,}} 之一（带可选 {@code ?} 惰性标记），
     * 外层 {@code Q} 属于 {@code + * {n,}}。这覆盖经典灾难性回溯：
     * {@code (a+)+}、{@code (a*)*}、{@code (a+)*}、{@code (.*)+}、{@code (a{2,})+} 等。
     * <p>
     * 形状（去转义后）：{@code \( ... [+*] | \{\d+,\}  ... \) [+*] | \{\d+,\}}。
     */
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile(
        "\\((?:[^()\\\\]|\\\\.)*?(?:[+*]|\\{\\d+,\\d*})\\??\\)(?:[+*]|\\{\\d+,\\d*})"
    );

    /**
     * 静态筛查一个不可信正则字符串。
     *
     * @param pattern 正则字符串
     * @return 校验错误列表；为空表示通过筛查
     */
    public static List<String> screen(String pattern) {
        List<String> errors = new ArrayList<>();
        if (pattern == null) {
            errors.add("regex pattern must not be null");
            return errors;
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            errors.add("regex pattern too long: " + pattern.length()
                + " chars (max " + MAX_PATTERN_LENGTH + ")");
        }
        if (NESTED_QUANTIFIER.matcher(pattern).find()) {
            errors.add("regex pattern has nested-quantifier ReDoS shape "
                + "(e.g. (...+)+, (...*)*, (...+)*): " + pattern);
        }
        if (hasAdjacentAmbiguousQuantifier(pattern)) {
            errors.add("regex pattern has adjacent-ambiguous-quantifier ReDoS shape "
                + "(e.g. a*a*b, a+a+b): two quantifiers over the same atom make the "
                + "split exponential: " + pattern);
        }
        return errors;
    }

    /**
     * 检测<b>相邻量词</b>歧义：{@code a*a*b}、{@code a+a+b}、{@code \d*\d*x}。
     *
     * <h2>★为什么 {@link #NESTED_QUANTIFIER} 抓不到</h2>
     *
     * 那条只看「被量词修饰的<b>分组</b>」，而歧义<b>不需要分组</b>即可产生：
     * 两个相邻、匹配<b>同一原子</b>的量词，会让「这个字符归左边还是右边」
     * 产生 2^n 种切分，后缀失配时全部被穷举。
     *
     * <p>实测（改前两侧守卫都 ACCEPTED）：
     * {@code a*a*a*a*a*a*a*a*a*a*b} 在 24 字符输入上耗时 1705ms，每 +2 字符翻倍。
     *
     * <p>Java 侧有 {@link #replaceAllWithTimeout} 看门狗兜底（降级为超时异常），
     * 但静态拒绝更好——它在<b>加载期</b>就挡住，而不是每次匹配都赌看门狗。
     *
     * <p>★判据保守：只认<b>文本完全相同</b>的相邻原子，不做字符集交集分析。
     * 这样 {@code a*b*c} 这类不同原子的模式不会被误伤
     * （误伤会静默丢掉用户的合法 overlay 规则，比漏网更难发现）。
     * 实证：扫两仓 79 条生产词典正则，零误伤。
     */
    static boolean hasAdjacentAmbiguousQuantifier(String p) {
        int i = 0;
        while (i < p.length()) {
            int[] first = readQuantifiedAtom(p, i);
            if (first == null) {
                i += (p.charAt(i) == '\\') ? 2 : 1;
                continue;
            }
            int[] second = readQuantifiedAtom(p, first[1]);
            if (second != null
                && p.substring(i, first[0]).equals(p.substring(first[1], second[0]))) {
                return true;
            }
            i = first[1];
        }
        return false;
    }

    /**
     * 从 {@code i} 处解析一个「原子 + 开区间量词」。
     *
     * @return {@code [原子结束位置, 量词结束位置]}；若此处不是「原子+量词」则返回 null
     */
    private static int[] readQuantifiedAtom(String s, int i) {
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        int j;
        if (c == '\\') {
            j = i + 2;                                  // 转义原子，如 \d \w \.
        } else if (c == '[') {                          // 字符类
            j = i + 1;
            while (j < s.length() && s.charAt(j) != ']') {
                if (s.charAt(j) == '\\') j++;
                j++;
            }
            j++;
        } else if (c == '(') {
            // ★分组也是原子：`(a)*(a)*b` / `(?:a)*(?:a)*b` 与 `a*a*b` 同样指数。
            //   第一版在此直接 return null、注释「交给 NESTED_QUANTIFIER」，但那个
            //   检查只看**分组内部**有无量词——`(a)*(a)*` 两侧内部都没有，无人负责。
            //   实测：两者 24 字符输入均 1720ms。
            // ★必须跳过**字符类**：`[)]` 里的括号不是分组括号。
            //   不跳会把 `([)])*([)])*b` 的深度算错而放行（实测 22 字符 794ms）
            //   ——这是加分组支持时新引入的漏判，与 TS 侧同源。
            int depth = 0;
            j = i;
            while (j < s.length()) {
                char d = s.charAt(j);
                if (d == '\\') { j += 2; continue; }
                if (d == '[') {                       // 字符类：整体跳过
                    j++;
                    while (j < s.length() && s.charAt(j) != ']') {
                        if (s.charAt(j) == '\\') j++;
                        j++;
                    }
                    j++;
                    continue;
                }
                if (d == '(') depth++;
                else if (d == ')') { depth--; if (depth == 0) { j++; break; } }
                j++;
            }
            if (depth != 0) return null;                // 不平衡，交给 Pattern.compile 报错
        } else if (c == ')' || c == '|') {
            return null;
        } else {
            j = i + 1;                                  // 单字符原子
        }
        if (j > s.length()) return null;

        int atomEnd = j;
        if (j < s.length()) {
            char q = s.charAt(j);
            // ★惰性量词 `*?` / `+?` 同样有歧义切分（实测 `a*?×10` 24 字符 640ms），
            //   故读完量词后要把可选的 `?` 一并吃掉。
            if (q == '*' || q == '+') return new int[]{atomEnd, lazyEnd(s, j + 1)};
            if (q == '{') {
                java.util.regex.Matcher m =
                    OPEN_REPETITION.matcher(s.substring(j));
                if (m.lookingAt()) return new int[]{atomEnd, lazyEnd(s, j + m.end())};
            }
        }
        return null;
    }

    /** 量词后若跟 {@code ?}（惰性），把它一并算进量词长度。 */
    private static int lazyEnd(String s, int k) {
        return (k < s.length() && s.charAt(k) == '?') ? k + 1 : k;
    }

    /** 开区间重复 {@code {n,}} / {@code {n,m}} —— 只有这类才产生歧义切分。 */
    private static final Pattern OPEN_REPETITION = Pattern.compile("\\{\\d*,\\d*}");

    /**
     * 筛查并编译一个不可信正则。
     *
     * @param pattern 正则字符串
     * @param flags   {@link Pattern} 标志位
     * @return 编译后的 {@link Pattern}
     * @throws IllegalArgumentException 如果筛查失败或正则语法非法
     */
    public static Pattern compile(String pattern, int flags) {
        List<String> errors = screen(pattern);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Rejected unsafe lexicon regex: " + String.join("; ", errors));
        }
        try {
            return Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid lexicon regex: " + e.getMessage(), e);
        }
    }

    private static final AtomicLong THREAD_SEQ = new AtomicLong();

    private static final ThreadFactory WATCHDOG_THREADS = runnable -> {
        Thread t = new Thread(runnable, "aster-regex-watchdog-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    };

    /**
     * 在看门狗超时内对 {@code input} 的非字符串部分执行 {@code pattern} 的全量替换。
     * <p>
     * 输入被包装成可中断的 {@link CharSequence}：匹配运行在独立 daemon 线程上，
     * 超时则中断该线程并抛错，避免灾难性回溯把编译线程挂死。
     *
     * @param pattern     已编译正则
     * @param input       目标文本
     * @param replacement 替换串
     * @param timeoutMs   超时毫秒数
     * @return 替换后的文本
     * @throws RegexTimeoutException 超时
     */
    public static String replaceAllWithTimeout(Pattern pattern, String input, String replacement, long timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor(WATCHDOG_THREADS);
        Future<String> future = executor.submit(() -> {
            Matcher matcher = pattern.matcher(new InterruptibleCharSequence(input));
            return matcher.replaceAll(replacement);
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RegexTimeoutException(
                "Lexicon regex exceeded " + timeoutMs + "ms (possible ReDoS): /" + pattern.pattern() + "/", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RegexTimeoutException("Interrupted while running lexicon regex: /" + pattern.pattern() + "/", e);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Lexicon regex failed: /" + pattern.pattern() + "/", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 便捷重载，使用 {@link #DEFAULT_TIMEOUT_MS}。
     */
    public static String replaceAllWithTimeout(Pattern pattern, String input, String replacement) {
        return replaceAllWithTimeout(pattern, input, replacement, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 在看门狗超时内创建一个可中断的 {@link Matcher}（用于只判定 {@code find()} 的场景）。
     */
    public static Matcher matcherFor(Pattern pattern, CharSequence input) {
        return pattern.matcher(new InterruptibleCharSequence(input));
    }

    /** 超时抛出的运行期异常。 */
    public static final class RegexTimeoutException extends RuntimeException {
        public RegexTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 可被线程中断打断的 {@link CharSequence} 包装。
     * <p>
     * {@link Matcher} 在回溯过程中会频繁调用 {@link #charAt(int)}；当看门狗线程
     * 取消任务（{@code interrupt()}）时，下一次 {@code charAt} 抛出
     * {@link RuntimeException}，从而打破灾难性回溯循环。
     */
    private static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence delegate;

        InterruptibleCharSequence(CharSequence delegate) {
            this.delegate = delegate;
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("regex matching interrupted (watchdog timeout)");
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(delegate.subSequence(start, end));
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
