package aster.core.canonicalizer;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierIndex;
import aster.core.identifier.VocabularyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonicalizer 单元测试
 * <p>
 * 验证 Canonicalizer 的各项规范化功能是否正确。
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
    // 多词关键字大小写规范化测试
    // ============================================================

    @Test
    void testNormalizeMultiWordKeywords_ModuleIs() {
        String input = "This Module Is app.";
        String expected = "this module is app.";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testNormalizeMultiWordKeywords_OneOf() {
        String input = "As One Of the options.";
        String expected = "as one of options.";  // "the" 会被移除
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testNormalizeMultiWordKeywords_WaitFor() {
        String input = "Wait For the result.";
        String expected = "wait for result.";  // "the" 会被移除
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    // ============================================================
    // 冠词移除测试
    // ============================================================

    @Test
    void testRemoveArticles_Basic() {
        String input = "define the function to return a value";
        String expected = "define function to return value";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testRemoveArticles_PreserveInStrings() {
        String input = "print \"the quick brown fox\"";
        String expected = "print \"the quick brown fox\"";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testRemoveArticles_MixedContext() {
        String input = "call the function with \"the parameter\"";
        String expected = "call function with \"the parameter\"";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testRemoveArticles_AllArticleTypes() {
        String input = "a function takes an input and returns the result";
        String expected = "function takes input and returns result";
        assertEquals(expected, canonicalizer.canonicalize(input));
    }

    @Test
    void testRemoveArticles_OnlyWithTrailingSpace() {
        String input = "the function with parameter";
        // "the " 会被移除（正则匹配 \b(a|an|the)\b\s）
        String expected = "function with parameter";
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
            This Module Is\tapp.
            // Comment line
            To greet,  produce   Text  :
              Return   "Hello,  the world"  .
            """;

        String expected = """
            this module is app.

            To greet, produce Text:
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
    // 中文关键词翻译测试（使用 ZhCnLexicon）
    // ============================================================

    @Test
    void testChineseKeywordTranslation_ControlFlow() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 测试控制流关键词翻译（大小写与 ANTLR 匹配）
        String input = "若 条件";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("If"), "中文'若'应翻译为'If'，实际结果: " + result);
    }

    @Test
    void testChineseKeywordTranslation_Return() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        String input = "返回 值";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("Return"), "中文'返回'应翻译为'Return'，实际结果: " + result);
    }

    @Test
    void testChineseKeywordTranslation_Boolean() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        String input = "真 且 假 或 非";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("true"), "中文'真'应翻译为'true'");
        assertTrue(result.contains("and"), "中文'且'应翻译为'and'");
        assertTrue(result.contains("false"), "中文'假'应翻译为'false'");
        assertTrue(result.contains("or"), "中文'或'应翻译为'or'");
        assertTrue(result.contains("not"), "中文'非'应翻译为'not'");
    }

    @Test
    void testChineseKeywordTranslation_ModuleDecl() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        String input = "【模块】测试";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("this module is"), "中文'【模块】'应翻译为'this module is'，实际结果: " + result);
    }

    @Test
    void testChineseKeywordTranslation_Variable() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        String input = "令 变量 为 10";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("Let"), "中文'令'应翻译为'Let'");
        assertTrue(result.contains("be"), "中文'为'应翻译为'be'");
    }

    @Test
    void testChineseKeywordTranslation_PreserveIdentifiers() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 中文标识符（变量名、函数名）应保留
        String input = "令 中文变量名 为 10";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("中文变量名"), "中文标识符应保留，实际结果: " + result);
    }

    @Test
    void testChineseKeywordTranslation_PreserveStrings() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 字符串内容应保留
        String input = "返回 \"若 这是字符串\"";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("\"若 这是字符串\""), "字符串内的中文应保留，实际结果: " + result);
        assertTrue(result.contains("Return"), "'返回'应翻译为'Return'");
    }

    @Test
    void testEnglishLexiconNoTranslation() {
        // 英文 Lexicon 不应进行翻译
        var enCanonicalizer = new Canonicalizer(aster.core.lexicon.EnUsLexicon.INSTANCE);

        String input = "if condition return true";
        String result = enCanonicalizer.canonicalize(input);
        assertEquals("if condition return true", result);
    }

    // ============================================================
    // 中文标识符保护测试（词边界检测）
    // ============================================================

    @Test
    void testChineseIdentifier_NotBreakByKeyword() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // "若何" 是中文标识符，不应被 "若" 关键字替换破坏
        String input = "令 若何 为 10";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("若何"), "标识符'若何'不应被关键词'若'替换破坏，实际结果: " + result);
        assertTrue(result.contains("Let"), "'令'应翻译为'Let'");
    }

    @Test
    void testChineseIdentifier_KeywordAtBoundary() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // "若" 后面有空格，应该被翻译
        String input = "若 条件成立";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("If"), "独立的'若'关键字应翻译为'If'，实际结果: " + result);
        assertTrue(result.contains("条件成立"), "'条件成立'标识符应保留");
    }

    @Test
    void testChineseIdentifier_MultipleKeywordsInIdentifier() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 标识符中包含多个关键字字符，都不应被替换
        String input = "令 返回值 为 若干";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("返回值"), "标识符'返回值'不应被'返回'关键词破坏，实际结果: " + result);
        assertTrue(result.contains("若干"), "标识符'若干'不应被'若'关键词破坏");
    }

    @Test
    void testChineseIdentifier_KeywordFollowedByPunctuation() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 关键字后面是标点（如冒号），应该被翻译
        String input = "若:";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("If"), "关键字后面是标点时应翻译，实际结果: " + result);
    }

    @Test
    void testMultiWordKeyword_NotModifyStrings() {
        // 多词关键词规范化不应修改字符串字面量
        String input = "Return \"This Module Is test\".";
        String result = new Canonicalizer().canonicalize(input);
        assertTrue(result.contains("\"This Module Is test\""),
            "字符串字面量内的多词关键词不应被修改，实际结果: " + result);
    }

    // ============================================================
    // 混合脚本标识符测试（ASCII + 中文）
    // ============================================================

    @Test
    void testMixedScript_ChineseAndAscii() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 混合脚本标识符：中文 + ASCII
        String input = "令 变量若value 为 10";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("变量若value"), "混合脚本标识符不应被关键词替换破坏，实际结果: " + result);
        assertTrue(result.contains("Let"), "'令'应翻译为'Let'");
    }

    @Test
    void testMixedScript_UnderscoreIdentifier() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 下划线标识符
        String input = "令 若_identifier 为 10";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("若_identifier"), "下划线标识符不应被关键词替换破坏，实际结果: " + result);
    }

    @Test
    void testMixedScript_AsciiSurrounding() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // ASCII 字符包围的中文关键词
        String input = "令 A若B 为 10";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("A若B"), "ASCII包围的标识符不应被关键词替换破坏，实际结果: " + result);
    }

    @Test
    void testMixedScript_NumberSuffix() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 数字后缀标识符
        String input = "令 若123 为 10";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("若123"), "数字后缀标识符不应被关键词替换破坏，实际结果: " + result);
    }

    @Test
    void testKeyword_StandaloneWithSpace() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 独立关键词（前后有空格）
        String input = "若 条件 返回 值";
        String result = zhCanonicalizer.canonicalize(input);
        assertTrue(result.contains("If"), "独立的'若'应翻译为'If'");
        assertTrue(result.contains("Return"), "独立的'返回'应翻译为'Return'");
    }

    // ============================================================
    // 函数调用内字符串参数测试（回归测试：防止在函数调用内添加额外句号）
    // ============================================================

    @Test
    void testChineseReturn_WithFunctionCallAndChineseString() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 返回语句中包含函数调用，函数参数为中文字符串字面量
        // 此测试验证不会在函数调用括号内错误添加句号
        String input = "返回 len(「hello」)。";
        String result = zhCanonicalizer.canonicalize(input);

        System.out.println("输入: " + input);
        System.out.println("输出: " + result);

        // 测试完整源代码（包含模块声明和函数定义）
        String fullSource = "【模块】测试。\n\nmain 入参 值：整数，产出 文本：\n  返回 len(「hello」)。";
        String fullResult = zhCanonicalizer.canonicalize(fullSource);
        System.out.println("\n完整源代码:");
        System.out.println(fullSource);
        System.out.println("\n规范化结果:");
        System.out.println(fullResult);

        // 验证输出格式正确：应该是 Return len("hello").
        // 不应该有双句号或括号内句号
        assertTrue(result.contains("Return"), "'返回'应翻译为'Return'，实际结果: " + result);
        assertTrue(result.contains("len(\"hello\")"), "函数调用应保持完整，实际结果: " + result);
        assertFalse(result.contains("len(\"hello\")..)"), "不应在括号后有双句号");
        assertFalse(result.contains("len(\"hello\".)"), "不应在括号内有句号");

        // 应该以单个句号结尾
        String trimmed = result.trim();
        assertTrue(trimmed.endsWith("."), "应以单个句号结尾，实际结果: " + result);
        assertFalse(trimmed.endsWith(".."), "不应以双句号结尾，实际结果: " + result);
    }

    @Test
    void testChineseReturn_WithNestedFunctionCall() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 嵌套函数调用
        String input = "返回 upper(trim(「 hello 」))。";
        String result = zhCanonicalizer.canonicalize(input);

        System.out.println("输入: " + input);
        System.out.println("输出: " + result);

        assertTrue(result.contains("Return"), "'返回'应翻译为'Return'");
        // 验证嵌套函数调用结构完整
        assertTrue(result.contains("upper(trim("), "嵌套函数调用结构应保持完整");
    }

    @Test
    void testChineseReturn_WithMultipleStringArgs() {
        var zhCanonicalizer = new Canonicalizer(aster.core.lexicon.ZhCnLexicon.INSTANCE);

        // 多个字符串参数的函数调用
        String input = "返回 concat(「hello」, 「world」)。";
        String result = zhCanonicalizer.canonicalize(input);

        System.out.println("输入: " + input);
        System.out.println("输出: " + result);

        assertTrue(result.contains("Return"), "'返回'应翻译为'Return'");
        assertTrue(result.contains("concat("), "函数名应保留");
        // 中文逗号会被转换为英文逗号
        assertTrue(result.contains("\"hello\"") && result.contains("\"world\""),
                "字符串参数应正确转换");
    }

    // ============================================================
    // 领域词汇表集成测试
    // ============================================================

    @Nested
    @DisplayName("领域词汇表集成")
    class VocabularyIntegrationTests {

        @BeforeEach
        void setUp() {
            // 确保 VocabularyRegistry 处于初始状态
            VocabularyRegistry.getInstance().reset();
        }

        @Test
        @DisplayName("使用单领域词汇表构造")
        void constructWithSingleDomain() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            assertTrue(canon.hasIdentifierTranslation(), "应启用标识符翻译");
            assertNotNull(canon.getIdentifierIndex(), "应有标识符索引");
        }

        @Test
        @DisplayName("使用多领域词汇表构造")
        void constructWithMultipleDomains() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                List.of("insurance.auto", "finance.loan"),
                "zh-CN"
            );

            assertTrue(canon.hasIdentifierTranslation(), "应启用标识符翻译");
        }

        @Test
        @DisplayName("无效领域返回无索引")
        void constructWithInvalidDomain() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "nonexistent.domain",
                "zh-CN"
            );

            assertFalse(canon.hasIdentifierTranslation(), "无效领域不应启用标识符翻译");
            assertNull(canon.getIdentifierIndex(), "无效领域应返回 null 索引");
        }

        @Test
        @DisplayName("基本结构体标识符翻译")
        void translateStructIdentifier() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            String input = "令 驾驶员 为 新建 驾驶员。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("Driver"), "结构体'驾驶员'应翻译为'Driver'，实际结果: " + result);
        }

        @Test
        @DisplayName("字段标识符翻译")
        void translateFieldIdentifier() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            String input = "令 结果 为 驾驶员 的 年龄。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("Driver"), "'驾驶员'应翻译为'Driver'");
            assertTrue(result.contains("age"), "'年龄'应翻译为'age'，实际结果: " + result);
        }

        @Test
        @DisplayName("函数标识符翻译")
        void translateFunctionIdentifier() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            String input = "调用 计算保费 以 驾驶员。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("calculatePremium"), "'计算保费'应翻译为'calculatePremium'，实际结果: " + result);
            assertTrue(result.contains("Driver"), "'驾驶员'应翻译为'Driver'");
        }

        @Test
        @DisplayName("别名标识符翻译")
        void translateAliasIdentifier() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            // "司机" 是 "驾驶员" 的别名
            String input = "令 司机 为 新建 驾驶人。";
            String result = canon.canonicalize(input);

            // 两个别名都应翻译为 Driver
            assertTrue(result.contains("Driver"), "别名'司机'和'驾驶人'应翻译为'Driver'，实际结果: " + result);
        }

        @Test
        @DisplayName("保留字符串内的标识符")
        void preserveIdentifiersInStrings() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            String input = "返回 \"驾驶员信息\"。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("\"驾驶员信息\""), "字符串内的标识符不应翻译，实际结果: " + result);
        }

        @Test
        @DisplayName("未映射标识符保持原样")
        void preserveUnmappedIdentifiers() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            String input = "令 自定义变量 为 10。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("自定义变量"), "未映射的标识符应保留，实际结果: " + result);
        }

        @Test
        @DisplayName("关键字与标识符翻译组合")
        void combineKeywordAndIdentifierTranslation() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            // 同时包含关键字和领域标识符
            String input = "若 驾驶员 的 年龄 大于 18，返回 真。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("If"), "'若'关键字应翻译为'If'");
            assertTrue(result.contains("Driver"), "'驾驶员'应翻译为'Driver'");
            assertTrue(result.contains("age"), "'年龄'应翻译为'age'");
            assertTrue(result.contains("Return"), "'返回'应翻译为'Return'");
            assertTrue(result.contains("true"), "'真'应翻译为'true'");
        }

        @Test
        @DisplayName("多领域词汇表标识符翻译")
        void translateMultiDomainIdentifiers() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                List.of("insurance.auto", "finance.loan"),
                "zh-CN"
            );

            // 包含两个领域的标识符
            String input = "令 司机 为 驾驶员，令 借款人 为 申请人。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("Driver"), "汽车保险领域的'驾驶员'/'司机'应翻译为'Driver'，实际结果: " + result);
            assertTrue(result.contains("Applicant"), "贷款金融领域的'申请人'/'借款人'应翻译为'Applicant'，实际结果: " + result);
        }

        @Test
        @DisplayName("完整 CNL 语句翻译")
        void translateCompleteCnlStatement() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            String input = """
                【模块】汽车保险报价。

                生成报价 入参 驾驶员：驾驶员，车辆：车辆，产出 报价结果：
                  令 年龄因子 为 计算年龄因子 以 驾驶员 的 年龄。
                  令 驾龄因子 为 计算驾龄因子 以 驾驶员 的 驾龄。
                  返回 新建 报价结果。
                """;

            String result = canon.canonicalize(input);

            // 验证关键词翻译
            assertTrue(result.contains("this module is"), "'【模块】'应翻译为'this module is'");

            // 验证领域标识符翻译
            assertTrue(result.contains("generateQuote") || result.contains("生成报价"),
                "函数名'生成报价'应翻译为'generateQuote'或保留");
            assertTrue(result.contains("Driver"), "'驾驶员'应翻译为'Driver'");
            assertTrue(result.contains("Vehicle"), "'车辆'应翻译为'Vehicle'");
            assertTrue(result.contains("QuoteResult"), "'报价结果'应翻译为'QuoteResult'");
            assertTrue(result.contains("age"), "'年龄'应翻译为'age'");
            assertTrue(result.contains("drivingYears"), "'驾龄'应翻译为'drivingYears'");
        }

        @Test
        @DisplayName("使用自定义 IdentifierIndex 构造")
        void constructWithCustomIdentifierIndex() {
            // 创建自定义词汇表
            DomainVocabulary customVocab = DomainVocabulary.builder("custom", "自定义", "zh-CN")
                .addStruct("MyStruct", "我的结构体")
                .addField("myField", "我的字段", "MyStruct")
                .build();

            IdentifierIndex customIndex = IdentifierIndex.build(customVocab);

            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                customIndex
            );

            String input = "令 变量 为 新建 我的结构体。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("MyStruct"), "'我的结构体'应翻译为'MyStruct'，实际结果: " + result);
        }

        @Test
        @DisplayName("标识符翻译幂等性")
        void identifierTranslationIdempotency() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "insurance.auto",
                "zh-CN"
            );

            String input = "令 驾驶员 为 新建 驾驶员。";
            String first = canon.canonicalize(input);
            String second = canon.canonicalize(first);

            assertEquals(first, second, "标识符翻译应该是幂等的");
        }

        @Test
        @DisplayName("贷款金融领域标识符翻译")
        void translateFinanceLoanIdentifiers() {
            var canon = new Canonicalizer(
                aster.core.lexicon.ZhCnLexicon.INSTANCE,
                "finance.loan",
                "zh-CN"
            );

            String input = "若 申请人 的 信用评分 大于 700，返回 新建 审批结果。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("Applicant"), "'申请人'应翻译为'Applicant'，实际结果: " + result);
            assertTrue(result.contains("creditScore"), "'信用评分'应翻译为'creditScore'，实际结果: " + result);
            assertTrue(result.contains("ApprovalResult"), "'审批结果'应翻译为'ApprovalResult'，实际结果: " + result);
        }
    }
}
