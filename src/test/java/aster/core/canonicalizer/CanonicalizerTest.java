package aster.core.canonicalizer;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.VocabularyScopes;
import aster.core.identifier.VocabularyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonicalizer 单元测试（语言无关部分）
 * <p>
 * 仅保留语言无关的通用规范化测试。
 * 语言特定测试已迁移至各语言包：
 * <ul>
 *   <li>英语: aster-lang-en EnUsCanonicalizerTest</li>
 *   <li>中文: aster-lang-zh ZhCnCanonicalizerTest</li>
 *   <li>德语: aster-lang-de DeDeCanonicalizerTest</li>
 * </ul>
 */
class CanonicalizerTest {

    private Canonicalizer canonicalizer;

    @BeforeEach
    void setUp() {
        canonicalizer = new Canonicalizer();
    }

    // ============================================================
    // 换行符规范化测试
    // ============================================================

    @Test
    void testNormalizeNewlines_CRLF() {
        String input = "line1\r\nline2\r\nline3";
        String expected = "line1\nline2\nline3";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testNormalizeNewlines_CR() {
        String input = "line1\rline2\rline3";
        String expected = "line1\nline2\nline3";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testNormalizeNewlines_Mixed() {
        String input = "line1\r\nline2\nline3\rline4";
        String expected = "line1\nline2\nline3\nline4";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 制表符转换测试
    // ============================================================

    @Test
    void testTabsToSpaces() {
        String input = "\tIndented with tab";
        String expected = "  Indented with tab";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testMultipleTabs() {
        String input = "\t\tDouble indented";
        String expected = "    Double indented";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 行注释移除测试
    // ============================================================

    @Test
    void testRemoveLineComments_DoubleSlash() {
        String input = "code line\n// comment\nmore code";
        String expected = "code line\n\nmore code";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testRemoveLineComments_Hash() {
        String input = "code line\n# comment\nmore code";
        String expected = "code line\n\nmore code";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testRemoveLineComments_Indented() {
        String input = "code\n  // indented comment\nmore";
        String expected = "code\n\nmore";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 智能引号规范化测试
    // ============================================================

    @Test
    void testSmartQuotesToStraight_DoubleQuotes() {
        String input = "Text with \u201Csmart quotes\u201D";  // Unicode 智能双引号
        String expected = "Text with \"smart quotes\"";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testSmartQuotesToStraight_SingleQuotes() {
        String input = "Text with \u2018smart quotes\u2019";  // Unicode 智能单引号
        String expected = "Text with 'smart quotes'";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 空白符规范化测试
    // ============================================================

    @Test
    void testCollapseMultipleSpaces() {
        String input = "word1    word2     word3";
        String expected = "word1 word2 word3";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testRemoveSpacesBeforePunctuation() {
        String input = "word1 , word2 . word3 :";
        String expected = "word1, word2. word3:";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testPreserveIndentation() {
        String input = "  indented   line  with   spaces";
        String expected = "  indented line with spaces";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 字符串字面量处理测试
    // ============================================================

    @Test
    void testEscapedQuotes() {
        String input = "print \"escaped \\\" quote\"";
        String expected = "print \"escaped \\\" quote\"";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testMultipleStrings() {
        String input = "the \"first\" and the \"second\"";
        String expected = "\"first\" and \"second\"";  // 字符串外的 the 被移除
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testPreserveSpacesInsideString() {
        String input = "Return \"foo  bar\".";
        String expected = "Return \"foo  bar\".";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 综合测试
    // ============================================================

    @Test
    void testCompleteExample() {
        String input = """
            Module\tapp.
            // Comment line
            Rule greet:
              Return   "Hello,  the world"  .
            """;

        String expected = """
            Module app.

            Rule greet:
              Return "Hello,  the world".
            """;

        // 注释行保留空白占位，字符串内部空格保持原样
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testIdempotency() {
        String input = "the function returns a value";
        String first = canonicalizer.canonicalize(input);
        String second = canonicalizer.canonicalize(first);
        assertEquals(first, second, "规范化应该是幂等的");
    }

    @Test
    void testEmptyString() {
        String input = "";
        String expected = "";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testWhitespaceOnlyLines() {
        String input = "line1\n   \nline2";
        String expected = "line1\n\nline2";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 领域词汇表集成测试
    // ============================================================

    @Nested
    @DisplayName("领域词汇（ADR 0051：只展开字面量宏，不替换标识符）")
    class VocabularyIntegrationTests {

        @BeforeEach
        void setUp() {
            VocabularyRegistry.getInstance().reset();
        }

        private VocabularyScopes insuranceAutoScopes() {
            return VocabularyRegistry.getInstance().get("insurance.auto", "zh-CN").orElseThrow().scopes();
        }

        @Test
        @DisplayName("词汇不在 canonicalize 中替换标识符")
        void vocabularyDoesNotRewriteIdentifiers() {
            var canon = new Canonicalizer(
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"), insuranceAutoScopes());
            String result = canon.canonicalize("令 驾驶员 为 新建 驾驶员。");
            assertTrue(result.contains("驾驶员"), "本地化标识符保持原写法，实际: " + result);
            assertFalse(result.contains("Driver"), "不得在解析前替换为规范名，实际: " + result);
            assertFalse(canon.hasLiteralMacros(), "内置词汇无字面量宏");
        }

        @Test
        @DisplayName("字面量宏按折叠键展开，字符串内不展开，幂等")
        void literalMacroExpandsOutsideStringsOnly() {
            DomainVocabulary vocab = DomainVocabulary.builder("custom", "自定义", "en-US")
                .addStruct("MyStruct", "Struktur")
                .addLiteral("Hallo Welt", "gruss")
                .build();
            var canon = new Canonicalizer(
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("en-US"), VocabularyScopes.build(vocab));
            String result = canon.canonicalize("Return GRUSS plus \"gruss\" plus Struktur.");
            assertEquals("Return \"Hallo Welt\" plus \"gruss\" plus Struktur.", result);
            assertEquals(result, canon.canonicalize(result), "展开后再规范化应幂等");
            assertTrue(canon.hasLiteralMacros());
        }

        @Test
        @DisplayName("无词汇与空词汇的规范化结果相同")
        void emptyVocabularyEqualsNone() {
            var lexicon = aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("en-US");
            DomainVocabulary empty = DomainVocabulary.builder("empty", "空", "en-US").build();
            String input = "Rule f given x as Int, produce Int:\n  Return x.\n";
            assertEquals(new Canonicalizer(lexicon).canonicalize(input),
                new Canonicalizer(lexicon, VocabularyScopes.build(empty)).canonicalize(input));
        }
    }

    // ============================================================
    // 审计 #58：段感知 「」 转换 + 伪造 PUA 标记拒绝
    // ============================================================

    /**
     * #120：冠词作左操作数时被 removeArticles 吞掉。
     * <p>
     * 冠词移除仅 en-US 启用，语言特定用例通常在 aster-lang-en；但该仓 pin 的是
     * aster-lang-core:1.0.2（见 aster-lang-en#? / 本仓 #120 讨论），测不到本仓改动，
     * 故把回归锁在这里——本仓测试直接跑源码。
     */
    @Nested
    @DisplayName("冠词标识符保护 #120")
    class ArticleOperandTests {

        /**
         * 裸 {@code not equal to}（不带 {@code is}）是合法比较运算符，但
         * IDENTIFIER_FOLLOW_WORDS 曾漏掉 {@code not}，导致左操作数被当冠词删掉：
         * {@code Return a not equal to b.} → {@code Return not equal to b.}
         * <p>
         * ★本引擎的危害高于 TS 侧：该式**编译通过且恒返回 true**（一元 not 作用于
         * 无关表达式），{@code 5 != 5} 得到 true——静默错答案；TS 侧是编译失败。
         * <p>
         * ★三个冠词逐一断言：正则用 {@code (a|an|the)} 交替，只测 {@code a}
         * 会漏掉 {@code an}/{@code the} 各自的词边界情况。
         */
        @Test
        @DisplayName("冠词作操作数后跟裸 not equal to 不吞")
        void articleOperandBeforeBareNotEqualTo() {
            for (String art : new String[] {"a", "an", "the"}) {
                String out = canonicalizer.canonicalize("Return " + art + " not equal to b.");
                assertTrue(out.startsWith("Return " + art + " "),
                    "标识符 " + art + " 被当冠词吞掉了，实际输出: " + out);
            }
        }

        /** 对照：{@code is not equal to} 本就正常（被已在列的 {@code is} 保护）。 */
        @Test
        @DisplayName("冠词作操作数后跟 is not equal to 保持正常")
        void articleOperandBeforeIsNotEqualTo() {
            String out = canonicalizer.canonicalize("Return a is not equal to b.");
            assertTrue(out.startsWith("Return a "),
                "标识符 a 被吞掉了，实际输出: " + out);
        }

        /**
         * ★反向护栏：加 {@code not} 不能让**真冠词**逃过移除。
         * 若哪天有人把 follow-set 放得更宽，这条会转红。
         */
        @Test
        @DisplayName("真冠词后跟名词仍被移除（未因加 not 而放宽）")
        void realArticlesStillRemoved() {
            assertEquals("define function to return value",
                canonicalizer.canonicalize("define the function to return a value"));
        }
    }

    @Nested
    @DisplayName("审计 #58 安全")
    class Audit58SecurityTests {

        @Test
        @DisplayName("字符串字面量内的 「」 经 canonicalize 保留（不溢出到代码位）")
        void cjkBracketsInsideStringLiteralPreserved() {
            // 默认 en-US：字符串定界符是 ASCII "。字面量内部的中文直角引号 「」 必须原样保留，
            // 不能被 step 9.5 盲替换成 " 而把内容溢出到代码位（parse error / 语义漂移）。
            String input = "Let x be \"a「b」c\".";
            String out = canonicalizer.canonicalize(input);
            assertTrue(out.contains("「b」"),
                "字符串内的 「」 应保留，实际: " + out);
            assertFalse(out.contains("\"a\"b\"c\""),
                "内部 「」 不应被改写成 \"，实际: " + out);
        }

        @Test
        @DisplayName("原始输入含伪造 PUA 协议标记 U+E000–E003 被拒绝")
        void forgedPuaMarkersRejected() {
            char[] markers = { '\uE000', '\uE001', '\uE002', '\uE003' };
            for (char c : markers) {
                String input = "Let x be " + c + "evil.";
                assertThrows(IllegalArgumentException.class,
                    () -> canonicalizer.canonicalize(input),
                    "含 U+" + Integer.toHexString(c).toUpperCase() + " 的输入应被拒绝");
            }
            // 范围外的 PUA（U+E004）与普通文本不受影响。
            assertDoesNotThrow(() -> canonicalizer.canonicalize("plain text.\n"));
            assertDoesNotThrow(() -> canonicalizer.canonicalize("Let y be \"ok\".\n"));
        }
    }
}
