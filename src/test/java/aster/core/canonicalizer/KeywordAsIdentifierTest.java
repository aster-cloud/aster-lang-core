package aster.core.canonicalizer;

import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterLexer;
import aster.core.parser.AsterParser;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：非英文（zh）词法包下，用户标识符与"单源词→多英文词"的 CNL 关键词同形时，
 * 应在标识符位置当标识符（名=源词，与 TS 引擎 parity），在关键词位置仍当关键词。
 * <p>
 * 历史 bug：zh `结果`(RESULT_OF) 当字段名 → 翻译层展开成 `result of` 两 token → 撑破
 * 标识符位置解析失败。修复：Canonicalizer 把这些源词包成自包含标记单 token，
 * AsterCustomLexer 按位置还原（标识符）或展开（关键词）。
 */
class KeywordAsIdentifierTest {

    private boolean parses(String src) {
        var canon = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("zh-CN"), null);
        String en = canon.canonicalize(src);
        final boolean[] err = {false};
        try {
            var lexer = new AsterCustomLexer(CharStreams.fromString(en));
            var tokens = new CommonTokenStream(lexer);
            tokens.fill();
            tokens.seek(0);
            var parser = new AsterParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> r, Object s, int l, int c, String m, RecognitionException e) {
                    err[0] = true;
                }
            });
            parser.module();
        } catch (Exception e) {
            err[0] = true;
        }
        return !err[0];
    }

    @Test
    void resultOfKeywordAsFieldName() {
        // 结果 = RESULT_OF 关键词，当字段名应解析成功
        assertTrue(parses("模块 a.b。\n定义 盒子 包含 结果。\n"), "结果 当字段名");
    }

    @Test
    void keywordIdentInFieldList() {
        // 字段列表中逗号前后均可用关键词当字段名
        assertTrue(parses("模块 a.b。\n定义 盒子 包含 年龄，结果。\n"), "年龄,结果");
        assertTrue(parses("模块 a.b。\n定义 盒子 包含 结果，年龄。\n"), "结果,年龄");
        assertTrue(parses("模块 a.b。\n定义 盒子 包含 年龄，结果，金额。\n"), "年龄,结果,金额");
    }

    @Test
    void keywordIdentAsParamAndVar() {
        assertTrue(parses("模块 a.b。\n规则 f 给定 结果 产出：\n  返回 结果。\n"), "结果 当参数名");
        assertTrue(parses("模块 a.b。\n规则 f 给定 x，结果 产出：\n  返回 结果。\n"), "结果 参数列表");
        assertTrue(parses("模块 a.b。\n规则 f 给定 x 产出：\n  令 结果 定义为 x。\n  返回 结果。\n"), "结果 当变量名+引用");
    }

    @Test
    void keywordIdentAsTypeName() {
        assertTrue(parses("模块 a.b。\n定义 结果 包含 值。\n"), "结果 当类型名");
    }

    @Test
    void constructorKeywordWorksBothWays() {
        // 成功值 = OK_OF 是真构造器：既能当字段名（标识符）又能当构造器（关键词）
        assertTrue(parses("模块 a.b。\n定义 盒子 包含 成功值。\n"), "成功值 当字段名");
        assertTrue(parses("模块 a.b。\n规则 f 给定 x 产出：\n  返回 成功值 x。\n"), "成功值 x 构造器");
    }

    @Test
    void identifierNameEqualsSourceWord() {
        // parity 硬约束：标识符名 = 源词 `结果`（与 TS 引擎一致），且无标记残留
        var canon = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("zh-CN"), null);
        String en = canon.canonicalize("模块 a.b。 定义 盒子 包含 结果。");
        var lexer = new AsterCustomLexer(CharStreams.fromString(en));
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();
        boolean found = false;
        for (var tok : tokens.getTokens()) {
            if ("结果".equals(tok.getText())) {
                found = true;
            }
            assertFalse(tok.getText().indexOf(Canonicalizer.KW_IDENT_MARKER_OPEN) >= 0,
                "不应有标记残留: " + tok.getText());
        }
        assertTrue(found, "字段名 token 应为源词 结果");
    }

    @Test
    void normalFieldsUnaffected() {
        assertTrue(parses("模块 a.b。\n定义 盒子 包含 年龄，金额。\n"), "普通字段不受影响");
    }

    @Test
    void ofFamilyInTypePositionStaysKeyword() {
        // 类型注解位置(作为 结果 文本 = as result-of Text):结果 是类型构造关键词,非标识符
        assertTrue(parses("模块 a.b。\n定义 盒子 包含 字段 作为 结果 文本。\n"), "结果 在类型注解位置");
    }

    @Test
    void constructorWithNullOperand() {
        // 成功值 空值 (ok of null):null 是合法表达式操作数,成功值 应当关键词展开
        assertTrue(parses("模块 a.b。\n规则 f 给定 x 产出：\n  返回 成功值 空值。\n"), "成功值 空值");
    }

    @Test
    void variableReferenceRestored() {
        // 变量引用 `返回 结果`(结果 是先前 Let/参数声明):后继是句末符 → 还原成标识符
        assertTrue(parses("模块 a.b。\n规则 f 给定 结果 产出：\n  返回 结果。\n"), "参数 结果 引用");
        assertTrue(parses("模块 a.b。\n规则 f 给定 x 产出：\n  令 结果 定义为 x。\n  返回 结果。\n"), "变量 结果 引用");
    }

    @Test
    void enumAndReturnShorthandUnaffected() {
        // 结构短语(为以下之一/结果为)不被包裹,其 enum/return-shorthand 关键词用法不受影响
        assertTrue(parses("模块 a.b。\n定义 状态 为以下之一 甲，乙。\n"), "为以下之一 enum");
        assertTrue(parses("模块 a.b。\n规则 f 给定 x 产出：\n  结果为 x。\n"), "结果为 return shorthand");
    }

    @Test
    void ofFamilyInValueRhsStaysKeyword() {
        // 表达式右值位置(Let be / set to 之后)OF 家族是构造器关键词,不能被"声明列表"泄漏误当标识符
        assertTrue(parses("模块 a.b。\n规则 f 给定 x 产出：\n  令 y 定义为 成功值 x。\n  返回 y。\n"),
            "Let y be 成功值 x 构造器");
    }

    @Test
    void ofFamilyAsFunctionName() {
        // OF 家族当函数名(规则 结果 ...)应还原成标识符,名=源词(与 TS parity)
        assertTrue(parses("模块 a.b。\n规则 结果 给定 x 产出：\n  返回 x。\n"), "结果 当函数名");
    }

    @Test
    void structuralPhraseAsFieldRejected() {
        // 结构短语当字段名两引擎都拒绝(仅 OF 家族还原,保持 parity)
        assertFalse(parses("模块 a.b。\n定义 盒子 包含 为以下之一。\n"), "为以下之一 当字段名应拒绝");
    }

    @Test
    void withAsCallSuffixStaysKeyword() {
        // with(包含) 也是函数调用后缀(`g 包含 成功值 x`),其参数位置的 OF 家族是构造器关键词,
        // 不能被误当声明字段名 → 靠后继(x 是表达式起点)判定为关键词展开
        assertTrue(parses("模块 a.b。\n规则 g 给定 a 产出：\n  返回 a。\n\n"
            + "规则 f 给定 x 产出：\n  返回 g 包含 成功值 x。\n"), "g 包含 成功值 x");
    }
}
