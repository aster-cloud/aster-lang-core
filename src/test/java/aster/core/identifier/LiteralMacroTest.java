package aster.core.identifier;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.DynamicLexicon;
import aster.core.lexicon.Lexicon;
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
    void rejectsAnyQuoteDelimiterOrBackslash() {
        // Codex 复审 P0：zh-CN 引号是「」，内容含它会提前闭合字符串逃逸注入。
        for (String bad : new String[]{"say \"hi\"", "path\\x", "静夜思」. Return evil", "「注入", "a『b", "»x«"}) {
            assertFalse(DomainVocabulary.builder("b", "b", "zh-CN")
                .addLiteral(bad, "注入").build().validate().valid(),
                "含引号定界符/反斜杠必须被拒: " + bad);
        }
    }

    @Test
    void literalTriggerCollidingWithIdentifierRejected() {
        // 「月」既是字面量宏触发词又是 struct localized → 展开歧义，必须被拒。
        DomainVocabulary v = new DomainVocabulary(
            "x", "x", "zh-CN", "1.0.0",
            List.of(IdentifierMapping.struct("moon", "月")),
            List.of(), List.of(), List.of(),
            List.of(IdentifierMapping.literal("静夜思", "月")),
            null);
        assertFalse(v.validate().valid(), "字面量宏触发词与普通标识符同名必须被拒");
    }

    @Test
    void normalIdentifiersSameNameAcrossKindsStillValid() {
        // 回归：普通 struct 与 field 同名（靠上下文消歧）不因新校验被误报 error。
        DomainVocabulary v = new DomainVocabulary(
            "x", "x", "zh-CN", "1.0.0",
            List.of(IdentifierMapping.struct("Limit", "额度")),
            List.of(IdentifierMapping.field("limit", "额度", "Loan")),
            List.of(), List.of(), List.of(), null);
        assertTrue(v.validate().valid(), "普通标识符跨 kind 同名不应报 error: " + v.validate().errors());
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

    /** 最小 Hindi lexicon（danda 句末符 + ASCII 引号 + 关键词），仅供本测试。 */
    private static Lexicon hindiLexicon() {
        String json = """
            {
              "meta": { "id": "hi-IN", "name": "हिन्दी", "direction": "LTR" },
              "keywords": {
                "MODULE_DECL": "मॉड्यूल", "FUNC_TO": "नियम",
                "FUNC_PRODUCE": "उत्पन्न", "RETURN": "लौटाएं"
              },
              "punctuation": {
                "statementEnd": "।", "listSeparator": ",", "enumSeparator": ",",
                "blockStart": ":", "stringQuoteOpen": "\\"", "stringQuoteClose": "\\""
              },
              "canonicalization": {
                "fullWidthToHalf": false, "whitespaceMode": "ENGLISH", "removeArticles": false
              },
              "messages": {}
            }
            """;
        return DynamicLexicon.fromJsonString(json);
    }

    /**
     * 回归：canonicalizer 的 isIdentifierPart 原用 Character.isLetterOrDigit，对天城文元音
     * 符号（matra，如 जागे 里的 ◌ा/◌े，属 NON_SPACING_MARK/COMBINING_SPACING_MARK）返回
     * false，把 जागे 切成 ज+ग 丢失元音 → 天城文字面量宏触发词永不匹配。修为纳入 Mark 后
     * जागे 作为整词匹配并展开。与 TS 的 {@code [\p{L}_][\p{L}\p{M}\p{Nd}_]*} 对齐。
     * 动机：Gitanjali #35 印地语 demo。注意 lexer 早已修（见 DevanagariLexerTest），
     * canonicalizer 的 translateIdentifiers 是独立路径，本次才补齐。
     */
    @Test
    void hindiLiteralMacroWithMatraExpands() {
        DomainVocabulary vocab = DomainVocabulary.builder("gitanjali", "Gitanjali", "hi-IN")
            .addLiteral("Into that heaven of freedom, let my country awake", "जागे")
            .build();
        assertTrue(vocab.validate().valid(), "合法字面量宏应通过校验: " + vocab.validate().errors());

        IdentifierIndex index = IdentifierIndex.build(vocab);
        assertTrue(index.isLiteral("जागे"), "जागे（含元音符号 matra）应作为整词标记为字面量宏");
        assertEquals("Into that heaven of freedom, let my country awake",
            index.canonicalize("जागे"), "canonicalize 返回内容");

        // Hindi stringQuotes 是 ASCII "，无「」→" 转换。जागे 应整词展开成 ASCII 字符串字面量。
        Canonicalizer canon = new Canonicalizer(hindiLexicon(), index);
        String out = canon.canonicalize("लौटाएं जागे।");
        assertTrue(out.contains("\"Into that heaven of freedom, let my country awake\""),
            "天城文触发词应展开成 ASCII 字符串字面量，实际: " + out);
        assertFalse(out.contains("जागे"),
            "原天城文 token 不应残留（含元音符号也须整词匹配）: " + out);
    }
}
