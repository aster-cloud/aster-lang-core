package aster.core.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * 从改过的显示文本还原规范源码（ADR 0059 修订 4 D7）。
 *
 * <p>一个解 = 给每个内容 span 一个非空新文本，使按 span 顺序拼接（结构 span 取原 display）恰为编辑后的文本 E，
 * 且每段两侧都是内容 span 的「隐藏串」（连续的、display 全为空串的结构 span）两侧至少一个内容与原文相同。
 * 解集合由定义唯一确定，与算法无关；这里按 aster-lang-test 参照实现的同一 DP 计数（截到 2 即停）：
 * 把合并形态看成 {@code D_0 c_1 D_1 … c_n D_n}，状态为 (内容下标, 起点, 是否必须原样)，锚点出现位置用 KMP 求，
 * 「任意终点」的累加用前缀和——每项先截到 2、和是精确整数，所以差也精确。总计 O(span 数 × |E|)。
 */
public final class DisplayReconstructor {

    private DisplayReconstructor() {
    }

    /** 还原结果。offset 只用于诊断（写 stderr），不跨实现比较。 */
    public sealed interface Reconstruction permits Unique, Unmappable, Ambiguous {
    }

    /** 恰好一个解：派生排版 L′（结构 span 原样，内容换成新文本）。 */
    public record Unique(PolicyLayout derived) implements Reconstruction {
    }

    /** 无解；crlfHint 表示编辑后的文本用了 CRLF 而排版没有。 */
    public record Unmappable(int offset, boolean crlfHint) implements Reconstruction {
    }

    /** 两个或更多解。 */
    public record Ambiguous(int offset) implements Reconstruction {
    }

    /**
     * @param layout 包的排版（已过 L1–L7，合并形态）
     * @param edited 编辑后的显示文本：已严格 UTF-8 解码、去掉至多一个开头 BOM；文末换行规则在这里处理
     */
    public static Reconstruction reconstruct(PolicyLayout layout, String edited) {
        String display = layout.toDisplay();
        boolean crlf = edited.contains("\r\n") && !display.contains("\r\n");
        if (LayoutLimits.utf8Length(edited) > LayoutLimits.DISPLAY_BYTES) {
            return new Unmappable(0, crlf);
        }
        String e = edited;
        // 编辑器保存时补的文末换行：显示不以 \n 结尾而 E 以 \n 结尾时去掉一个（及其前一个 \r）
        if (!display.endsWith("\n") && e.endsWith("\n")) {
            e = e.substring(0, e.length() - (e.endsWith("\r\n") ? 2 : 1));
        }
        List<String> contents = new ArrayList<>();
        List<StringBuilder> anchors = new ArrayList<>();
        anchors.add(new StringBuilder());
        for (LayoutSpan s : layout.spans()) {
            if (s instanceof LayoutSpan.Content c) {
                contents.add(c.text());
                anchors.add(new StringBuilder());
            } else {
                anchors.get(anchors.size() - 1).append(s.displayText());
            }
        }
        int n = contents.size();
        int len = e.length();
        Unmappable unmappable = new Unmappable(firstDivergence(e, display), crlf);
        if (n == 0) {
            return e.equals(display) ? new Unique(layout) : unmappable;
        }
        String head = anchors.get(0).toString();
        String tail = anchors.get(n).toString();
        if (head.length() + tail.length() > len || !e.startsWith(head) || !e.endsWith(tail)) {
            return unmappable;
        }
        int lastEnd = len - tail.length();

        // f[k][2p + flag]：c_k 从 p 开始、flag = 1 表示必须原样的部分解个数（截到 2）
        byte[][] f = new byte[n][2 * (len + 1)];
        boolean[][] orig = new boolean[n][];
        for (int k = 0; k < n; k++) {
            orig[k] = occurrences(e, contents.get(k));
        }
        f[0][2 * head.length()] = 1;
        long[] prefix = new long[len + 2];
        int total = 0;
        for (int k = 0; k < n; k++) {
            byte[] fk = f[k];
            int olen = contents.get(k).length();
            boolean[] om = orig[k];
            prefix[0] = 0;
            for (int p = 0; p <= len; p++) {
                prefix[p + 1] = prefix[p] + fk[2 * p];
            }
            if (k == n - 1) {
                total = cap(origEnd(fk, om, olen, lastEnd) + modifiedEnd(fk, om, olen, prefix, lastEnd));
                break;
            }
            byte[] next = f[k + 1];
            String dk = anchors.get(k + 1).toString();
            if (!dk.isEmpty()) {
                boolean[] dm = occurrences(e, dk);
                for (int end = 1; end + dk.length() <= len; end++) {
                    if (!dm[end]) {
                        continue;
                    }
                    int w = cap(origEnd(fk, om, olen, end) + modifiedEnd(fk, om, olen, prefix, end));
                    if (w > 0) {
                        int at = 2 * (end + dk.length());
                        next[at] = (byte) cap(next[at] + w);
                    }
                }
            } else {
                for (int end = 1; end < len; end++) {
                    int wo = origEnd(fk, om, olen, end);
                    int wm = modifiedEnd(fk, om, olen, prefix, end);
                    if (wo > 0) {
                        next[2 * end] = (byte) cap(next[2 * end] + wo);
                    }
                    if (wm > 0) {
                        next[2 * end + 1] = (byte) cap(next[2 * end + 1] + wm);
                    }
                }
            }
        }
        if (total == 0) {
            return unmappable;
        }
        if (total >= 2) {
            return new Ambiguous(firstDivergence(e, display));
        }

        // 唯一解：从文末沿前驱回溯各 c_k 的 [start, end)
        int[] starts = new int[n];
        int[] ends = new int[n];
        int end = lastEnd;
        Boolean mustBeOriginalEnd = null;
        for (int k = n - 1; k >= 0; k--) {
            byte[] fk = f[k];
            int olen = contents.get(k).length();
            int foundP = -1;
            int foundFlag = -1;
            for (int p = 0; p < end && foundP < 0; p++) {
                boolean isOrig = end - p == olen && orig[k][p];
                if (Boolean.TRUE.equals(mustBeOriginalEnd) && !isOrig) {
                    continue;
                }
                if (Boolean.FALSE.equals(mustBeOriginalEnd) && isOrig) {
                    continue;
                }
                if (fk[2 * p] != 0) {
                    foundP = p;
                    foundFlag = 0;
                } else if (isOrig && fk[2 * p + 1] != 0) {
                    foundP = p;
                    foundFlag = 1;
                }
            }
            starts[k] = foundP;
            ends[k] = end;
            if (k == 0) {
                break;
            }
            String dprev = anchors.get(k).toString();
            if (!dprev.isEmpty()) {
                end = foundP - dprev.length();
                mustBeOriginalEnd = null;
            } else {
                end = foundP;
                // c_k 必须原样（flag = 1）⇔ c_{k-1} 改过；c_k 自由 ⇔ c_{k-1} 原样
                mustBeOriginalEnd = foundFlag == 0;
            }
        }
        List<LayoutSpan> spans = new ArrayList<>(layout.spans().size());
        int ci = 0;
        for (LayoutSpan s : layout.spans()) {
            if (s instanceof LayoutSpan.Content) {
                spans.add(new LayoutSpan.Content(e.substring(starts[ci], ends[ci])));
                ci++;
            } else {
                spans.add(s);
            }
        }
        return new Unique(new PolicyLayout(spans));
    }

