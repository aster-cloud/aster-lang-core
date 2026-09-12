package aster.core.mapping;

import aster.core.mapping.MappingIr.TextSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SourceIR —— 人类 artifact 的结构化表示（ADR 0037 §2/§10）。
 *
 * <p>与 {@code aster-lang-ts} 的 {@code src/mapping/source-ir.ts} <b>逐条对等</b>：
 * ADR §7 要求两引擎对同一输入给出同一结果。
 *
 * <h2>它与 LayoutMap 不是同一个问题</h2>
 *
 * <pre>
 *   LayoutMap   display ↔ canonical，用于**本来就是 Aster 源码**的文本
 *               手写、平铺、全文档逐字符覆盖 —— 20 字的诗可以，50 页 Policy 不行
 *   SourceIR    **从未是 Aster** 的人类文档（Policy / SOP / 合同）的结构
 *               机械推导、**嵌套**（标题层级）、只标结构不改内容
 * </pre>
 *
 * <h2>★唯一的硬约束：offset 必须可回切原文</h2>
 *
 * {@link MappingIr.TextSpan} 是<b>字符偏移</b>的。SourceIR 的每个节点都必须携带能
 * <b>逐字节切回原文</b>的 span，否则「文本片段 ↔ IR 节点」这条链在第一步就断了。
 *
 * <p>故本类<b>不做任何文本改写</b>——不 trim、不规范化、不转义。
 */
public final class SourceIr {

    /** 结构角色。刻意保持<b>小而封闭</b>——每多一种就多一份解析歧义。 */
    public enum SectionKind {
        DOCUMENT, HEADING, PARAGRAPH, LIST_ITEM, CODE_BLOCK
    }

