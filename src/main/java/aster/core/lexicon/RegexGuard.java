package aster.core.lexicon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 *   <li><b>匹配期超时</b> —— {@link #matcherFor(Pattern, CharSequence)} 与
 *       {@link #replaceAllWithTimeout} 把输入包装成带截止时间的 {@link CharSequence}，
 *       正则在调用线程上运行，超过看门狗超时（{@link #DEFAULT_TIMEOUT_MS}）即抛出清晰的 lexicon 错误。</li>
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

    /** 待替换的半开区间 {@code [start, end)}，用于跳过字符串字面量等不可改写区域。 */
    public record Region(int start, int end) {}

    /**
     * 只在 {@code regions} 内查找并替换，区间之外的文本原样保留。
     * <p>
     * ★整个输入只建<b>一个</b> {@link Matcher}，逐区间 {@link Matcher#region}：
     * 配合 {@code useAnchoringBounds(false)} 与 {@code useTransparentBounds(true)}，
     * {@code ^}/{@code $}/{@code \b} 与 lookbehind 看到的是真实上下文，而不是每段
     * 子串各自的"开头"——否则 {@code MULTILINE ^} 会在字符串字面量之后的行中触发。
     * 区间必须升序且互不重叠。
     *
     * @param pattern     已编译正则
     * @param input       完整输入
     * @param regions     允许改写的区间
     * @param replacement 替换串（{@link Matcher#appendReplacement} 模板语法）
     * @return 替换后的文本
     */
    public static String replaceRegions(Pattern pattern, CharSequence input, List<Region> regions, String replacement) {
        Matcher matcher = pattern.matcher(input)
            .useAnchoringBounds(false)
            .useTransparentBounds(true);
        StringBuilder out = new StringBuilder(input.length());
        int copied = 0;
        for (Region region : regions) {
            matcher.region(region.start(), region.end());
            while (matcher.find()) {
                out.append(input, copied, matcher.start());
                appendExpandedReplacement(matcher, replacement, out);
                copied = matcher.end();
            }
        }
        out.append(input, copied, input.length());
        return out.toString();
    }

    /**
     * 按 {@link Matcher#appendReplacement} 的模板语法展开替换串
     * （{@code $n}、{@code ${name}}、{@code \x} 转义）。
     * <p>
     * 不能直接用 {@code appendReplacement}：{@link Matcher#region} 会把它的追加游标重置为 0，
     * 跨区间复用同一 Matcher 时前一区间的文本会被重复追加。
     */
    private static void appendExpandedReplacement(Matcher matcher, String replacement, StringBuilder out) {
        int i = 0;
        int n = replacement.length();
        while (i < n) {
            char c = replacement.charAt(i++);
            if (c == '\\') {
                if (i >= n) {
                    throw new IllegalArgumentException("character to be escaped is missing");
                }
                out.append(replacement.charAt(i++));
            } else if (c == '$') {
                i = appendGroupReference(matcher, replacement, i, out);
            } else {
                out.append(c);
            }
        }
    }

    /** 解析 {@code $} 之后的组引用，返回引用结束后的下标。 */
    private static int appendGroupReference(Matcher matcher, String replacement, int i, StringBuilder out) {
        int n = replacement.length();
        if (i >= n) {
            throw new IllegalArgumentException("Illegal group reference: group index is missing");
        }
        String group;
        if (replacement.charAt(i) == '{') {
            int close = replacement.indexOf('}', i + 1);
            if (close < 0) {
                throw new IllegalArgumentException("named capturing group is missing trailing '}'");
            }
            group = matcher.group(replacement.substring(i + 1, close));
            i = close + 1;
        } else {
            int refNum = replacement.charAt(i) - '0';
            if (refNum < 0 || refNum > 9) {
                throw new IllegalArgumentException("Illegal group reference");
            }
            i++;
            // 与 JDK 一致：只要更长的数字仍是合法组号就继续吃，否则余下数字是字面量。
            while (i < n) {
                int digit = replacement.charAt(i) - '0';
                if (digit < 0 || digit > 9 || refNum * 10 + digit > matcher.groupCount()) {
                    break;
                }
                refNum = refNum * 10 + digit;
                i++;
            }
            group = matcher.group(refNum);
        }
        if (group != null) {
            out.append(group);
        }
        return i;
    }

    /**
     * 在看门狗超时内对 {@code input} 的 {@code regions} 执行 {@code pattern} 的全量替换。
     * <p>
     * ★超时不靠线程：输入被包装成带截止时间的 {@link CharSequence}，正则引擎的每一步
     * 回溯都要经过 {@code charAt}，超过截止时间即抛错打断回溯。匹配就在调用线程上跑，
     * 没有线程创建、任务提交与结果交接——逐词热路径上每词每条规则各调一次也不再付这笔开销。
     *
     * @param pattern     已编译正则
     * @param input       完整输入
     * @param regions     允许改写的区间
     * @param replacement 替换串
     * @param timeoutMs   超时毫秒数
     * @return 替换后的文本
     * @throws RegexTimeoutException 超时
     */
    public static String replaceRegionsWithTimeout(
        Pattern pattern, String input, List<Region> regions, String replacement, long timeoutMs
    ) {
        return replaceRegions(pattern, new DeadlineCharSequence(input, pattern, timeoutMs), regions, replacement);
    }

    /**
     * 在看门狗超时内对整个 {@code input} 执行 {@code pattern} 的全量替换。
     *
     * @throws RegexTimeoutException 超时
     */
    public static String replaceAllWithTimeout(Pattern pattern, String input, String replacement, long timeoutMs) {
        return replaceRegionsWithTimeout(pattern, input, List.of(new Region(0, input.length())), replacement, timeoutMs);
    }

    /**
     * 便捷重载，使用 {@link #DEFAULT_TIMEOUT_MS}。
     */
    public static String replaceAllWithTimeout(Pattern pattern, String input, String replacement) {
        return replaceAllWithTimeout(pattern, input, replacement, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 创建一个带 {@link #DEFAULT_TIMEOUT_MS} 截止时间的 {@link Matcher}（用于只判定 {@code find()} 的场景）。
     * 超时后任一匹配操作抛出 {@link RegexTimeoutException}。
     */
    public static Matcher matcherFor(Pattern pattern, CharSequence input) {
        return pattern.matcher(new DeadlineCharSequence(input, pattern, DEFAULT_TIMEOUT_MS));
    }

    /** 超时抛出的运行期异常。 */
    public static final class RegexTimeoutException extends RuntimeException {
        public RegexTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 带截止时间的 {@link CharSequence} 包装。
     * <p>
     * {@link Matcher} 在回溯过程中会频繁调用 {@link #charAt(int)}；每 {@link #CHECK_INTERVAL}
     * 次访问核对一次时钟，超过截止时间即抛出 {@link RegexTimeoutException}，
     * 从而在调用线程上直接打破灾难性回溯循环。
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private static final int CHECK_INTERVAL = 4096;

        private final CharSequence delegate;
        private final Pattern pattern;
        private final long timeoutMs;
        private final long deadlineNanos;
        private int accessesSinceCheck;

        DeadlineCharSequence(CharSequence delegate, Pattern pattern, long timeoutMs) {
            this(delegate, pattern, timeoutMs, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        }

        private DeadlineCharSequence(CharSequence delegate, Pattern pattern, long timeoutMs, long deadlineNanos) {
            this.delegate = delegate;
            this.pattern = pattern;
            this.timeoutMs = timeoutMs;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if (++accessesSinceCheck >= CHECK_INTERVAL) {
                accessesSinceCheck = 0;
                if (System.nanoTime() - deadlineNanos > 0) {
                    throw new RegexTimeoutException(
                        "Lexicon regex exceeded " + timeoutMs + "ms (possible ReDoS): /" + pattern.pattern() + "/", null);
                }
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), pattern, timeoutMs, deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
