package aster.core.mapping;

import aster.core.nodeid.NodeIdMap;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 从「canonical 文本 + 带 origin 的节点」<b>机械生成</b>候选映射（ADR 0037）。
 *
 * <p>与 TS 侧 {@code src/mapping/candidate-generator.ts} <b>逐条对等</b>。
 *
 * <h2>★本类不做任何猜测</h2>
 *
 * 它只是把 {@code origin} 的行列翻译成字符偏移并切片。切不出东西就
 * <b>跳过并记录</b>，绝不编造——一条错的候选比没有候选更糟，
 * 因为它会让双向导航静默跳到错误位置。
 *
 * <h2>★坐标系：先在 canonical 文本上切，再重定位回原文</h2>
 *
 * {@code origin} 记的是 canonical 文本的行列。canonicalize 保持行数不变，但会改变行内长度
 * （英文删冠词等），所以 canonical 偏移不能直接当原文偏移用。{@link #generate} 在 canonical 上
 * 切出文本，{@link #relocateToSource} 再在原文同一行里找到同一段文本（按该行内的出现序号对应），
 * 产出原文坐标的候选。找不到就跳过并报告，绝不推算。
 *
 * <h2>★路径规则只有一份</h2>
 *
 * 节点遍历一律走 {@link NodeIdMap#forEachIrNode}，候选的 nodeId 与 resolve 的键天然一致。
 */
public final class CandidateGenerator {

    /** 带 origin 的节点。 */
    public record OriginatedNode(String nodeId, String kind,
                                 int startLine, int startCol,
                                 int endLine, int endCol) {}

    /** 被跳过的节点及原因。★必须如实报告。 */
    public record Skipped(String nodeId, String why) {}

    public record GenerationResult(List<MappingIr.CandidateMapping> candidates,
                                   List<Skipped> skipped) {}

    private CandidateGenerator() {}

    /**
     * 生成候选映射。
     *
     * @param canonical canonical 文本（origin 的行列以它为准）；结果须再经 {@link #relocateToSource}
     * @param nodes     带 origin 的节点
     */
    public static GenerationResult generate(String canonical, List<OriginatedNode> nodes) {
        int[] lineStarts = computeLineStarts(canonical);
        List<MappingIr.CandidateMapping> candidates = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (OriginatedNode node : nodes) {
            Integer start = toOffset(lineStarts, canonical.length(), node.startLine(), node.startCol(), false);
            Integer end = toOffset(lineStarts, canonical.length(), node.endLine(), node.endCol(), true);

            if (start == null || end == null) {
                skipped.add(new Skipped(node.nodeId(), "origin 的行列超出源码范围"));
                continue;
            }
            if (end <= start) {
                // ★零宽或倒置区间：多半是 end 未被真实计算（占位值）。
                //   生成零宽候选毫无意义，且会让 verifier 判 REJECTED 制造噪声。
                skipped.add(new Skipped(node.nodeId(),
                    "区间非法或为零宽：[" + start + ", " + end + ")"));
                continue;
            }

            String text = canonical.substring(start, end);
            if (text.isBlank()) {
                // ★自检：切片全是空白说明 origin 与文本没对齐。宁可跳过并报告。
                skipped.add(new Skipped(node.nodeId(),
                    "切片为空白——canonical 与源码可能未对齐"));
                continue;
            }

            candidates.add(new MappingIr.CandidateMapping(
                new MappingIr.TextSpan(start, end), text, node.nodeId()));
        }

        return new GenerationResult(List.copyOf(candidates), List.copyOf(skipped));
    }

    /**
     * 把 canonical 坐标的候选重定位到原文坐标。
     *
     * <p>候选起点所在行号在两边相同（canonicalize 保持行数）。数出候选文本在 canonical 该行内、
     * 起点之前出现了几次，再在原文同一行里取同序号的出现位置。字面量文本（数字、带引号的字符串、
     * 布尔）不被 canonicalize 改写，所以序号一一对应。
     *
     * <p>★任何对不上的情况都跳过并报告：两边行数不同、该文本在两边同一行的出现次数不同
     * （canonicalize 增删了同样的子串，如智能引号归一化后多出一个 {@code "x"}，按序号配对会配错）。
     * 与 TS 侧 {@code relocateToSource} 同规则、同原因文本。
     */
    public static GenerationResult relocateToSource(String source, String canonical, GenerationResult generated) {
        int[] canStarts = computeLineStarts(canonical);
        int[] srcStarts = computeLineStarts(source);
        List<Skipped> skipped = new ArrayList<>(generated.skipped());
        if (canStarts.length != srcStarts.length) {
            for (MappingIr.CandidateMapping c : generated.candidates()) {
                skipped.add(new Skipped(c.nodeId(), "原文与 canonical 行数不同（" + srcStarts.length
                    + " ≠ " + canStarts.length + "），无法定位原文位置"));
            }
            return new GenerationResult(List.of(), List.copyOf(skipped));
        }

        List<MappingIr.CandidateMapping> candidates = new ArrayList<>();
        for (MappingIr.CandidateMapping c : generated.candidates()) {
            int line = lineIndexOf(canStarts, c.span().start());
            int canLineEnd = line + 1 < canStarts.length ? canStarts[line + 1] - 1 : canonical.length();
            int lineEnd = line + 1 < srcStarts.length ? srcStarts[line + 1] - 1 : source.length();
            int canCount = countOccurrences(canonical, c.text(), canStarts[line], canLineEnd + 1);
            int srcCount = countOccurrences(source, c.text(), srcStarts[line], lineEnd + 1);
            if (canCount != srcCount) {
                skipped.add(new Skipped(c.nodeId(), "候选文本 " + jsonQuote(c.text()) + " 在第 " + (line + 1)
                    + " 行的出现次数不同（原文 " + srcCount + "，canonical " + canCount + "），无法唯一定位"));
                continue;
            }
            int ordinal = countOccurrences(canonical, c.text(), canStarts[line], c.span().start());
            // 两边次数相等且候选自身就是 canonical 该行的第 ordinal 次出现，故原文必有第 ordinal 次。
            int start = nthOccurrence(source, c.text(), srcStarts[line], lineEnd, ordinal);
            candidates.add(new MappingIr.CandidateMapping(
                new MappingIr.TextSpan(start, start + c.text().length()), c.text(), c.nodeId()));
        }
        return new GenerationResult(List.copyOf(candidates), List.copyOf(skipped));
    }

    /**
     * 遍历 IR JSON，收集<b>带 origin 且带 value</b> 的节点。
     *
     * <p>★与 TS 侧 {@code collectLiteralNodes} 同规则：只收字面量
     * （有 {@code value} 字段），因为只有它们可能被机械证明。
     */
    public static List<OriginatedNode> collectLiteralNodes(JsonNode ir) {
        List<OriginatedNode> out = new ArrayList<>();
        NodeIdMap.forEachIrNode(ir, (node, nodeId) -> {
            JsonNode origin = node.get("origin");
            if (node.has("value") && isOrigin(origin)) {
                out.add(new OriginatedNode(
                    nodeId, node.get("kind").asText(),
                    origin.path("start").path("line").asInt(),
                    origin.path("start").path("col").asInt(),
                    origin.path("end").path("line").asInt(),
                    origin.path("end").path("col").asInt()));
            }
        });
        return out;
    }

    private static boolean isOrigin(JsonNode v) {
        return v != null && v.isObject()
            && v.path("start").path("line").isNumber()
            && v.path("start").path("col").isNumber()
            && v.path("end").path("line").isNumber()
            && v.path("end").path("col").isNumber();
    }

    /** 每行起始偏移。索引 i 对应第 i+1 行（origin 是 1-based）。 */
    private static int[] computeLineStarts(String s) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 1-based 行列 → 字符偏移；越界返回 {@code null}（由调用方记为 skipped）。
     *
     * <p>★{@code end} 是开区间，允许正好落在行尾（{@code allowLineEnd}）；{@code start} 必须指向该行的
     * 真实字符。二者都不允许越过<b>该行</b>的行尾——否则切片会静默跨进下一行。
     */
    private static Integer toOffset(int[] lineStarts, int len, int line, int col, boolean allowLineEnd) {
        if (line < 1 || line > lineStarts.length || col < 1) return null;
        int lineStart = lineStarts[line - 1];
        int lineEnd = line < lineStarts.length ? lineStarts[line] - 1 : len;
        int off = lineStart + (col - 1);
        return off > lineEnd || (off == lineEnd && !allowLineEnd) ? null : off;
    }

    /** 偏移所在的行（0-based）。 */
    private static int lineIndexOf(int[] lineStarts, int offset) {
        int line = 0;
        while (line + 1 < lineStarts.length && lineStarts[line + 1] <= offset) line++;
        return line;
    }

    /** {@code text} 在 {@code [from, to)} 内起始的出现次数（允许重叠）。 */
    private static int countOccurrences(String s, String text, int from, int to) {
        int n = 0;
        for (int i = s.indexOf(text, from); i != -1 && i < to; i = s.indexOf(text, i + 1)) n++;
        return n;
    }

    /** 起点落在 {@code [from, lineEnd]} 内的第 {@code ordinal} 次（0-based）出现；没有则为 -1。 */
    private static int nthOccurrence(String s, String text, int from, int lineEnd, int ordinal) {
        int n = 0;
        for (int i = s.indexOf(text, from); i != -1 && i <= lineEnd; i = s.indexOf(text, i + 1)) {
            if (n == ordinal) return i;
            n++;
        }
        return -1;
    }

    /** 与 TS {@code JSON.stringify(text)} 对常见字符同形，使跳过原因两侧一致。 */
    private static String jsonQuote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }
}
