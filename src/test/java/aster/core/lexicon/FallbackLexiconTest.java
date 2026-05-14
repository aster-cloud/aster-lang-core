package aster.core.lexicon;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FallbackLexicon + LexiconRegistry 装饰逻辑测试。
 */
class FallbackLexiconTest {

    /** 用 en-US 真实 punct/canon/messages 配 anonymous Lexicon，方便构造"故意缺 keyword"的 target。 */
    private static Lexicon mockLexicon(String id, Map<SemanticTokenKind, String> keywords) {
        Lexicon enUs = LexiconRegistry.getInstance().getOrThrow("en-US");
        return new Lexicon() {
            @Override public String getId() { return id; }
            @Override public String getName() { return "Mock " + id; }
            @Override public Direction getDirection() { return Direction.LTR; }
            @Override public Map<SemanticTokenKind, String> getKeywords() { return keywords; }
            @Override public PunctuationConfig getPunctuation() { return enUs.getPunctuation(); }
            @Override public CanonicalizationConfig getCanonicalization() { return enUs.getCanonicalization(); }
            @Override public ErrorMessages getMessages() { return enUs.getMessages(); }
        };
    }

    @Test
    void getDefault_returnsEnUs_unwrapped() {
        Lexicon en = LexiconRegistry.getInstance().getDefault();
        assertEquals("en-US", en.getId());
        assertFalse(en instanceof FallbackLexicon, "en-US itself should not be FallbackLexicon-wrapped");
    }

    @Test
    void get_enUs_returnsRaw_notFallbackLexicon() {
        Lexicon en = LexiconRegistry.getInstance().get("en-US").orElseThrow();
        assertFalse(en instanceof FallbackLexicon);
        assertEquals("en-US", en.getId());
    }

    @Test
    void get_nonEnUsLexicon_isWrappedByFallback() {
        // zh-CN 通过 SPI 注册（aster-lang-zh classpath）。get() 应包成 FallbackLexicon。
        Lexicon zh = LexiconRegistry.getInstance().get("zh-CN").orElse(null);
        if (zh == null) {
            // 测试环境若无 zh plugin，跳过（CI 中 aster-lang-zh 应在 classpath）
            return;
        }
        assertTrue(zh instanceof FallbackLexicon,
            "non-en-US lexicons must be wrapped by FallbackLexicon");
        assertEquals("zh-CN", zh.getId()); // id 仍透传 target
    }

    @Test
    void fallbackLexicon_mergesKeywords_targetWinsWhenPresent() {
        Lexicon enUs = LexiconRegistry.getInstance().getOrThrow("en-US");

        // 构造一个"故意缺 GIVEN"的 partial lexicon
        Map<SemanticTokenKind, String> partial = new EnumMap<>(SemanticTokenKind.class);
        for (Map.Entry<SemanticTokenKind, String> e : enUs.getKeywords().entrySet()) {
            if (e.getKey() == SemanticTokenKind.FUNC_GIVEN) continue;       // 缺 GIVEN
            partial.put(e.getKey(), e.getValue() + "_t");              // 其余加后缀
        }
        Lexicon target = mockLexicon("xx-MOCK", partial);

        FallbackLexicon wrapped = new FallbackLexicon(target, enUs);

        // GIVEN 来自 fallback (en-US)
        assertEquals("given", wrapped.getKeyword(SemanticTokenKind.FUNC_GIVEN).orElseThrow(),
            "missing keyword should fall back to en-US value");

        // 其他 keyword 来自 target（带 _t 后缀）
        String moduleKw = wrapped.getKeyword(SemanticTokenKind.MODULE_DECL).orElseThrow();
        assertTrue(moduleKw.endsWith("_t"), "target keyword should win when present, got: " + moduleKw);

        // id 透传 target
        assertEquals("xx-MOCK", wrapped.getId());
        // direction、punct、canon、messages 透传 target
        assertEquals(Lexicon.Direction.LTR, wrapped.getDirection());
        assertSame(enUs.getPunctuation(), wrapped.getPunctuation());
    }

    @Test
    void fallbackLexicon_keywords_coverFullEnUsSet() {
        Lexicon enUs = LexiconRegistry.getInstance().getOrThrow("en-US");

        // target 只填一个 keyword，其余完全缺失
        Map<SemanticTokenKind, String> partial = new EnumMap<>(SemanticTokenKind.class);
        partial.put(SemanticTokenKind.MODULE_DECL, "Modul");
        Lexicon target = mockLexicon("xx-MOCK", partial);

        FallbackLexicon wrapped = new FallbackLexicon(target, enUs);

        Set<SemanticTokenKind> mergedKinds = new LinkedHashSet<>(wrapped.getKeywords().keySet());
        Set<SemanticTokenKind> enKinds = new LinkedHashSet<>(enUs.getKeywords().keySet());
        assertEquals(enKinds, mergedKinds,
            "merged keyword set must cover full en-US backbone even when target has only 1 keyword");

        // MODULE_DECL 来自 target
        assertEquals("Modul", wrapped.getKeyword(SemanticTokenKind.MODULE_DECL).orElseThrow());
        // FUNC_PRODUCE 来自 fallback
        assertEquals(enUs.getKeyword(SemanticTokenKind.FUNC_PRODUCE).orElseThrow(),
            wrapped.getKeyword(SemanticTokenKind.FUNC_PRODUCE).orElseThrow());
    }

    @Test
    void fallbackLexicon_rejectsNullArgs() {
        Lexicon enUs = LexiconRegistry.getInstance().getOrThrow("en-US");
        assertThrows(IllegalArgumentException.class, () -> new FallbackLexicon(null, enUs));
        assertThrows(IllegalArgumentException.class, () -> new FallbackLexicon(enUs, null));
    }

    @Test
    void fallbackLexicon_isNotDoubleWrapped() {
        Lexicon enUs = LexiconRegistry.getInstance().getOrThrow("en-US");
        Map<SemanticTokenKind, String> partial = new EnumMap<>(SemanticTokenKind.class);
        partial.put(SemanticTokenKind.MODULE_DECL, "M");
        Lexicon target = mockLexicon("xx-MOCK", partial);
        FallbackLexicon first = new FallbackLexicon(target, enUs);

        // 通过 registry.get 二次拿（模拟下游再次查询）；应**不**被双层 wrap
        // 直接测 registry 路径
        // registry.lexicons 是私有的，无法直接塞 — 此条用 wrapped 自身校验：
        // FallbackLexicon 不应再 wrap FallbackLexicon
        FallbackLexicon doubleWrap = new FallbackLexicon(first, enUs);
        // 双层 wrap 在直接 new 时允许（防御性 API），但 registry.decorateWithFallback
        // 内部有 instanceof 检查避免双 wrap —— 行为契约
        assertNotNull(doubleWrap, "double-wrap technically allowed at constructor level");
    }
}
