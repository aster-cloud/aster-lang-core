package aster.core.layout;

import aster.core.identifier.CaseFold;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 策略包显示排版规则 L1–L9 的字符串部分（ADR 0059 修订 4 §4）：CLI 与 aster-api 共用，与 ts {@code ./layout} 和
 * aster-lang-test 参照实现 {@code display.mjs} 跑同一组向量。
 *
 * <p>只用字符串运算、{@link CaseFold#foldKey}（NFC + Unicode 15.1 简单折叠）与标识符字符类
 * {@code [\p{L}\p{M}\p{Nd}_]}；值是否被切进结构 span 由 L9 的补空格不变性交给编译器判定，这里不依赖任何词法器
 * （不移植 ts {@code value-tokens.ts}，修订 4 D9）。
 */
public final class PackageLayoutRules {

    /** L7 的 ASCII 运算符字符。 */
    public static final List<String> ASCII_OPERATORS = List.of("+", "-", "*", "/", "%", "<", ">", "=", "!", "&", "|", "^", "~");

    /** 11 个运算符 kind（core {@code UserAliasValidator} 的运算符档，ADR 0060 §1.1）。 */
    public static final List<SemanticTokenKind> OPERATOR_KINDS = List.of(
        SemanticTokenKind.PLUS, SemanticTokenKind.MINUS_WORD, SemanticTokenKind.TIMES, SemanticTokenKind.DIVIDED_BY,
        SemanticTokenKind.INTEGER_DIVIDED_BY, SemanticTokenKind.MODULO, SemanticTokenKind.LESS_THAN,
        SemanticTokenKind.GREATER_THAN, SemanticTokenKind.EQUALS_TO, SemanticTokenKind.AT_LEAST, SemanticTokenKind.AT_MOST);

    /** 字面量关键词 kind（L6(c)）。 */
    public static final List<SemanticTokenKind> LITERAL_KINDS = List.of(
        SemanticTokenKind.TRUE, SemanticTokenKind.FALSE, SemanticTokenKind.NULL, SemanticTokenKind.NONE);

    private static final Pattern IDENT = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}_]");
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");
    private static final Pattern QUOTES = Pattern.compile("[\"“”「」『』]");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** ECMAScript {@code String.prototype.trim} 所认的空白（WhiteSpace ∪ LineTerminator）。 */
    private static final int[] ES_WHITESPACE = {
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20, 0xa0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff,
    };

    private PackageLayoutRules() {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 包排版 L1–L7（不编译）。{@code policySource} 为 {@code policy/policy.aster} 的字节（严格 UTF-8 解码，不去 BOM）。
     *
     * @return 解析后的排版
     * @throws LayoutException 第一条失败的规则
     */
    public static PolicyLayout checkPackage(byte[] layoutJson, byte[] displayAster, byte[] policySource, int layoutVersion,
                                            Collection<String> vocabularyLocalized, Collection<String> literalKeywords,
                                            Collection<String> operatorSpellings) {
        String source;
        try {
            source = LayoutFormat.decodeStrict(policySource);
        } catch (IllegalArgumentException e) {
            source = null;
        }
        return check(layoutJson, displayAster, source, layoutVersion, vocabularyLocalized, literalKeywords, operatorSpellings);
    }

    /** 同上，{@code source} 为已解码的规范源码文本。 */
    public static PolicyLayout checkPackage(byte[] layoutJson, byte[] displayAster, String source, int layoutVersion,
                                            Collection<String> vocabularyLocalized, Collection<String> literalKeywords,
                                            Collection<String> operatorSpellings) {
        return check(layoutJson, displayAster, source, layoutVersion, vocabularyLocalized, literalKeywords, operatorSpellings);
    }

    private static PolicyLayout check(byte[] layoutJson, byte[] displayAster, String source, int layoutVersion,
                                      Collection<String> vocabularyLocalized, Collection<String> literalKeywords,
                                      Collection<String> operatorSpellings) {
        PolicyLayout layout = LayoutFormat.parseStrict(layoutJson);
        if (layoutVersion != 1) {
            throw new LayoutException("L1", "layout v 1 differs from manifest display.layoutVersion " + layoutVersion);
        }
        LayoutLimits.check(layout, source == null ? "" : source);
        if (source == null || !layout.toCanonical().equals(source)) {
            throw new LayoutException("L3", "toCanonical(layout) differs from policy/policy.aster");
        }
        if (!Arrays.equals(layout.toDisplay().getBytes(StandardCharsets.UTF_8), displayAster)) {
            throw new LayoutException("L4", "display.aster differs from toDisplay(layout)");
        }
        stringRules(layout, vocabularyLocalized, literalKeywords, operatorSpellings);
        return layout;
    }

    /** 派生排版 L′（run --display / show --from-display）：L2、L5、L6、L7。 */
    public static void checkDerived(PolicyLayout layout, Collection<String> vocabularyLocalized,
                                    Collection<String> literalKeywords, Collection<String> operatorSpellings) {
        LayoutLimits.check(layout, layout.toCanonical());
        stringRules(layout, vocabularyLocalized, literalKeywords, operatorSpellings);
    }

    private static void stringRules(PolicyLayout layout, Collection<String> vocabularyLocalized,
                                    Collection<String> literalKeywords, Collection<String> operatorSpellings) {
        checkContentParity(layout);
        checkStructuralValues(layout, vocabularyLocalized, literalKeywords);
        checkOperators(layout, operatorSpellings);
    }

    // ------------------------------------------------------------------
    // L5 / L6 / L7
    // ------------------------------------------------------------------

    /** L5：至少一个内容 span 去掉 ECMAScript 空白后非空；结构 canonical 不含引号。 */
    static void checkContentParity(PolicyLayout layout) {
        for (LayoutSpan s : layout.spans()) {
            if (s instanceof LayoutSpan.Structural st && QUOTES.matcher(st.canonical()).find()) {
                throw new LayoutException("L5", "structural span canonical contains a string literal quote");
            }
        }
        for (LayoutSpan s : layout.spans()) {
            if (s instanceof LayoutSpan.Content c && !isEsBlank(c.text())) {
                return;
            }
        }
        throw new LayoutException("L5", "layout has no non-empty content span");
    }

    private static boolean isEsBlank(String s) {
        return s.codePoints().allMatch(cp -> Arrays.binarySearch(ES_WHITESPACE, cp) >= 0);
    }

    /** L6：结构 canonical 不含 \p{Nd}，不整词出现词汇 localized 与字面量关键词拼写。 */
    static void checkStructuralValues(PolicyLayout layout, Collection<String> vocabularyLocalized, Collection<String> literalKeywords) {
        List<String> names = foldAll(vocabularyLocalized, literalKeywords);
        for (LayoutSpan s : layout.spans()) {
            if (!(s instanceof LayoutSpan.Structural st)) {
                continue;
            }
            if (DIGIT.matcher(st.canonical()).find()) {
                throw new LayoutException("L6", "structural span canonical contains a digit");
            }
            String folded = CaseFold.foldKey(st.canonical());
            for (String n : names) {
                if (containsWholeWord(folded, n)) {
                    throw new LayoutException("L6", "structural span canonical contains a value word");
                }
            }
        }
    }

    /** L7：每个结构 span 的 canonical 与 display 的运算符序列完全相同。 */
    static void checkOperators(PolicyLayout layout, Collection<String> operatorSpellings) {
        List<Operator> table = operatorTable(operatorSpellings);
        for (LayoutSpan s : layout.spans()) {
            if (s instanceof LayoutSpan.Structural st
                && !operatorSequence(st.canonical(), table).equals(operatorSequence(st.display(), table))) {
                throw new LayoutException("L7", "structural span hides or rewrites an operator");
            }
        }
    }

    /** L7 的一个运算符拼写（已 foldKey）。 */
    record Operator(String text, int codePoints, boolean word) {
    }

    /** 运算符表：ASCII 运算符 ∪ 给出的拼写（foldKey、去重），按码点数降序、同长按码点序。 */
    static List<Operator> operatorTable(Collection<String> operatorSpellings) {
        List<String> all = new ArrayList<>(ASCII_OPERATORS);
        all.addAll(operatorSpellings);
        List<Operator> out = new ArrayList<>();
        for (String s : foldAll(all)) {
            out.add(new Operator(s, s.codePointCount(0, s.length()), s.codePoints().anyMatch(PackageLayoutRules::isIdentCodePoint)));
        }
        out.sort(Comparator.comparingInt(Operator::codePoints).reversed()
            .thenComparing((a, b) -> CaseFold.compareCodePoints(a.text(), b.text())));
        return out;
    }

    /** 一段文本的运算符序列：foldKey 后从左到右扫描，命中即记下并跳过，否则前进一个码点。 */
    static List<String> operatorSequence(String text, List<Operator> table) {
        String t = CaseFold.foldKey(text);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < t.length()) {
            Operator hit = null;
            for (Operator op : table) {
                if (t.startsWith(op.text(), i)
                    && (!op.word() || (!isIdentCodePoint(codePointBefore(t, i)) && !isIdentCodePoint(codePointAt(t, i + op.text().length()))))) {
                    hit = op;
                    break;
                }
            }
            if (hit != null) {
                out.add(hit.text());
                i += hit.text().length();
            } else {
                i += Character.charCount(t.codePointAt(i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // L8 / L9 的字符串部分
    // ------------------------------------------------------------------

    /** IR 中键为 {@code name} 或 {@code written} 的全部字符串值，按 JSON 文本中的出现顺序去重。 */
    public static List<String> irNameStrings(String coreIrJson) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            walk(JSON.readTree(coreIrJson), out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Core IR is not JSON", e);
        }
        return List.copyOf(out);
    }

    private static void walk(JsonNode n, Set<String> out) {
        if (n.isArray()) {
            for (JsonNode x : n) {
                walk(x, out);
            }
        } else if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (("name".equals(e.getKey()) || "written".equals(e.getKey())) && e.getValue().isTextual()) {
                    out.add(e.getValue().textValue());
                } else {
                    walk(e.getValue(), out);
                }
            }
        }
    }

    /** L8：整词出现在任一结构 canonical 中的 IR 名字（按 irNames 顺序）；空表示通过。 */
    public static List<String> structuralNameHits(PolicyLayout layout, Collection<String> irNames) {
        List<String> structural = new ArrayList<>();
        for (LayoutSpan s : layout.spans()) {
            if (s instanceof LayoutSpan.Structural st) {
                structural.add(CaseFold.foldKey(st.canonical()));
            }
        }
        List<String> hits = new ArrayList<>();
        for (String name : irNames) {
            String n = CaseFold.foldKey(name);
            if (structural.stream().anyMatch(c -> containsWholeWord(c, n))) {
                hits.add(name);
            }
        }
        return hits;
    }

    /**
     * L9 的 P：在全部结构 span 的起止偏移 p 处插入一个 U+0020，跳过文首文末、换行之后与行首缩进内的位置，
     * 按 p 从大到小插入。
     */
    public static String padStructural(PolicyLayout layout) {
        String c = layout.toCanonical();
        TreeSet<Integer> bounds = new TreeSet<>(Comparator.reverseOrder());
        int at = 0;
        for (LayoutSpan s : layout.spans()) {
            int len = s.canonicalText().length();
            if (s instanceof LayoutSpan.Structural) {
                bounds.add(at);
                bounds.add(at + len);
            }
            at += len;
        }
        StringBuilder out = new StringBuilder(c);
        for (int p : bounds) {
            if (eligible(c, p)) {
                out.insert(p, ' ');
            }
        }
        return out.toString();
    }

    private static boolean eligible(String c, int p) {
        if (p <= 0 || p >= c.length() || isNewline(c.charAt(p - 1))) {
            return false;
        }
        for (int q = p - 1; q >= 0 && !isNewline(c.charAt(q)); q--) {
            if (c.charAt(q) != ' ' && c.charAt(q) != '\t') {
                return true;
            }
        }
        return false;
    }

    private static boolean isNewline(char c) {
        return c == '\n' || c == '\r';
    }

    // ------------------------------------------------------------------
    // 拼写来源：locales SPI 词法表 + aliasSet + 词汇
    // ------------------------------------------------------------------

    /** L6(c)：包 locale 与 en-US 词法表的 TRUE / FALSE / NULL / NONE 拼写（keywords 与 aliases）。 */
    public static List<String> literalKeywords(String locale) {
        List<String> out = new ArrayList<>(lexiconSpellings(locale, LITERAL_KINDS));
        out.addAll(lexiconSpellings("en-US", LITERAL_KINDS));
        return out;
    }

    /** L7：包 locale 与 en-US 词法表中 11 个运算符 kind 的全部拼写，并入 aliasSet 中这些 kind 的别名。 */
    public static List<String> operatorSpellings(String locale, Map<String, List<String>> aliasSet) {
        List<String> out = new ArrayList<>(lexiconSpellings(locale, OPERATOR_KINDS));
        out.addAll(lexiconSpellings("en-US", OPERATOR_KINDS));
        if (aliasSet != null) {
            for (SemanticTokenKind k : OPERATOR_KINDS) {
                List<String> aliases = aliasSet.get(k.name());
                if (aliases != null) {
                    out.addAll(aliases);
                }
            }
        }
        return out;
    }

    /**
     * 某个 locale 词法表中给定 kind 的全部拼写（keywords 与 aliases），foldKey 后按码点排序去重——
     * 与共享向量 {@code keyword-spellings.json} 的形态相同。词法表不存在时抛 {@link IllegalArgumentException}。
     */
    public static List<String> lexiconSpellings(String locale, Collection<SemanticTokenKind> kinds) {
        Lexicon lexicon = LexiconRegistry.getInstance().get(locale)
            .orElseThrow(() -> new IllegalArgumentException("no lexicon for " + locale));
        List<String> raw = new ArrayList<>();
        for (SemanticTokenKind k : kinds) {
            String kw = lexicon.getKeywords().get(k);
            if (kw != null) {
                raw.add(kw);
            }
            raw.addAll(lexicon.getAliases().getOrDefault(k, List.of()));
        }
        TreeSet<String> sorted = new TreeSet<>(CaseFold::compareCodePoints);
        for (String s : raw) {
            String f = CaseFold.foldKey(s);
            if (!f.isEmpty()) {
                sorted.add(f);
            }
        }
        return List.copyOf(sorted);
    }

    /** 词汇（DomainVocabulary JSON）全部条目的 {@code localized}：任何数组成员里带字符串 localized 的对象。 */
    public static List<String> vocabularyLocalized(JsonNode vocabulary) {
        List<String> out = new ArrayList<>();
        if (vocabulary == null || !vocabulary.isObject()) {
            return out;
        }
        for (JsonNode v : vocabulary) {
            if (!v.isArray()) {
                continue;
            }
            for (JsonNode e : v) {
                if (e.isObject() && e.path("localized").isTextual()) {
                    out.add(e.path("localized").textValue());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 整词
    // ------------------------------------------------------------------

    /**
     * 「整词出现」：needle 在 haystack 中出现，且该出现的前一码点与后一码点（若有）都不属标识符字符类。
     * 两个参数都应已 foldKey；空 needle 永不出现。
     */
    public static boolean containsWholeWord(String haystack, String needle) {
        if (needle.isEmpty()) {
            return false;
        }
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            if (!isIdentCodePoint(codePointBefore(haystack, i)) && !isIdentCodePoint(codePointAt(haystack, i + needle.length()))) {
                return true;
            }
        }
        return false;
    }

    /** at 之前一个码点；不存在为 -1。 */
    private static int codePointBefore(String s, int at) {
        return at <= 0 ? -1 : s.codePointBefore(at);
    }

    private static int codePointAt(String s, int at) {
        return at >= s.length() ? -1 : s.codePointAt(at);
    }

    static boolean isIdentCodePoint(int cp) {
        return cp >= 0 && IDENT.matcher(new String(Character.toChars(cp))).matches();
    }

    private static List<String> foldAll(Collection<String> a) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : a) {
            String f = CaseFold.foldKey(s);
            if (!f.isEmpty()) {
                out.add(f);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> foldAll(Collection<String> a, Collection<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return foldAll(all);
    }
}