    /** 以 end 为终点、且恰为原文的部分解个数（自由与必须原样两类起点之和，截到 2）。 */
    private static int origEnd(byte[] fk, boolean[] om, int olen, int end) {
        int p = end - olen;
        return p >= 0 && om[p] ? cap(fk[2 * p] + fk[2 * p + 1]) : 0;
    }

    /** 以 end 为终点、且不是原文的部分解个数：全部自由起点 p < end 之和减去原样终点那一项。 */
    private static int modifiedEnd(byte[] fk, boolean[] om, int olen, long[] prefix, int end) {
        int p = end - olen;
        long own = p >= 0 && om[p] ? fk[2 * p] : 0;
        long v = prefix[end] - own;
        return v > 2 ? 2 : (int) v;
    }

    private static int cap(int v) {
        return v > 2 ? 2 : v;
    }

    private static int firstDivergence(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** KMP：pattern（非空）在 text 中全部出现的起点标记，长度 |text| + 1。 */
    static boolean[] occurrences(String text, String pattern) {
        boolean[] marks = new boolean[text.length() + 1];
        int m = pattern.length();
        int[] fail = new int[m];
        for (int i = 1, k = 0; i < m; i++) {
            while (k > 0 && pattern.charAt(i) != pattern.charAt(k)) {
                k = fail[k - 1];
            }
            if (pattern.charAt(i) == pattern.charAt(k)) {
                k++;
            }
            fail[i] = k;
        }
        for (int i = 0, k = 0; i < text.length(); i++) {
            while (k > 0 && text.charAt(i) != pattern.charAt(k)) {
                k = fail[k - 1];
            }
            if (text.charAt(i) == pattern.charAt(k)) {
                k++;
            }
            if (k == m) {
                marks[i - m + 1] = true;
                k = fail[k - 1];
            }
        }
        return marks;
    }
}
