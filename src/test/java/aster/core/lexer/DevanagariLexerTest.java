package aster.core.lexer;

import aster.core.lexicon.DynamicLexicon;
import aster.core.lexicon.Lexicon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1（ADR 0017）—— 天城文 Devanagari 词法支持测试。
 *
 * Devanagari 是 abugida 脚本：辅音 + 元音符号（matra）+ virama 组合记号。POC 发现
 * 两个词法坑，本测试锁定修复：
 *   1. 组合记号（matra ◌ॉ ◌ू、virama ◌्）是 NON_SPACING_MARK / COMBINING_SPACING_MARK，
 *      Character.isLetter 对它们返回 false → 词在记号处断开（मॉड्यूल → 碎片）。
 *      修复：isIdentifierChar 用 Character.isUnicodeIdentifierPart 接受这些记号。
 *   2. danda「।」是句末符（lexicon punctuation.statementEnd），但旧 lexer 硬编码只认
 *      ASCII「.」/ 中文「。」。修复：Lexer 标点分支同时认 lexicon 配置的 statementEnd。
 */
class DevanagariLexerTest {

    /** 最小 Hindi lexicon（danda 句末符 + 少量关键词），仅供本测试。 */
    private static Lexicon hindiLexicon() {
        String json = """
            {
              "meta": {
                "id": "hi-IN",
                "name": "हिन्दी",
                "direction": "LTR"
              },
              "keywords": {
                "MODULE_DECL": "मॉड्यूल",
                "FUNC_TO": "नियम",
                "IF": "यदि",
                "RETURN": "लौटाएं"
              },
              "punctuation": {
                "statementEnd": "।",
                "listSeparator": ",",
                "enumSeparator": ",",
                "blockStart": ":",
                "stringQuoteOpen": "\\"",
                "stringQuoteClose": "\\""
              },
              "canonicalization": {
                "fullWidthToHalf": false,
                "whitespaceMode": "ENGLISH",
                "removeArticles": false
              },
              "messages": {}
            }
            """;
        return DynamicLexicon.fromJsonString(json);
    }

    @Test
    void devanagariIdentifierStaysOneToken() {
        // 修复 1：含元音符号 + virama 的天城文词必须是单个 IDENT，不在记号处碎裂。
        // मॉड्यूल = म + ◌ॉ + ड + ◌् + य + ◌ू + ल（7 UTF-16 单元，多个组合记号）
        List<Token> tokens = Lexer.lex("मॉड्यूल pricing", hindiLexicon());
        // 期望：IDENT(मॉड्यूल) ... 至少证明天城文词没被切碎（>1 个 Devanagari 片段）。
        long devanagariIdents = tokens.stream()
                .filter(t -> t.kind() == TokenKind.IDENT && String.valueOf(t.value()).codePoints().anyMatch(c -> c >= 0x0900 && c <= 0x097F))
                .count();
        assertEquals(1, devanagariIdents,
                "天城文词应是单个 IDENT，不被元音符号/virama 切碎。实际 tokens=" + tokens);
        // 且该 token 的文本应完整等于原词。
        boolean intact = tokens.stream().anyMatch(t -> "मॉड्यूल".equals(String.valueOf(t.value())));
        assertTrue(intact, "मॉड्यूल 应作为完整标识符出现。实际 tokens=" + tokens);
    }

    @Test
    void dandaTokenizesAsStatementEnd() {
        // 修复 2：danda「।」作为 lexicon 配置的句末符，应 tokenize 成 DOT。
        List<Token> tokens = Lexer.lex("pricing।", hindiLexicon());
        // 期望：IDENT(pricing) DOT(।) EOF
        assertEquals(TokenKind.IDENT, tokens.get(0).kind(), "tokens=" + tokens);
        assertEquals(TokenKind.DOT, tokens.get(1).kind(),
                "danda「।」应识别为句末 DOT。实际 tokens=" + tokens);
        assertEquals("।", tokens.get(1).value());
    }

    @Test
    void dandaIsNotSwallowedIntoIdentifier() {
        // 回归：danda 必须与前面的词分开（不被当成标识符字符吞掉）。
        List<Token> tokens = Lexer.lex("मॉड्यूल।", hindiLexicon());
        boolean wordIntact = tokens.stream().anyMatch(t -> "मॉड्यूल".equals(String.valueOf(t.value())));
        boolean dandaSeparate = tokens.stream().anyMatch(t -> t.kind() == TokenKind.DOT && "।".equals(String.valueOf(t.value())));
        assertTrue(wordIntact, "天城文词应完整。tokens=" + tokens);
        assertTrue(dandaSeparate, "danda 应与词分开成 DOT。tokens=" + tokens);
    }

    @Test
    void asciiAndChinesePunctuationStillWork() {
        // 回归：不破坏既有 en（.）/ zh（。）句末符行为。
        List<Token> en = Lexer.lex("foo.", hindiLexicon());
        assertEquals(TokenKind.DOT, en.get(1).kind(), "ASCII 句号仍应是 DOT。tokens=" + en);
        List<Token> zh = Lexer.lex("foo。", hindiLexicon());
        assertEquals(TokenKind.DOT, zh.get(1).kind(), "中文句号仍应是 DOT。tokens=" + zh);
    }
}
