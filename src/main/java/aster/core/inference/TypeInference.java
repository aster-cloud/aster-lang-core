package aster.core.inference;

import aster.core.ast.Constraint;
import aster.core.ast.Span;
import aster.core.ast.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 未标注形参 / 字段的类型推断（ADR 0056 §3.2）。
 *
 * <p>规则表是 aster-lang-locales {@code type-inference/naming-rules.json} 的内置副本
 * （{@code /type-inference/naming-rules.json}，由 NamingRulesSiblingParityTest 守护），与词法表无关。
 * 算法与 aster-lang-ts {@code parser/type-inference.ts} 逐步相同，两侧用 locales 的共用测试向量对照：
 * <ol>
 *   <li>分词：名字只含 ASCII 字母、数字与 {@code _} 时按 {@code _} 切段，每段按 {@link #WORD} 切词并做 ASCII 小写；
 *       否则词列表为空；</li>
 *   <li>逐条判定规则是否命中（首词 / 末词 / 任一词 / 整名前缀后缀）；</li>
 *   <li>命中规则中 priority 最大者胜，同优先级取表中靠前者，都不命中取缺省类型；</li>
 *   <li>按约束修正（与约束书写顺序无关）。</li>
 * </ol>
 */
public final class TypeInference {

    private TypeInference() {
        // 工具类，不可实例化
    }

    /**
     * 分词式：不带任何标志、只用 ASCII 字符类与先行断言，在 Java 与 JS 中语义相同。
     */
    private static final Pattern WORD = Pattern.compile("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+");

    private enum Match { FIRST_WORD, LAST_WORD, LAST_WORD_MULTI, ANY_WORD, TEXT_PREFIX, TEXT_SUFFIX }

    /** 一条规则：{@code items} 是 words（*Word*）或 strings（text*）。 */
    private record NamingRule(String type, int priority, Match match, Set<String> items) {}

    private record NamingRules(String defaultType, List<NamingRule> rules) {}

    private static final NamingRules RULES = load();

    private static NamingRules load() {
        try (InputStream in = TypeInference.class.getResourceAsStream("/type-inference/naming-rules.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少内置推断命名规则 /type-inference/naming-rules.json");
            }
            JsonNode root = new ObjectMapper().readTree(in);
            List<NamingRule> rules = new ArrayList<>();
            for (JsonNode rule : root.path("rules")) {
                Match match = switch (rule.path("match").asText()) {
                    case "firstWord" -> Match.FIRST_WORD;
                    case "lastWord" -> Match.LAST_WORD;
                    case "lastWordMulti" -> Match.LAST_WORD_MULTI;
                    case "anyWord" -> Match.ANY_WORD;
                    case "textPrefix" -> Match.TEXT_PREFIX;
                    case "textSuffix" -> Match.TEXT_SUFFIX;
                    default -> throw new IllegalStateException("未知的 match：" + rule.path("match").asText());
                };
                JsonNode items = rule.has("words") ? rule.path("words") : rule.path("strings");
                List<String> values = new ArrayList<>();
                items.forEach(item -> values.add(item.asText()));
                rules.add(new NamingRule(rule.path("type").asText(), rule.path("priority").asInt(), match, Set.copyOf(values)));
            }
            return new NamingRules(root.path("default").asText(), List.copyOf(rules));
        } catch (IOException e) {
            throw new UncheckedIOException("读取推断命名规则失败", e);
        }
    }

    /**
     * 按名字推断类型（无约束）。
     *
     * @param fieldName 字段 / 形参名
     * @return 推断的 AST 类型节点（无 span）
     */
    public static Type inferFieldType(String fieldName) {
        return inferFieldType(fieldName, List.of(), null);
    }

    /**
     * 按名字推断类型（无约束），类型节点带源码位置。
     */
    public static Type inferFieldType(String fieldName, Span span) {
        return inferFieldType(fieldName, List.of(), span);
    }

    /**
     * 按名字推断类型并按约束修正。
     *
     * @param fieldName   字段 / 形参名
     * @param constraints 源码写下的约束（可为空）
     * @param span        类型节点的源码位置（推断类型锚定在名字 token，ADR 0056 §3.3）
     */
    public static Type inferFieldType(String fieldName, List<Constraint> constraints, Span span) {
        return new Type.TypeName(inferTypeName(fieldName, constraints), List.of(), span);
    }

    /** 推断类型名：名字推断结果按约束修正。 */
    public static String inferTypeName(String fieldName, List<Constraint> constraints) {
        return refine(inferTypeNameFromFieldName(fieldName), constraintType(constraints));
    }

    /**
     * 只按名字推断类型名（第 1–3 步）。
     *
     * @param fieldName 字段 / 形参名
     * @return 类型名称（Text / Int / Float / Bool / DateTime）
     */
    public static String inferTypeNameFromFieldName(String fieldName) {
        String name = fieldName == null ? "" : fieldName;
        List<String> words = tokenize(name);
        NamingRule best = null;
        for (NamingRule rule : RULES.rules()) {
            if (matches(rule, words, name) && (best == null || rule.priority() > best.priority())) {
                best = rule;
            }
        }
        return best == null ? RULES.defaultType() : best.type();
    }

    /** 第 1 步：分词。名字含 ASCII 字母、数字、{@code _} 以外的字符时返回空列表。 */
    static List<String> tokenize(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!ascii) {
                return List.of();
            }
        }
        List<String> words = new ArrayList<>();
        for (String segment : name.split("_", -1)) {
            Matcher m = WORD.matcher(segment);
            while (m.find()) {
                words.add(asciiLower(m.group()));
            }
        }
        return words;
    }

    /** 第 2 步：单条规则是否命中。词列表为空时 *Word* 规则一律不命中。 */
    private static boolean matches(NamingRule rule, List<String> words, String name) {
        return switch (rule.match()) {
            case FIRST_WORD -> !words.isEmpty() && rule.items().contains(words.getFirst());
            case LAST_WORD -> !words.isEmpty() && rule.items().contains(words.getLast());
            case LAST_WORD_MULTI -> words.size() >= 2 && rule.items().contains(words.getLast());
            case ANY_WORD -> words.stream().anyMatch(rule.items()::contains);
            case TEXT_PREFIX -> rule.items().stream().anyMatch(asciiLower(name)::startsWith);
            case TEXT_SUFFIX -> rule.items().stream().anyMatch(asciiLower(name)::endsWith);
        };
    }

    /** ASCII 小写：只改 A–Z，中文、变音符等字符不变（不受默认 Locale 影响）。 */
    private static String asciiLower(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    /**
     * 第 5 步的约束类型：存在 Range 时，任一端点的规范十进制值带小数部分得 Float，否则 Int；
     * 不存在 Range 而存在 Pattern 时得 Text；其余为 null。
     */
    static String constraintType(List<Constraint> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return null;
        }
        boolean anyRange = false;
        boolean fractional = false;
        boolean anyPattern = false;
        for (Constraint c : constraints) {
            if (c instanceof Constraint.Range r) {
                anyRange = true;
                fractional |= hasFraction(r.min()) || hasFraction(r.max());
            } else if (c instanceof Constraint.Pattern) {
                anyPattern = true;
            }
        }
        if (anyRange) {
            return fractional ? "Float" : "Int";
        }
        return anyPattern ? "Text" : null;
    }

    /** 端点的规范十进制串（ADR 0055 §5，去尾随零）是否含小数点；字面量原文 {@code 1.0} 不算。 */
    private static boolean hasFraction(String literal) {
        return literal != null && new BigDecimal(literal).stripTrailingZeros().scale() > 0;
    }

    /** 第 5 步：名字推断结果 n 与约束类型 c 合并。 */
    static String refine(String n, String c) {
        if (c == null || c.equals(n)) {
            return n;
        }
        if ("Text".equals(n) && ("Int".equals(c) || "Float".equals(c))) {
            return c;
        }
        if ("Int".equals(n) && "Float".equals(c)) {
            return "Float";
        }
        return n;
    }

    /**
     * 检查类型名称是否为基本类型
     *
     * @param typeName 类型名称
     * @return 是否为基本类型
     */
    public static boolean isPrimitiveType(String typeName) {
        return typeName != null && (
            "Int".equals(typeName) ||
            "Float".equals(typeName) ||
            "Bool".equals(typeName) ||
            "Text".equals(typeName) ||
            "DateTime".equals(typeName) ||
            "Long".equals(typeName) ||
            "Double".equals(typeName) ||
            "Decimal".equals(typeName)
        );
    }
}