    /**
     * SourceIR 节点。
     *
     * @param level 标题层级（1..6）；非 HEADING 为 {@code null}
     */
    public record SourceNode(String nodeId, SectionKind kind, TextSpan span, String text,
                             Integer level, List<SourceNode> children) {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+\\S");
    private static final Pattern FENCE = Pattern.compile("^\\s*```");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-*+]\\s|\\d+\\.\\s)");

    private SourceIr() {}

    /**
     * 从 Markdown 文本推导 SourceIR。
     *
     * <p>★<b>只标结构，不改内容</b>。每个节点都能用 {@code document.substring(span)}
     * 逐字节切回原文——由 {@link #verifyCoverage} 机械验证。
     */
    public static SourceNode parse(String document) {
        List<Block> blocks = splitBlocks(document);
        MutableNode root = new MutableNode("doc", SectionKind.DOCUMENT,
            new TextSpan(0, document.length()), null);

        // 标题栈：栈顶是当前生效的最深标题。遇到同级或更浅的标题就出栈。
        List<int[]> levels = new ArrayList<>();      // [level] 与 stack 同步
        List<MutableNode> stack = new ArrayList<>();
        Map<String, Map<String, Integer>> counters = new HashMap<>();

        for (Block block : blocks) {
            Integer level = headingLevel(block.text());
            if (level != null) {
                while (!stack.isEmpty() && levels.get(levels.size() - 1)[0] >= level) {
                    stack.remove(stack.size() - 1);
                    levels.remove(levels.size() - 1);
                }
                MutableNode host = stack.isEmpty() ? root : stack.get(stack.size() - 1);
                MutableNode node = new MutableNode(
                    nextId(counters, host.nodeId, "h"), SectionKind.HEADING, block.span(), level);
                host.children.add(node);
                stack.add(node);
                levels.add(new int[]{level});
                continue;
            }
            MutableNode parent = stack.isEmpty() ? root : stack.get(stack.size() - 1);
            SectionKind kind = classify(block.text());
            parent.children.add(new MutableNode(
                nextId(counters, parent.nodeId, prefixOf(kind)), kind, block.span(), null));
        }

        // ★HEADING 的 span 必须**覆盖其管辖范围**，而不只是标题那一行。
        //   否则父子 span 不满足包含关系，`nodeAtOffset` 无法从父下降到子，
        //   「点击原文 → 定位结构」直接失效。（TS 侧实测踩过。）
        extendHeadingSpans(root);
        return freeze(root, document);
    }

    /**
     * 机械验证覆盖不变式：text 逐字节等于切片、叶子不重叠、<b>父覆盖子</b>。
     *
     * <p>★没有这道检查，一个错位的 span 会静默把映射指向错误的文本——不报错，
     * 只是双向导航跳到别处。
     *
     * @return 违规列表；空列表表示通过
     */
    public static List<String> verifyCoverage(SourceNode root, String document) {
        List<String> problems = new ArrayList<>();
        List<SourceNode> leaves = new ArrayList<>();
        walkVerify(root, document, problems, leaves);

        leaves.sort(Comparator.comparingInt(n -> n.span().start()));
        for (int i = 1; i < leaves.size(); i++) {
            SourceNode prev = leaves.get(i - 1);
            SourceNode cur = leaves.get(i);
            if (cur.span().start() < prev.span().end()) {
                problems.add("叶子 span 重叠：" + prev.nodeId() + " 与 " + cur.nodeId());
            }
        }
        return problems;
    }

    /** 找出包含某个字符偏移的<b>最深</b>节点——「点击原文 → 定位结构」的基础。 */
    public static SourceNode nodeAtOffset(SourceNode root, int offset) {
        if (offset < root.span().start() || offset >= root.span().end()) {
            return null;
        }
        for (SourceNode child : root.children()) {
            SourceNode hit = nodeAtOffset(child, offset);
            if (hit != null) {
                return hit;
            }
        }
        return root;
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private static void walkVerify(SourceNode n, String doc, List<String> problems,
                                   List<SourceNode> leaves) {
        if (n.span().start() < 0 || n.span().end() > doc.length()
            || n.span().end() < n.span().start()) {
            problems.add(n.nodeId() + ": span 越界或倒置 [" + n.span().start() + ","
                + n.span().end() + ")");
            return;
        }
        String slice = doc.substring(n.span().start(), n.span().end());
        if (!slice.equals(n.text())) {
            problems.add(n.nodeId() + ": text 与 span 切片不符");
        }
        if (n.children().isEmpty()) {
            leaves.add(n);
            return;
        }
        // ★包含不变式：父的 span 必须覆盖每个子。
        for (SourceNode c : n.children()) {
            if (c.span().start() < n.span().start() || c.span().end() > n.span().end()) {
                problems.add(c.nodeId() + " 不被父 " + n.nodeId() + " 包含");
            }
        }
        for (SourceNode c : n.children()) {
            walkVerify(c, doc, problems, leaves);
        }
    }

    private static final class MutableNode {
        final String nodeId;
        final SectionKind kind;
        TextSpan span;
        final Integer level;
        final List<MutableNode> children = new ArrayList<>();

        MutableNode(String nodeId, SectionKind kind, TextSpan span, Integer level) {
            this.nodeId = nodeId;
            this.kind = kind;
            this.span = span;
            this.level = level;
        }
    }

    private record Block(TextSpan span, String text) {}

    /**
     * 按空行切块，并<b>保留每块的精确偏移</b>。
     *
     * <p>★不能用 split 再累加长度——分隔符长度不固定（{@code \n\n} vs
     * {@code \n   \n}），累加会漂移。这里逐行扫描，偏移由扫描位置直接给出。
     *
     * <p>围栏代码块内的空行<b>不切</b>——否则一个代码块会被撕成多块。
     */
    private static List<Block> splitBlocks(String doc) {
        List<Block> blocks = new ArrayList<>();
        boolean fenced = false;
        int curStart = -1;
        int curEnd = -1;

        int lineStart = 0;
        for (int i = 0; i <= doc.length(); i++) {
            if (i != doc.length() && doc.charAt(i) != '\n') {
                continue;
            }
            String line = doc.substring(lineStart, i);

            if (FENCE.matcher(line).find()) {
                if (fenced) {
                    curEnd = i;
                    fenced = false;
                    if (curStart >= 0) {
                        blocks.add(new Block(new TextSpan(curStart, curEnd),
                            doc.substring(curStart, curEnd)));
                        curStart = -1;
                    }
                } else {
                    flush(doc, blocks, curStart, curEnd);
                    curStart = lineStart;
                    curEnd = i;
                    fenced = true;
                }
            } else if (!fenced && line.isBlank()) {
                flush(doc, blocks, curStart, curEnd);
                curStart = -1;
            } else {
                if (curStart < 0) {
                    curStart = lineStart;
                }
                curEnd = i;
            }
            lineStart = i + 1;
        }
        flush(doc, blocks, curStart, curEnd);
        return blocks;
    }

    private static void flush(String doc, List<Block> blocks, int start, int end) {
        if (start < 0) {
            return;
        }
        String text = doc.substring(start, end);
        if (!text.isBlank()) {
            blocks.add(new Block(new TextSpan(start, end), text));
        }
    }

    private static Integer headingLevel(String text) {
        var m = HEADING.matcher(text);
        return m.find() ? m.group(1).length() : null;
    }

    private static SectionKind classify(String text) {
        if (FENCE.matcher(text).find()) return SectionKind.CODE_BLOCK;
        if (LIST.matcher(text).find()) return SectionKind.LIST_ITEM;
        return SectionKind.PARAGRAPH;
    }

    private static String prefixOf(SectionKind kind) {
        return switch (kind) {
            case LIST_ITEM -> "li";
            case CODE_BLOCK -> "code";
            default -> "p";
        };
    }

    /** 在<b>同一父节点</b>下按前缀递增编号——保证 nodeId 在文档内唯一且可预测。 */
    private static String nextId(Map<String, Map<String, Integer>> counters,
                                 String parentId, String prefix) {
        Map<String, Integer> byPrefix = counters.computeIfAbsent(parentId, k -> new HashMap<>());
        int n = byPrefix.merge(prefix, 1, Integer::sum) - 1;
        return parentId + "." + prefix + "[" + n + "]";
    }

    private static void extendHeadingSpans(MutableNode node) {
        node.children.forEach(SourceIr::extendHeadingSpans);
        if (node.kind != SectionKind.HEADING || node.children.isEmpty()) {
            return;
        }
        int lastEnd = node.children.stream().mapToInt(c -> c.span.end()).max().orElse(node.span.end());
        if (lastEnd > node.span.end()) {
            node.span = new TextSpan(node.span.start(), lastEnd);
        }
    }

    /** ★`text` 在 span 扩展后重算——text 必须始终等于 {@code document.substring(span)}。 */
    private static SourceNode freeze(MutableNode n, String document) {
        List<SourceNode> children = n.children.stream().map(c -> freeze(c, document)).toList();
        String text = document.substring(n.span.start(), n.span.end());
        return new SourceNode(n.nodeId, n.kind, n.span, text, n.level, children);
    }
}
