package aster.core.lexicon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LexiconRegistry 单元测试
 * <p>
 * 验证词法表注册中心的功能。
 */
class LexiconRegistryTest {

    private LexiconRegistry registry;

    @BeforeEach
    void setUp() {
        registry = LexiconRegistry.getInstance();
    }

    // ============================================================
    // 内置词法表测试
    // ============================================================

    @Test
    void testBuiltinEnUsLexiconRegistered() {
        assertTrue(registry.has("en-US"), "英文词法表应已注册");
        assertNotNull(registry.get("en-US").orElse(null));
        assertSame(EnUsLexicon.INSTANCE, registry.getOrThrow("en-US"));
    }

    @Test
    void testBuiltinZhCnLexiconRegistered() {
        assertTrue(registry.has("zh-CN"), "中文词法表应已注册");
        assertNotNull(registry.get("zh-CN").orElse(null));
        assertSame(ZhCnLexicon.INSTANCE, registry.getOrThrow("zh-CN"));
    }

    @Test
    void testDefaultLexicon() {
        Lexicon defaultLexicon = registry.getDefault();
        assertNotNull(defaultLexicon);
        assertEquals("en-US", defaultLexicon.getId());
    }

    // ============================================================
    // 列表和查询测试
    // ============================================================

    @Test
    void testListRegisteredLexicons() {
        var list = registry.list();
        assertTrue(list.contains("en-US"), "列表应包含 en-US");
        assertTrue(list.contains("zh-CN"), "列表应包含 zh-CN");
    }

    @Test
    void testGetNonExistentLexicon() {
        assertTrue(registry.get("xx-XX").isEmpty());
        assertFalse(registry.has("xx-XX"));
    }

    @Test
    void testGetOrThrowNonExistent() {
        assertThrows(IllegalArgumentException.class, () -> {
            registry.getOrThrow("xx-XX");
        });
    }

    // ============================================================
    // 词法表内容验证
    // ============================================================

    @Test
    void testEnUsLexiconContent() {
        Lexicon enUs = registry.getOrThrow("en-US");

        assertEquals("en-US", enUs.getId());
        assertEquals("English (US)", enUs.getName());
        assertEquals(Lexicon.Direction.LTR, enUs.getDirection());

        // 验证关键词映射（注意：大小写必须与 ANTLR 词法器匹配）
        var keywords = enUs.getKeywords();
        assertEquals("If", keywords.get(SemanticTokenKind.IF));
        assertEquals("Return", keywords.get(SemanticTokenKind.RETURN));
        assertEquals("true", keywords.get(SemanticTokenKind.TRUE));
    }

    @Test
    void testZhCnLexiconContent() {
        Lexicon zhCn = registry.getOrThrow("zh-CN");

        assertEquals("zh-CN", zhCn.getId());
        assertEquals("简体中文", zhCn.getName());
        assertEquals(Lexicon.Direction.LTR, zhCn.getDirection());

        // 验证关键词映射（与 TypeScript 前端保持一致）
        var keywords = zhCn.getKeywords();
        assertEquals("如果", keywords.get(SemanticTokenKind.IF));
        assertEquals("若", keywords.get(SemanticTokenKind.MATCH));
        assertEquals("返回", keywords.get(SemanticTokenKind.RETURN));
        assertEquals("真", keywords.get(SemanticTokenKind.TRUE));
        assertEquals("模块", keywords.get(SemanticTokenKind.MODULE_DECL));
    }

    // ============================================================
    // 标点符号配置测试
    // ============================================================

    @Test
    void testEnUsPunctuationConfig() {
        Lexicon enUs = registry.getOrThrow("en-US");
        var punct = enUs.getPunctuation();

        assertNotNull(punct.statementEnd());
        assertNotNull(punct.listSeparator());
        assertNotNull(punct.blockStart());
    }

    @Test
    void testZhCnPunctuationConfig() {
        Lexicon zhCn = registry.getOrThrow("zh-CN");
        var punct = zhCn.getPunctuation();

        assertNotNull(punct.statementEnd());
        assertNotNull(punct.listSeparator());
        assertNotNull(punct.blockStart());
    }
}
