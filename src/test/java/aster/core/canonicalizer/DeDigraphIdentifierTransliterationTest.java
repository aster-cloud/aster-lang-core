package aster.core.canonicalizer;

import aster.core.lexicon.LexiconRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：de 二合字母 customRules（oe→ö/ue→ü/ae→ä）只转写关键词，不误伤用户标识符。
 * <p>
 * 历史 bug：全局转写把含二合字母的标识符（fruehereSchaeden）错写成 frühereSchäden，
 * 导致标识符与运行时 context 键不匹配。修复后转写按词限定在关键词上。
 */
class DeDigraphIdentifierTransliterationTest {

    private Canonicalizer de() {
        return new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("de-DE"), null);
    }

    @Test
    void identifierWithDigraphsPreserved() {
        // fruehereSchaeden 是用户标识符，不该被转写成 frühereSchäden
        String out = de().canonicalize("sei fruehereSchaeden gleich 1.");
        assertTrue(out.contains("fruehereSchaeden"),
            "标识符应保留 ASCII 形态，实际: " + out);
        assertFalse(out.contains("frühereSchäden"),
            "标识符不该被转写，实际: " + out);
    }

    @Test
    void keywordDigraphsStillTransliterated() {
        // hoechstens 是关键词(at most)，经转写+翻译后应成为运算符 <=
        String out = de().canonicalize("x hoechstens 5");
        assertTrue(out.contains("<="),
            "关键词应转写并翻译为 <=，实际: " + out);
    }

    @Test
    void mixedIdentifierAndKeyword() {
        String out = de().canonicalize("wenn fruehereSchaeden hoechstens 5");
        assertTrue(out.contains("fruehereSchaeden"), "标识符保留, 实际: " + out);
        assertTrue(out.contains("<="), "关键词转写并翻译, 实际: " + out);
    }

    @Test
    void multiRuleChainedKeywordStillWorks() {
        // groesser 需 oe→ö(成 grösser) 再 \bgrösser\b→größer 两条规则串联才成关键词。
        // 一次性应用全部规则后判定，确保不在中间态被误判为标识符而中断。
        // groesser als = GREATER_THAN 关键词 → 翻译为 >
        String out = de().canonicalize("wenn x groesser als 5");
        assertTrue(out.contains(">"),
            "groesser als 应转写并翻译为 >，实际: " + out);
    }

    @Test
    void stringLiteralDigraphsPreserved() {
        // 字符串字面量内的二合字母不被转写（内容原样透传）
        String out = de().canonicalize("gib zurueck \"Bonitaet zu niedrig\".");
        assertTrue(out.contains("\"Bonitaet zu niedrig\""),
            "字符串内容应保留, 实际: " + out);
    }

    @Test
    void identifierStartingWithKeywordWordNotPartiallyTransliterated() {
        // fuer 是关键词词(fuer jedes=for each)，但 fuer_foo/fuer2 是完整标识符，
        // 整 token 才是判定单位，不该被拆出 fuer 误转成 für_foo/für2
        assertTrue(de().canonicalize("sei fuer_foo gleich 1.").contains("fuer_foo"),
            "fuer_foo 应整体保留");
        assertTrue(de().canonicalize("sei fuer2 gleich 1.").contains("fuer2"),
            "fuer2 应整体保留");
        // 作为关键词的 fuer jedes（=for each）仍应被识别并翻译为英文 for each
        String forEach = de().canonicalize("fuer jedes x");
        assertTrue(forEach.contains("for each"),
            "fuer jedes 关键词应翻译为 for each, 实际: " + forEach);
    }
}
