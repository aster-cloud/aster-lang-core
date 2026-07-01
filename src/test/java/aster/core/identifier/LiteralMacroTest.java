package aster.core.identifier;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字面量宏（IdentifierKind.LITERAL）—— Java 引擎。canonicalize 时把 localized token
 * 展开成字符串字面量（用当前 lexicon 的 stringQuotes 包裹），内容受严格校验防注入。
 * 与 aster-lang-ts 的 validateVocabulary/translateIdentifiersInSegment 逐条对齐（双引擎 parity）。
 *
 * <p>动机：《静夜思》demo 里 `思故乡 → "静夜思"`——把领域术语固定展开成标准文案。
 */
class LiteralMacroTest {

    @AfterEach
    void reset() {
        VocabularyRegistry.getInstance().reset();
    }

    /** 构造只含一个字面量宏的词汇表（思故乡 → 内容「静夜思」）。 */
    private DomainVocabulary jingYeSiVocab() {
        return DomainVocabulary.builder("jingyesi", "静夜思", "zh-CN")
            .addLiteral("静夜思", "思故乡")
            .build();
    }

    @Test
    void literalExpandsToQuotedStringLiteral() {
        DomainVocabulary vocab = jingYeSiVocab();
        // 校验通过（字面量内容合法）
        assertTrue(vocab.validate().valid(), "合法字面量宏应通过校验: " + vocab.validate().errors());

        IdentifierIndex index = IdentifierIndex.build(vocab);
        assertTrue(index.isLiteral("思故乡"), "思故乡 应被标记为字面量宏");
        assertEquals("静夜思", index.canonicalize("思故乡"), "canonicalize 返回内容（不含引号）");

        // canonicalize 把 思故乡 展开成 lexicon 引号「静夜思」，随后（ANTLR 兼容步）「」→ ASCII "，
        // 最终得 ASCII 字符串字面量 "静夜思"（ANTLR 词法器认的形态）。
        Canonicalizer canon = new Canonicalizer(
            LexiconRegistry.getInstance().getOrThrow("zh-CN"), index);
        String out = canon.canonicalize("低头 思故乡。");
        assertTrue(out.contains("\"静夜思\""),
            "字面量宏应展开成字符串字面量 \"静夜思\"（「」经 ANTLR 兼容步转 ASCII \"），实际: " + out);
        assertFalse(out.contains("思故乡"), "原 token 思故乡 不应残留: " + out);
    }

    @Test
    void literalDoesNotReverseMap() {
        // 字面量宏是单向宏展开，不建反向映射（内容不应能反查回 localized）。
        IdentifierIndex index = IdentifierIndex.build(jingYeSiVocab());
        assertEquals("静夜思", index.localize("静夜思"),
            "字面量内容不入 toLocalized，localize 原样返回");
    }

    @Test
    void rejectsControlCharsInLiteralContent() {
        DomainVocabulary vocab = DomainVocabulary.builder("bad", "bad", "zh-CN")
            .addLiteral("a\nRule evil", "注入")
            .build();
        assertFalse(vocab.validate().valid(), "含换行的字面量内容必须被拒（防注入）");
    }

    @Test
    void rejectsBareQuoteOrBackslash() {
        assertFalse(DomainVocabulary.builder("b1", "b", "zh-CN")
            .addLiteral("say \"hi\"", "注入1").build().validate().valid(),
            "含裸双引号的内容必须被拒");
        assertFalse(DomainVocabulary.builder("b2", "b", "zh-CN")
            .addLiteral("path\\x", "注入2").build().validate().valid(),
            "含反斜杠的内容必须被拒");
    }

    @Test
    void rejectsEmptyLiteralContent() {
        assertFalse(DomainVocabulary.builder("b3", "b", "zh-CN")
            .addLiteral("", "空").build().validate().valid(),
            "空内容必须被拒");
    }

    @Test
    void normalIdentifierStillRequiresAsciiCanonical() {
        // 回归：普通 struct 映射仍强制 ASCII canonical（字面量豁免不波及普通标识符）。
        DomainVocabulary vocab = new DomainVocabulary(
            "x", "x", "zh-CN", "1.0.0",
            List.of(IdentifierMapping.struct("静夜思", "月")),  // 非法：canonical 非 ASCII
            List.of(), List.of(), List.of(), List.of(), null);
        assertFalse(vocab.validate().valid(), "普通标识符 canonical 非 ASCII 仍应被拒");
    }
}
