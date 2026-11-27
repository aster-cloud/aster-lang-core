package aster.core.canonicalizer;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.VocabularyScopes;
import aster.core.identifier.VocabularyRegistry;
import aster.core.lexicon.LexiconRegistry;
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

    // aster-lang-ts#347：弯引号只在充当字符串定界符时改写，字符串字面量内容原样保留。
    // 与 aster-lang-ts test/unit/canonicalizer/quote-delimiters.test.ts 逐条对应。
    // ★字符一律写 Unicode 转义：编辑器的"智能引号"替换会把测试本身写歪。

    @Test
    void smartQuotesInsideStringLiteralArePreserved() {
        String input = "Return \"it\u2019s \u2018q\u2019 and \u201Chi\u201D\".";
        assertEquals(input, canonicalizer.canonicalize(input));
    }

    @Test
    void curlyDelimitedStringKeepsItsContent() {
        assertEquals("Return \"a \u2018b\u2019 c\".",
            canonicalizer.canonicalize("Return \u201Ca \u2018b\u2019 c\u201D."));
    }

    @Test
    void curlyOpenedStringClosesOnAnyDoubleQuote() {
        // “…" 与 “…“ 此前都被整段改成 "…"，仍是合法字符串
        assertEquals("Return \"abc\".", canonicalizer.canonicalize("Return \u201Cabc\"."));
        assertEquals("Return \"abc\".", canonicalizer.canonicalize("Return \u201Cabc\u201C."));
    }

    @Test
    void asciiOpenedStringIsNotClosedByCurlyQuote() {
        // "abc” 的 ” 是内容：字符串不再闭合，交给词法器报未闭合（E051）
        String input = "Return \"abc\u201D.";
        assertEquals(input, canonicalizer.canonicalize(input));
    }

    @Test
    void zhCjkStringKeepsQuotesAsContent() {
        // zh-CN 由 aster-lang-locales 经 SPI 提供（与 ParseDiagnosticsParityTest 相同）；放在 core 而非语言包，
        // 因为被测的是 core 的 convertCjkStringQuotes
        Canonicalizer zh = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("zh-CN"));
        // 「」换成 ASCII 引号时，内容里未转义的 " 转义成 \"，弯引号原样保留（关键词 返回 译成 Return）
        assertEquals("Return \"他说\u201C好\u201D，a\\\"b，it\u2019s\".",
            zh.canonicalize("返回 \u300C他说\u201C好\u201D，a\"b，it\u2019s\u300D。"));
        // 已转义的 \" 不重复转义
        assertEquals("Return \"a\\\"b\".", zh.canonicalize("返回 \u300Ca\\\"b\u300D。"));
        // “…” 定界的字符串照常换成 ASCII 引号
        assertEquals("Return \"中文\".", zh.canonicalize("返回 \u201C中文\u201D。"));
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
         * #243：{@code Define <冠词同形词> has|with} 中该词即类型名（语法
         * {@code DEFINE article? typeIdent (HAS|WITH)}），按位置保护，不能当冠词删。
         */
        @Test
        @DisplayName("Define 名为冠词同形词时保留 #243")
        void defineNameHomographOfArticleKept() {
            for (String name : new String[] {"A", "An", "The", "a", "an", "the"}) {
                for (String kw : new String[] {"has", "with"}) {
                    String out = canonicalizer.canonicalize("Define " + name + " " + kw + " score: Int.");
                    assertEquals("Define " + name + " " + kw + " score: Int.", out, "Define 名被当冠词吞掉");
                }
            }
        }

        /** 反向护栏：Define 后真冠词修饰类型名、Return 中自然语言冠词仍删除。 */
        @Test
        @DisplayName("Define/Return 中的真冠词仍删除 #243")
        void realArticlesAroundDefineStillRemoved() {
            assertEquals("Define User has score: Int.", canonicalizer.canonicalize("Define a User has score: Int."));
            assertEquals("Define User with score: Int.", canonicalizer.canonicalize("Define the User with score: Int."));
            assertEquals("Return Decision with ok = true.", canonicalizer.canonicalize("Return a Decision with ok = true."));
        }

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

    // ============================================================
    // Set 的目标名不读作多词运算符（aster-lang-core#328）
    // ============================================================

    @Nested
    @DisplayName("Set 的目标名 equals 不改写成运算符 equals to")
    class SetTargetMultiWordKeyword {

        @Test
        @DisplayName("行首 Set / set（可缩进）之后的「名字 to」原样保留")
        void setTargetKeptAsWritten() {
            assertEquals("  Set Equals to 5.", canonicalizer.canonicalize("  Set Equals to 5."));
            assertEquals("  Set EQUALS to 5.", canonicalizer.canonicalize("  Set EQUALS to 5."));
            assertEquals("Set eQUALS to 5.", canonicalizer.canonicalize("Set eQUALS to 5."));
            assertEquals("  set Equals to 5.", canonicalizer.canonicalize("  set Equals to 5."));
            // 前面的行里有字符串字面量（分段不从源码开头起）也照样按行首判定
            assertEquals("  Let s be \"a\".\n  Set Equals to 5.",
                canonicalizer.canonicalize("  Let s be \"a\".\n  Set Equals to 5."));
        }

        @Test
        @DisplayName("SET 等其余大小写是同形词（可以是变量名），读法不变")
        void otherSetSpellingsUnchanged() {
            assertEquals("  SET equals to 5.", canonicalizer.canonicalize("  SET Equals to 5."));
        }

        @Test
        @DisplayName("操作数之后的 Equals to 照旧归一成小写运算符")
        void operatorAfterOperandStillLowercased() {
            assertEquals("  Return x equals to 5.", canonicalizer.canonicalize("  Return x Equals to 5."));
            assertEquals("  Set y to x equals to 5.", canonicalizer.canonicalize("  Set y to x Equals to 5."));
        }

        @Test
        @DisplayName("Set 不在行首、或同一行此前有字符串字面量时不按 Set 目标处理")
        void onlyStatementInitialSet() {
            assertEquals("  Return Set equals to 5.", canonicalizer.canonicalize("  Return Set Equals to 5."));
            assertEquals("  Return \"Set\" equals to 5.", canonicalizer.canonicalize("  Return \"Set\" Equals to 5."));
            // to 只是更长单词的前缀时不是 Set 的 to，照旧按多词关键字改写
            assertEquals("  Set equals tomorrow.", canonicalizer.canonicalize("  Set Equals tomorrow."));
        }
    }
}
