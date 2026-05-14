package aster.core.lexicon;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lexicon 装饰器：把一个 target lexicon（如 zh-CN）与一个 fallback lexicon（始终为 en-US）合并。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code getKeywords()} 返回**预合并**的 map（en-US 全集 + target 覆盖）</li>
 *   <li>{@code getPunctuation()}、{@code getCanonicalization()}、{@code getMessages()}
 *       完全透传 target —— 这些是语言强相关行为，混合 en 的标点 / 规范化规则会破坏
 *       lexer 一致性，因此**不做 fallback**。如果 target 这几个字段缺失，请先修
 *       lexicon JSON，而非依赖此装饰器</li>
 *   <li>{@code getId/getName/getDirection} 完全透传 target —— FallbackLexicon 对外仍是
 *       target 的身份（zh-CN 用户切到 zh-CN 不应被告知"你其实在用 en-US 的 fallback"）</li>
 * </ul>
 *
 * <p>性能：构造时一次性合并 keywords map，运行时 {@code getKeywords()} 是 O(1) 引用返回。
 *
 * <p>en-US 自身**不**应被 wrap（target == fallback 时退化为身份函数；
 * LexiconRegistry 通过 id 判断跳过此装饰）。
 */
public final class FallbackLexicon implements Lexicon {

    private final Lexicon target;
    private final Lexicon fallback;
    /** 预合并的 keywords map，运行时直接返回引用（不可变）。 */
    private final Map<SemanticTokenKind, String> mergedKeywords;

    public FallbackLexicon(Lexicon target, Lexicon fallback) {
        if (target == null) throw new IllegalArgumentException("target lexicon required");
        if (fallback == null) throw new IllegalArgumentException("fallback lexicon required");
        this.target = target;
        this.fallback = fallback;
        this.mergedKeywords = mergeKeywords(target, fallback);
    }

    /**
     * 合并策略：fallback 全集做底，target 覆盖（target 缺的 key 自然保留 fallback 值）。
     * 用 LinkedHashMap 保持插入顺序便于调试。
     */
    private static Map<SemanticTokenKind, String> mergeKeywords(Lexicon target, Lexicon fallback) {
        Map<SemanticTokenKind, String> merged = new LinkedHashMap<>(fallback.getKeywords());
        for (Map.Entry<SemanticTokenKind, String> e : target.getKeywords().entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isEmpty()) {
                merged.put(e.getKey(), v);
            }
        }
        // 让返回值不可变，避免下游意外修改
        return java.util.Collections.unmodifiableMap(merged);
    }

    /** 返回被装饰的 target lexicon。供调试 / 测试使用。 */
    public Lexicon getTarget() {
        return target;
    }

    /** 返回 fallback lexicon。 */
    public Lexicon getFallback() {
        return fallback;
    }

    @Override
    public String getId() {
        return target.getId();
    }

    @Override
    public String getName() {
        return target.getName();
    }

    @Override
    public Direction getDirection() {
        return target.getDirection();
    }

    @Override
    public Map<SemanticTokenKind, String> getKeywords() {
        return mergedKeywords;
    }

    @Override
    public PunctuationConfig getPunctuation() {
        return target.getPunctuation();
    }

    @Override
    public CanonicalizationConfig getCanonicalization() {
        return target.getCanonicalization();
    }

    @Override
    public ErrorMessages getMessages() {
        return target.getMessages();
    }
}
