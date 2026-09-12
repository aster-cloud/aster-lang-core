package aster.core.nodeid;

import aster.core.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable IR Node ID —— 为 Core IR 的每个节点计算一对**跨版本稳定**的标识。
 *
 * <h2>为什么是两个字段而不是一个</h2>
 *
 * ADR 0037 §8 用实测回答了「结构 hash 还是路径」这个权衡：<b>三种候选方案各有一个
 * 灾难场景，且互不重叠</b>（基线 16 节点，存活率）：
 *
 * <pre>
 *   编辑                  结构hash   结构路径   命名作用域路径
 *   改阈值                 10/16      16/16      16/16
 *   前面插入一条规则        16/16       5/16      16/16
 *   重命名规则             14/16      16/16       1/16
 *   改分支内字面量          9/16      16/16      16/16
 * </pre>
 *
 * 结论是<b>复合键</b>，两者职责分离：
 *
 * <pre>
 *   nodeId      = 命名作用域路径   ← 回答「是哪个节点」（跨版本稳定）
 *   contentHash = 子树结构 hash    ← 回答「它变了没有」（change impact 的信号）
 * </pre>
 *
 * 这正对应 ADR 0037 §4 的需求：{@code $10,000 → $20,000} 时 {@code nodeId} 不变
 * （还是那个阈值节点）、{@code contentHash} 变（于是下游标 stale）。
 * 若只用结构 hash 当 ID，「改了值」会表现成「旧节点消失 + 新节点出现」，
 * <b>change impact 无法表达</b>。
 *
 * <h2>与 ADR 0032 anchor 的边界（★不可混用）</h2>
 *
 * <pre>
 *   anchor ("L21C5-L21C22")  位置派生，**同一版本内**稳定 → trace 跨执行聚合
 *   nodeId (命名作用域路径)   结构派生，**跨版本**稳定     → 双向导航 / change impact
 * </pre>
 *
 * ADR 0032 §6.1 已就此修订：原文「不引入独立节点 ID 体系」反对的是 <b>UUID</b>
 * （随机、跨编译不稳定），本类不是 UUID；而 anchor 在结构上就不解决跨版本身份
 * （0032 §5 自承「改一行则下面 anchor 全变」并接受该代价）。
 *
 * <p>★两者并存时<b>不得用其中一个去做另一个的工作</b>——混用会让「跨版本统计」
 * 悄悄退化成「同版本统计」，且不报错。
 *
 * <h2>已知局限（ADR 0037 §8.5）</h2>
 *
 * <b>重命名会使该子树的 nodeId 全部改变</b>（实测最差 1/16）。这是命名作用域路径
 * 的固有代价，<b>本类不试图掩盖它</b>：重命名是一次**有意的身份迁移**，应当显式
 * 声明（rename map），而不是指望算法猜出来。猜错的代价是把两个不同的节点当成
 * 同一个——那比「识别为新节点」更危险。
 */
public final class NodeIdMap {

    /** ID 方案版本。变更路径构造规则或 hash 口径必须 bump，否则跨版本比对会静默错配。 */
    public static final String NODE_ID_VERSION = "aster-node-id/v1";

    /**
     * 一个节点的稳定标识。
     *
     * @param nodeId      命名作用域路径，如 {@code $.decls{approve}.body.statements[0]}
     * @param contentHash 该节点子树（剥掉 origin 与 id 字段）的 canonical hash
     * @param kind        节点类型（{@code Func} / {@code If} / ...），便于调试与过滤
     */
    public record NodeIdentity(String nodeId, String contentHash, String kind) {}

    private NodeIdMap() {}

    /**
     * 为一棵 Core IR（已序列化为 JsonNode）计算全部节点的稳定标识。
     *
     * @param ir Core IR 的 JSON 表示
     * @return nodeId → NodeIdentity，按路径字典序（{@link LinkedHashMap} 保持确定顺序）
     */
    public static Map<String, NodeIdentity> compute(JsonNode ir) {
        Map<String, NodeIdentity> out = new LinkedHashMap<>();
        walk(ir, "$", out);
        return Collections.unmodifiableMap(out);
    }

    private static void walk(JsonNode node, String path, Map<String, NodeIdentity> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        // 只有带 kind 的对象才是「IR 节点」；Field/Param 等无 kind 的结构体不单独发 ID，
        // 它们随宿主节点的 contentHash 一起变化。
        if (node.hasNonNull("kind")) {
            out.put(path, new NodeIdentity(path, contentHashOf(node), node.get("kind").asText()));
        }
        node.properties().forEach(entry -> {
            String field = entry.getKey();
            if (isIdentityIrrelevant(field)) {
                return;
            }
            JsonNode value = entry.getValue();
            if (value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    walk(value.get(i), path + "." + field + segmentOf(value.get(i), i), out);
                }
            } else {
                walk(value, path + "." + field, out);
            }
        });
    }

    /**
     * 数组元素的路径段：<b>有名字就用名字</b>，否则退回下标。
     *
     * <p>这一行是整个方案的关键——它让「在前面插入一条规则」不再移动后续规则的身份
     * （实测：纯下标路径 5/16 存活，改用名字后 16/16）。
     */
    private static String segmentOf(JsonNode element, int index) {
        if (element != null && element.isObject() && element.hasNonNull("name")) {
            return "{" + element.get("name").asText() + "}";
        }
        // Import 没有 name，用 path 充当名字：它同样是「重排后仍指同一个导入」的标识。
        if (element != null && element.isObject() && element.hasNonNull("path")) {
            return "{" + element.get("path").asText() + "}";
        }
        return "[" + index + "]";
    }

    /**
     * 计算节点子树的内容指纹。
     *
     * <p>★必须剥掉 {@code origin}：位置不是内容。否则「在前面插入一条规则」会让后续
     * 所有节点的 contentHash 变化，change impact 报出一堆假阳性——而那正是
     * nodeId 花力气避开的问题。
     */
    private static String contentHashOf(JsonNode node) {
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        stripIdentityIrrelevant(copy);
        return CanonicalJson.canonicalHash(copy);
    }

    /** 不参与身份与内容判定的字段。 */
    private static boolean isIdentityIrrelevant(String field) {
        return "origin".equals(field);
    }

    private static void stripIdentityIrrelevant(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> drop = new ArrayList<>();
            obj.fieldNames().forEachRemaining(f -> {
                if (isIdentityIrrelevant(f)) {
                    drop.add(f);
                }
            });
            drop.forEach(obj::remove);
            obj.forEach(NodeIdMap::stripIdentityIrrelevant);
        } else if (node.isArray()) {
            node.forEach(NodeIdMap::stripIdentityIrrelevant);
        }
    }
}
