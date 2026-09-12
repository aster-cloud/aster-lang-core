package aster.core.nodeid;

import aster.core.nodeid.NodeIdMap.NodeIdentity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨版本 change impact —— 比较同一份程序的两个版本，回答
 * 「改了什么，谁受影响」。
 *
 * <h2>这是 ADR 0037 §4 的核心能力</h2>
 *
 * <pre>
 *   $10,000 → $20,000
 *        ↓
 *   PaymentApproval.threshold 已 stale
 * </pre>
 *
 * 之所以能回答，是因为 {@link NodeIdMap} 把「是哪个节点」（nodeId）与
 * 「它变了没有」（contentHash）拆成了两个字段：阈值改动时 nodeId 不变、
 * contentHash 变，于是可以报 {@code MODIFIED} 而不是
 * 「一个节点消失 + 一个节点出现」。
 *
 * <h2>★结果的解读边界</h2>
 *
 * {@code MODIFIED} 的判定是<b>内容指纹不同</b>，不是「语义变了」。
 * 两者大多数时候一致，但并非总是——例如把 {@code x plus y} 写成
 * {@code y plus x}，指纹变而语义可能等价。<b>本类只报「变了」，
 * 不声称「行为会不同」</b>；后者需要真正的语义比对。
 *
 * <p>同理 {@code REMOVED}/{@code ADDED} 在**重命名**时会成对出现
 * （见 {@link NodeIdMap} 的已知局限）——那是路径方案的固有代价，
 * 需由显式 rename 声明消解，而不是由本类猜测。
 */
public final class ChangeImpact {

    public enum ChangeKind {
        /** nodeId 两侧都在，contentHash 不同 → 同一个节点，内容变了。 */
        MODIFIED,
        /** nodeId 只在新版出现。 */
        ADDED,
        /** nodeId 只在旧版出现。 */
        REMOVED
    }

    /**
     * 一条变更。
     *
     * @param nodeId  稳定标识（{@code ADDED}/{@code REMOVED} 时只在一侧存在）
     * @param kind    变更类型
     * @param nodeKind 节点类型（{@code Func}/{@code If}/...）
     * @param staleAncestors 受本次变更波及的祖先 nodeId（由内向外），
     *                       即 ADR 0037 §4 里「谁已经 stale」的答案
     */
    public record Change(String nodeId, ChangeKind kind, String nodeKind,
                         List<String> staleAncestors) {}

    private ChangeImpact() {}

    /**
     * 比较两个版本的节点标识表。
     *
     * @param before 旧版本 {@link NodeIdMap#compute}
     * @param after  新版本 {@link NodeIdMap#compute}
     * @return 变更列表，按 nodeId 字典序（确定顺序，便于比对与快照）
     */
    public static List<Change> diff(Map<String, NodeIdentity> before,
                                    Map<String, NodeIdentity> after) {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(before.keySet());
        all.addAll(after.keySet());

        List<Change> changes = new ArrayList<>();
        for (String id : all.stream().sorted().toList()) {
            NodeIdentity b = before.get(id);
            NodeIdentity a = after.get(id);
            if (b == null) {
                changes.add(new Change(id, ChangeKind.ADDED, a.kind(), ancestorsOf(id, after)));
            } else if (a == null) {
                changes.add(new Change(id, ChangeKind.REMOVED, b.kind(), ancestorsOf(id, before)));
            } else if (!b.contentHash().equals(a.contentHash())) {
                changes.add(new Change(id, ChangeKind.MODIFIED, a.kind(), ancestorsOf(id, after)));
            }
        }
        return List.copyOf(changes);
    }

    /**
     * 一个节点变更后，哪些祖先随之 stale。
     *
     * <p>★注意方向：<b>祖先 stale，后代不 stale</b>。改了 if 条件里的阈值，
     * 是「包含它的那条规则」需要重新审视；而阈值节点的子节点（字面量本身）
     * 并没有变。反过来标记会把影响面夸大到整棵子树。
     */
    private static List<String> ancestorsOf(String nodeId, Map<String, NodeIdentity> universe) {
        List<String> out = new ArrayList<>();
        String cur = nodeId;
        while (true) {
            int cut = lastSegmentStart(cur);
            if (cut <= 0) {
                break;
            }
            cur = cur.substring(0, cut);
            if (universe.containsKey(cur)) {
                out.add(cur);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 找到最后一个路径段的起点。
     *
     * <p>路径形如 {@code $.decls{approve}.body.statements[0].cond}，分隔符是
     * {@code .}、{@code [}、{@code &#123;}。★不能简单用 {@code lastIndexOf('.')}——
     * 名字段 {@code {a.b.c}} 里可能含点（模块路径式的 Import 名就是如此），
     * 那样会从名字中间切断，产生一个不存在的祖先。
     */
    private static int lastSegmentStart(String path) {
        int depth = 0;
        for (int i = path.length() - 1; i >= 0; i--) {
            char c = path.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                depth--;
                if (depth == 0) {
                    // {name} 段：其起点是前面那个 '.'（如 .decls{approve}）
                    int dot = path.lastIndexOf('.', i);
                    return dot > 0 ? dot : i;
                }
            } else if (depth == 0 && (c == '.' || c == '[')) {
                return i;
            }
        }
        return -1;
    }
}
