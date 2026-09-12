package aster.core.nodeid;

import aster.core.nodeid.NodeIdMap.NodeIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * 一次**显式声明**的重命名：`Rule approve` → `Rule assess`。
     *
     * <h2>为什么必须显式声明，不能自动推断</h2>
     *
     * 直觉上可以「按 contentHash 配对」——内容没变的节点自动认亲。**实测该方案
     * 不成立**：contentHash <b>不唯一</b>。两条函数体相同的规则，其
     * {@code body} / {@code statements[0]} / {@code ret} 等各级节点的 hash
     * 全部相同：
     *
     * <pre>
     *   Rule alpha, produce:    Rule beta, produce:
     *     Return 1.               Return 1.
     *
     *   → $.decls{alpha}.body 与 $.decls{beta}.body 的 contentHash **完全相同**
     * </pre>
     *
     * 于是把 {@code alpha} 改名为 {@code gamma} 后，{@code beta.body} 的 hash
     * 在新版里能匹配到<b>两个</b>候选（{@code gamma.body} 与 {@code beta.body}），
     * 无法判定谁是谁——自动配对会把两条规则的身份互换，而且<b>不报错</b>。
     *
     * <p>★把两个不同的节点当成同一个，比「识别为新节点」危险得多：前者会让
     * change impact 给出**错误**的溯源答案，后者只是丢失了历史关联。
     * 故本类的立场是：<b>宁可少认，不可错认</b>。
     *
     * @param oldName 旧名字（如 {@code approve}）
     * @param newName 新名字（如 {@code assess}）
     */
    public record Rename(String oldName, String newName) {
        public Rename {
            if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
                throw new IllegalArgumentException("rename 的新旧名字都不能为空");
            }
            if (oldName.equals(newName)) {
                throw new IllegalArgumentException("rename 的新旧名字相同：" + oldName);
            }
        }
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
        return diff(before, after, List.of());
    }

    /**
     * 比较两个版本，并应用一组**显式声明**的重命名。
     *
     * <p>重命名按「路径前缀改写」处理：把旧版所有 {@code $.decls{oldName}…} 形态的
     * nodeId 改写成 {@code $.decls{newName}…}，再做常规 diff。这样整棵子树**一次性
     * 迁移**，而不是逐个节点认亲——既避免了 contentHash 不唯一带来的错配
     * （见 {@link Rename}），也天然保证子树内部的相对结构不受影响。
     *
     * <p>效果（实测 16 节点样本，改名一条规则）：
     * <pre>
     *   无声明  31 条变更（15 REMOVED + 15 ADDED + 1 MODIFIED）← 全是噪声
     *   有声明   0 条变更                                      ← 只是换了名字
     * </pre>
     *
     * @param renames 显式声明的重命名列表；空列表等价于 {@link #diff(Map, Map)}
     * @throws IllegalArgumentException 同一个 oldName 被声明重命名到多个不同的新名字
     */
    public static List<Change> diff(Map<String, NodeIdentity> before,
                                    Map<String, NodeIdentity> after,
                                    List<Rename> renames) {
        if (renames != null && !renames.isEmpty()) {
            before = applyRenames(before, renames);
        }
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
     * 把旧版的 nodeId 按声明的重命名做**路径段替换**。
     *
     * <p>★只替换完整的 {@code {name}} 路径段，不做子串替换。路径里的名字段形如
     * {@code .decls{approve}}，若用朴素的字符串 replace，{@code approve} 会误伤
     * {@code approveAll}、也会误伤恰好含该子串的其他段。
     *
     * <p>★同一个 oldName 不允许被声明成多个不同的新名字——那是自相矛盾的输入，
     * 静默取其一会给出无声错误的溯源结果，故直接拒绝。
     */
    private static Map<String, NodeIdentity> applyRenames(Map<String, NodeIdentity> before,
                                                          List<Rename> renames) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Rename r : renames) {
            String prev = mapping.putIfAbsent(r.oldName(), r.newName());
            if (prev != null && !prev.equals(r.newName())) {
                throw new IllegalArgumentException(
                    "同一个名字被声明重命名到多个目标：" + r.oldName()
                        + " → " + prev + " / " + r.newName());
            }
        }

        Map<String, NodeIdentity> out = new LinkedHashMap<>();
        for (Map.Entry<String, NodeIdentity> e : before.entrySet()) {
            String renamed = renamePathSegments(e.getKey(), mapping);
            NodeIdentity v = e.getValue();
            out.put(renamed, renamed.equals(e.getKey())
                ? v
                : new NodeIdentity(renamed, v.contentHash(), v.kind()));
        }
        return out;
    }

    /** 逐个 {@code {name}} 段做整段匹配替换。 */
    private static String renamePathSegments(String path, Map<String, String> mapping) {
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = path.indexOf('}', i);
            if (close < 0) { // 不成对的 '{'：原样输出，不猜
                out.append(path, i, path.length());
                break;
            }
            String name = path.substring(i + 1, close);
            out.append('{').append(mapping.getOrDefault(name, name)).append('}');
            i = close + 1;
        }
        return out.toString();
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
