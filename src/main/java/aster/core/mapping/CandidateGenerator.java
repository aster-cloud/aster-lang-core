package aster.core.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 从「源码 + 带 origin 的节点」<b>机械生成</b>候选映射（ADR 0037）。
 *
 * <p>与 TS 侧 {@code src/mapping/candidate-generator.ts} <b>逐条对等</b>。
 *
 * <h2>★本类不做任何猜测</h2>
 *
 * 它只是把 {@code origin} 的行列翻译成字符偏移并切片。切不出东西就
 * <b>跳过并记录</b>，绝不编造——一条错的候选比没有候选更糟，
 * 因为它会让双向导航静默跳到错误位置。
 *
 * <h2>★路径规则必须三方一致</h2>
 *
 * 本类的 {@code segmentOf} 与 {@link aster.core.nodeid.NodeIdMap} 以及
 * TS 侧的同名函数<b>必须逐字一致</b>。不一致时候选的 nodeId 与
 * resolve 的键对不上，表现为「节点不存在」——极易被误读成 IR 有问题。
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
     * @param source canonical 文本（与 IR 的 origin 行列对齐）
     * @param nodes  带 origin 的节点
     */
    public static GenerationResult generate(String source, List<OriginatedNode> nodes) {
        int[] lineStarts = computeLineStarts(source);
        List<MappingIr.CandidateMapping> candidates = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (OriginatedNode node : nodes) {
            Integer start = toOffset(lineStarts, source.length(), node.startLine(), node.startCol());
            Integer end = toOffset(lineStarts, source.length(), node.endLine(), node.endCol());

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

            String text = source.substring(start, end);
            if (text.isBlank()) {
                // ★自检：切片全是空白说明 canonical 与源码没对齐。
                //   此时产出的候选必然错位——宁可跳过并报告。
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
     * 遍历 IR JSON，收集<b>带 origin 且带 value</b> 的节点。
     *
     * <p>★与 TS 侧 {@code collectLiteralNodes} 同规则：只收字面量
     * （有 {@code value} 字段），因为只有它们可能被机械证明。
     */
    public static List<OriginatedNode> collectLiteralNodes(JsonNode ir) {
        List<OriginatedNode> out = new ArrayList<>();
        walk(ir, "$", out);
        return out;
    }

    private static void walk(JsonNode node, String path, List<OriginatedNode> out) {
        if (node == null || !node.isObject()) return;

        JsonNode kind = node.get("kind");
        JsonNode origin = node.get("origin");
        if (kind != null && kind.isTextual() && node.has("value") && isOrigin(origin)) {
            out.add(new OriginatedNode(
                path, kind.asText(),
                origin.path("start").path("line").asInt(),
                origin.path("start").path("col").asInt(),
                origin.path("end").path("line").asInt(),
                origin.path("end").path("col").asInt()));
        }

        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if ("origin".equals(e.getKey())) continue;
            JsonNode v = e.getValue();
            if (v.isArray()) {
                for (int i = 0; i < v.size(); i++) {
                    walk(v.get(i), path + "." + e.getKey() + segmentOf(v.get(i), i), out);
                }
            } else {
                walk(v, path + "." + e.getKey(), out);
            }
        }
    }

    /**
     * ★与 {@code NodeIdMap.segmentOf} / TS 侧同规则——三方必须一致。
     *
     * <p>{@code _} 是占位符不是名字，必须退回下标，否则同一函数体里的
     * 多条裸表达式语句会塌成同一个 nodeId。
     */
    private static String segmentOf(JsonNode element, int index) {
        if (element != null && element.isObject()) {
            JsonNode name = element.get("name");
            if (name != null && name.isTextual() && !"_".equals(name.asText())) {
                return "{" + name.asText() + "}";
            }
            JsonNode p = element.get("path");
            if (p != null && p.isTextual()) return "{" + p.asText() + "}";
        }
        return "[" + index + "]";
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
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        return arr;
    }

    /** 1-based 行列 → 字符偏移；越界返回 {@code null}（由调用方记为 skipped）。 */
    private static Integer toOffset(int[] lineStarts, int len, int line, int col) {
        if (line < 1 || line > lineStarts.length || col < 1) return null;
        int off = lineStarts[line - 1] + (col - 1);
        return off > len ? null : off;
    }
}
