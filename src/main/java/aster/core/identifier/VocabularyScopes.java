package aster.core.identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按角色与作用域组织的词汇视图（ADR 0051 §5）。构建时不覆盖：同折叠键的全部候选都保留，
 * 由解析器按位置判定。所有索引键均为 {@link CaseFold#foldKey(String)}(写法)。
 * {@link #canonicalJson()} 的输出须与 TS canonicalScopesJson 逐字节一致。
 */
public final class VocabularyScopes {

    /** 规范 JSON 的结构版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 候选数组的确定性顺序：(canonical, written, kind, source, parent) 逐项按码点比较，缺失的 parent 视为空串。 */
    private static final Comparator<Candidate> CANDIDATE_ORDER =
        Comparator.comparing(Candidate::canonical, CaseFold::compareCodePoints)
            .thenComparing(Candidate::written, CaseFold::compareCodePoints)
            .thenComparing((Candidate c) -> c.kind().getValue(), CaseFold::compareCodePoints)
            .thenComparing((Candidate c) -> c.source().value(), CaseFold::compareCodePoints)
            .thenComparing((Candidate c) -> c.parent() == null ? "" : c.parent(), CaseFold::compareCodePoints);

    private final Map<String, List<Candidate>> types;
    private final Map<String, Map<String, List<Candidate>>> fields;
    private final Map<String, Map<String, List<Candidate>>> enumMembers;
    private final Map<String, List<Candidate>> functions;
    private final Map<String, String> literals;

    private VocabularyScopes(Builder b) {
        this.types = freeze(b.types);
        this.fields = freezeScoped(b.fields);
        this.enumMembers = freezeScoped(b.enumMembers);
        this.functions = freeze(b.functions);
        this.literals = Collections.unmodifiableMap(b.literals);
    }

    /**
     * 查找结果：exact 为 NFC 后逐码点相等的候选；folded 为折叠键相同的全部候选（包含 exact），
     * 由解析器决定精确优先。
     */
    public record Lookup(List<Candidate> exact, List<Candidate> folded) {}

    /**
     * 从词汇表构建作用域视图。localized 为 term 候选，aliases 为 synonym 候选。
     *
     * @throws VocabularyScopeException 字段 / 枚举成员缺少 parent，或同折叠键的字面量宏内容冲突
     */
    public static VocabularyScopes build(DomainVocabulary vocabulary) {
        Builder b = new Builder();
        for (IdentifierMapping m : vocabulary.allMappings()) {
            b.place(m, candidate(m, m.localized(), Candidate.Source.TERM));
            for (String alias : m.aliases()) {
                b.place(m, candidate(m, alias, Candidate.Source.SYNONYM));
            }
        }
        return new VocabularyScopes(b);
    }

    /** 在一个折叠键索引中查找写法；未命中返回两个空列表。 */
    public static Lookup lookup(Map<String, List<Candidate>> index, String written) {
        String w = CaseFold.nfc(written);
        List<Candidate> folded = index.getOrDefault(CaseFold.foldKey(w), List.of());
        List<Candidate> exact = folded.stream().filter(c -> c.written().equals(w)).toList();
        return new Lookup(exact, List.copyOf(folded));
    }

    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    /** 类型：foldKey → 候选。 */
    public Map<String, List<Candidate>> types() {
        return types;
    }

    /** 字段：规范父名 → (foldKey → 候选)。 */
    public Map<String, Map<String, List<Candidate>>> fields() {
        return fields;
    }

    /** 枚举成员：规范枚举名 → (foldKey → 候选)。 */
    public Map<String, Map<String, List<Candidate>>> enumMembers() {
        return enumMembers;
    }

    /** 函数：foldKey → 候选。 */
    public Map<String, List<Candidate>> functions() {
        return functions;
    }

    /** 字面量宏：foldKey(触发词) → 字面量内容。 */
    public Map<String, String> literals() {
        return literals;
    }

    /**
     * 规范 JSON（契约见 TS canonicalScopesJson 的文档注释）：无空白；所有对象键按码点序；
     * 顶层键 enumMembers、fields、functions、literals、schemaVersion、types；候选数组按
     * (canonical, written, kind, source, parent) 码点序；字符串值均 NFC，折叠键不再二次规范化。
     */
    public String canonicalJson() {
        Map<String, Object> literalsJson = CanonicalJson.object();
        literals.forEach((k, v) -> literalsJson.put(k, CaseFold.nfc(v)));
        Map<String, Object> root = CanonicalJson.object();
        root.put("enumMembers", scopedJson(enumMembers));
        root.put("fields", scopedJson(fields));
        root.put("functions", bucketsJson(functions));
        root.put("literals", literalsJson);
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("types", bucketsJson(types));
        return CanonicalJson.write(root);
    }

    // ---- 构建 ----

    private static Candidate candidate(IdentifierMapping m, String written, Candidate.Source source) {
        return new Candidate(CaseFold.nfc(written), m.canonical(), m.kind(), m.parent(), source);
    }

    /** 构建期的可变累加器；插入顺序保留，与 TS Map 语义一致。 */
    private static final class Builder {
        final Map<String, List<Candidate>> types = new LinkedHashMap<>();
        final Map<String, Map<String, List<Candidate>>> fields = new LinkedHashMap<>();
        final Map<String, Map<String, List<Candidate>>> enumMembers = new LinkedHashMap<>();
        final Map<String, List<Candidate>> functions = new LinkedHashMap<>();
        final Map<String, String> literals = new LinkedHashMap<>();

        void place(IdentifierMapping m, Candidate cand) {
            String key = CaseFold.foldKey(cand.written());
            switch (m.kind()) {
                case STRUCT -> push(types, key, cand);
                case FUNCTION -> push(functions, key, cand);
                case LITERAL -> addLiteral(key, cand);
                case FIELD -> pushScoped(fields, requireParent(m, VocabularyScopeException.Code.FIELD_PARENT_REQUIRED, "字段"), key, cand);
                case ENUM_VALUE -> pushScoped(enumMembers, requireParent(m, VocabularyScopeException.Code.MEMBER_PARENT_REQUIRED, "枚举成员"), key, cand);
            }
        }

        private void addLiteral(String key, Candidate cand) {
            String prev = literals.get(key);
            if (prev != null && !prev.equals(cand.canonical())) {
                throw new VocabularyScopeException(VocabularyScopeException.Code.LITERAL_CONFLICT,
                    "字面量宏触发词 \"" + cand.written() + "\" 对应多个内容");
            }
            literals.put(key, cand.canonical());
        }

        private static String requireParent(IdentifierMapping m, VocabularyScopeException.Code code, String role) {
            if (m.parent() == null) {
                throw new VocabularyScopeException(code, role + " \"" + m.localized() + "\" 缺少 parent");
            }
            return m.parent();
        }

        private static void push(Map<String, List<Candidate>> map, String key, Candidate cand) {
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(cand);
        }

        private static void pushScoped(Map<String, Map<String, List<Candidate>>> map, String parent,
                                       String key, Candidate cand) {
            push(map.computeIfAbsent(parent, k -> new LinkedHashMap<>()), key, cand);
        }
    }

    private static Map<String, List<Candidate>> freeze(Map<String, List<Candidate>> map) {
        Map<String, List<Candidate>> out = new LinkedHashMap<>();
        map.forEach((k, list) -> out.put(k, List.copyOf(list)));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Map<String, List<Candidate>>> freezeScoped(Map<String, Map<String, List<Candidate>>> map) {
        Map<String, Map<String, List<Candidate>>> out = new LinkedHashMap<>();
        map.forEach((parent, inner) -> out.put(parent, freeze(inner)));
        return Collections.unmodifiableMap(out);
    }

    // ---- 规范 JSON ----

    private static Map<String, Object> candidateJson(Candidate c) {
        Map<String, Object> o = CanonicalJson.object();
        o.put("canonical", CaseFold.nfc(c.canonical()));
        o.put("kind", c.kind().getValue());
        o.put("source", c.source().value());
        o.put("written", CaseFold.nfc(c.written()));
        if (c.parent() != null) o.put("parent", CaseFold.nfc(c.parent()));
        return o;
    }

    private static Map<String, Object> bucketsJson(Map<String, List<Candidate>> map) {
        Map<String, Object> o = CanonicalJson.object();
        map.forEach((k, list) -> o.put(k, list.stream().sorted(CANDIDATE_ORDER).map(VocabularyScopes::candidateJson).toList()));
        return o;
    }

    private static Map<String, Object> scopedJson(Map<String, Map<String, List<Candidate>>> map) {
        Map<String, Object> o = CanonicalJson.object();
        map.forEach((parent, inner) -> o.put(CaseFold.nfc(parent), bucketsJson(inner)));
        return o;
    }
}
