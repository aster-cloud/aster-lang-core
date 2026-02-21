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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
                List.of("insurance.auto", "finance.loan"),
                "zh-CN"
            );

            assertTrue(canon.hasIdentifierTranslation(), "应启用标识符翻译");
        }

        @Test
        @DisplayName("无效领域返回无索引")
        void constructWithInvalidDomain() {
            var canon = new Canonicalizer(
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
                "insurance.auto",
                "zh-CN"
            );

            // 同时包含关键字和领域标识符
            // 与 TypeScript 前端保持一致："如果" 是 IF
            String input = "如果 驾驶员 的 年龄 大于 18，返回 真。";
            String result = canon.canonicalize(input);

            assertTrue(result.contains("If"), "'如果'关键字应翻译为'If'");
            assertTrue(result.contains("Driver"), "'驾驶员'应翻译为'Driver'");
            assertTrue(result.contains("age"), "'年龄'应翻译为'age'");
            assertTrue(result.contains("Return"), "'返回'应翻译为'Return'");
            assertTrue(result.contains("true"), "'真'应翻译为'true'");
        }

        @Test
        @DisplayName("多领域词汇表标识符翻译")
        void translateMultiDomainIdentifiers() {
            var canon = new Canonicalizer(
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
                "insurance.auto",
                "zh-CN"
            );

            String input = """
                模块 汽车保险报价。

                生成报价 入参 驾驶员：驾驶员，车辆：车辆，产出 报价结果：
                  令 年龄因子 为 计算年龄因子 以 驾驶员 的 年龄。
                  令 驾龄因子 为 计算驾龄因子 以 驾驶员 的 驾龄。
                  返回 新建 报价结果。
                """;

            String result = canon.canonicalize(input);

            // 验证关键词翻译
            assertTrue(result.contains("Module"), "'模块'应翻译为'Module'");

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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
                aster.core.lexicon.LexiconRegistry.getInstance().getOrThrow("zh-CN"),
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
