package aster.core.resolve;

import aster.core.ast.ResolvedRole;
import aster.core.identifier.CaseFold;
import aster.core.identifier.Candidate;
import aster.core.identifier.VocabularyScopes;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 名称解析 R1 / R2 共用的候选选择规则（ADR 0051 §6.2）：精确优先、折叠唯一、
 * 同层多个不同规范名即歧义；源码声明按原写法与规范名双向匹配；词汇后接规范名直写。
 * 实例持有规范名反向索引缓存，按一次解析过程使用，非线程安全。
 */
public final class ResolutionChoice {

    /** 关键词构造器（Ok/Err/Some/None）：语法关键词，不查词汇。 */
    public static final Set<String> KEYWORD_CTORS = Set.of("Ok", "Err", "Some", "None");

    /** 参与匹配的一项：written 为可匹配的写法，canonical 为命中后的规范名。 */
    public record Entry(String written, String canonical, ResolvedRole role) {}

    /** 选择结果：命中、歧义或未命中。 */
    public sealed interface Choice permits Hit, Ambiguous, Miss {}

    public record Hit(Entry entry) implements Choice {}

    public record Ambiguous(List<Entry> candidates) implements Choice {
        public Ambiguous {
            candidates = List.copyOf(candidates);
        }
    }

    public record Miss() implements Choice {}

    public static final Choice MISS = new Miss();

    /** 一张词汇索引及命中后赋予的角色。 */
    public record VocabMap(Map<String, List<Candidate>> map, ResolvedRole role) {}

    /** 规范名 → 候选的反向索引，按词汇索引对象身份缓存，避免每次未命中都全表扫描。 */
    private final Map<Map<String, List<Candidate>>, Map<String, List<Candidate>>> canonicalIndex = new IdentityHashMap<>();

    /** 精确命中优先；无精确时折叠命中唯一才采用；同层多个不同规范名即歧义。 */
    public static Choice choose(List<Entry> exact, List<Entry> folded) {
        for (List<Entry> layer : List.of(exact, folded)) {
            List<Entry> uniq = distinct(layer);
            if (uniq.size() == 1) return new Hit(uniq.get(0));
            if (uniq.size() > 1) return new Ambiguous(uniq);
        }
        return MISS;
    }

    /** 源码声明（含导入）：按原写法与规范名双向匹配。 */
    public static Choice chooseDecl(List<Entry> decls, String written) {
        String w = CaseFold.nfc(written);
        String key = CaseFold.foldKey(w);
        List<Entry> exact = decls.stream().filter(d -> d.written().equals(w) || d.canonical().equals(w)).toList();
        List<Entry> folded = decls.stream()
            .filter(d -> CaseFold.foldKey(d.written()).equals(key) || CaseFold.foldKey(d.canonical()).equals(key))
            .toList();
        return choose(exact, folded);
    }

    /** 词汇（§6.2 第 4 步）后接规范名直写（第 5 步）。 */
    public Choice chooseVocab(List<VocabMap> maps, String written) {
        List<Entry> exact = maps.stream()
            .flatMap(m -> toEntries(VocabularyScopes.lookup(m.map(), written).exact(), m.role())).toList();
        List<Entry> folded = maps.stream()
            .flatMap(m -> toEntries(VocabularyScopes.lookup(m.map(), written).folded(), m.role())).toList();
        Choice picked = choose(exact, folded);
        if (!(picked instanceof Miss)) return picked;
        String w = CaseFold.nfc(written);
        List<Entry> direct = maps.stream()
            .flatMap(m -> toEntries(byCanonical(m.map()).getOrDefault(w, List.of()), m.role())).toList();
        return choose(direct, List.of());
    }

    /** 歧义候选的确定性文本：`规范名 (写法)` 按码点序以逗号连接。 */
    public static String formatCandidates(List<Entry> candidates) {
        return String.join(", ", candidates.stream()
            .map(c -> c.canonical() + " (" + c.written() + ")")
            .sorted(CaseFold::compareCodePoints)
            .toList());
    }

    private static Stream<Entry> toEntries(List<Candidate> cands, ResolvedRole role) {
        return cands.stream().map(c -> new Entry(c.written(), c.canonical(), role));
    }

    private static List<Entry> distinct(List<Entry> entries) {
        Map<String, Entry> seen = new LinkedHashMap<>();
        for (Entry e : entries) seen.put(e.role() + "\u0000" + e.canonical(), e);
        return new ArrayList<>(seen.values());
    }

    private Map<String, List<Candidate>> byCanonical(Map<String, List<Candidate>> map) {
        return canonicalIndex.computeIfAbsent(map, m -> {
            Map<String, List<Candidate>> index = new LinkedHashMap<>();
            m.values().stream().flatMap(List::stream)
                .forEach(c -> index.computeIfAbsent(c.canonical(), k -> new ArrayList<>()).add(c));
            return index;
        });
    }
}
