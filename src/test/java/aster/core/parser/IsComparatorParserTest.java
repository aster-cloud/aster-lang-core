package aster.core.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0013 #1b-i：自然语言比较器在**裸 parser 路径**上与 TS 引擎对齐。
 *
 * <p>背景（本地 podman E2E + dual-engine parity gate 实证）：1b-i 的 Java 实现
 * 此前只把可选 {@code is} 连接词放在 canonicalizer 的 IsComparatorTransformer
 * 里，而 dual-engine parity 的 {@code TsSampleParseInventoryTest} 走的是
 * <b>裸 lexer+parser</b>（不经 canonicalize），于是 {@code is}/{@code under}/
 * {@code over} 在该路径上失败 → {@code 21-comparison-is-prefix} 报 Java ✗。
 *
 * <p>修复：多词比较词 token 内吸收可选 {@code is} 前缀（lexer 安全，不与标识符
 * 冲突）；{@code under}/{@code over} 作为 comparisonExpr 里的**软关键字**（语义
 * 谓词匹配 IDENT 文本），仅比较位置当运算符、其余位置仍是普通标识符——与 TS
 * parseComparison 完全一致。本测试在裸 parser 路径上锁住该行为。
 */
class IsComparatorParserTest {

    /** 裸 parser 路径（与 dual-engine inventory gate 一致，不经 canonicalize）。 */
    private static boolean parsesClean(String source) {
        List<String> errors = new ArrayList<>();
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        tokens.seek(0);
        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                    String msg, RecognitionException e) {
                errors.add("L" + line + ":" + col + " " + msg);
            }
        });
        parser.module();
        return errors.isEmpty();
    }

    private static String rule(String returnExpr) {
        return "Module m.\nRule r given s, produce:\n  Return " + returnExpr + ".";
    }

    @Test
    void is_prefix_comparators_parse_on_bare_parser() {
        // 全部 7 个自然语言比较器 + 可选 is 前缀，裸 parser 直接通过。
        assertTrue(parsesClean(rule("s is at least 700")));
        assertTrue(parsesClean(rule("s is at most 700")));
        assertTrue(parsesClean(rule("s is greater than 700")));
        assertTrue(parsesClean(rule("s is less than 700")));
        assertTrue(parsesClean(rule("s is more than 700")));
        assertTrue(parsesClean(rule("s is under 700")));
        assertTrue(parsesClean(rule("s is over 700")));
    }

    @Test
    void bare_comparators_without_is_still_parse() {
        assertTrue(parsesClean(rule("s at least 700")));
        assertTrue(parsesClean(rule("s under 700")));
        assertTrue(parsesClean(rule("s over 700")));
        assertTrue(parsesClean(rule("s more than 700")));
    }

    @Test
    void under_over_remain_usable_as_identifiers() {
        // 与 TS 一致：under/over 是软关键字，非比较位置仍是普通标识符。
        assertTrue(parsesClean("Module m.\nRule r given under, produce:\n  Return under."));
        assertTrue(parsesClean("Module m.\nRule r given x, produce:\n  Return x.over."));
    }

    @Test
    void full_canonicalize_path_gives_under_over_the_same_meaning_as_canonical_spelling() {
        // 互补验证：完整 canonicalize 链下，`under`/`over` 与其规范拼写
        // （`less than`/`greater than`）**语义等价**——与裸 parser 路径殊途同归。
        //
        // ★本断言测的是 IR 等价，不是 canonical 文本形态（ADR 0037 步骤 2）。
        //   原断言写的是 `out.contains("s < 700")`，即要求 canonical 文本里出现符号 `<`。
        //   那锁住的是**实现细节**而非契约：英语规范拼写现已不再被翻译成符号
        //   （grammar 本就认 PLUS_WORD/LT_WORD 等词形；翻译唯一的作用是让行变短、
        //   使 origin.col 偏离用户原文，见 Canonicalizer 中的说明）。
        //   真正要守的契约是「两种写法产出同一个程序」，故改为逐字段比对 Core IR。
        Canonicalizer canon = new Canonicalizer(LexiconRegistry.getInstance().getDefault());

        assertSameIr(canon, "s is under 700", "s is less than 700");
        assertSameIr(canon, "s is over 700", "s is greater than 700");
    }

    /** 断言两种写法经完整 canonicalize→parse→lower 后产出相同的 Core IR（位置信息除外）。 */
    private static void assertSameIr(Canonicalizer canon, String aliasForm, String canonicalForm) {
        String aliasIr = irOfRule(canon, aliasForm);
        String canonicalIr = irOfRule(canon, canonicalForm);
        assertEquals(canonicalIr, aliasIr,
            "`" + aliasForm + "` 与 `" + canonicalForm + "` 应产出相同的 Core IR。"
                + "\n★若不同，说明软关键字 under/over 的语义与其规范拼写发生了分叉——"
                + "\n  这会让同一段逻辑因写法不同而执行出不同结果。");
    }

    /** 完整链路：canonicalize → parse → lower → JSON（剥掉 origin，只比结构/语义）。 */
    private static String irOfRule(Canonicalizer canon, String body) {
        String canonical = canon.canonicalize(rule(body));
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object o, int line, int col,
                                    String msg, RecognitionException e) {
                throw new IllegalStateException("解析失败 @" + line + ":" + col + " " + msg
                    + "\n源：" + canonical);
            }
        });
        var core = new aster.core.lowering.CoreLowering()
            .lowerModule(new AstBuilder().visitModule(parser.module()));
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(core);
            // 剥掉 origin：本测试只关心语义是否一致，位置本就随写法长度不同而不同。
            return json.replaceAll("\"origin\":\\{[^{}]*\\{[^{}]*\\}[^{}]*\\{[^{}]*\\}[^{}]*\\}",
                "\"origin\":null");
        } catch (Exception e) {
            throw new IllegalStateException("IR 序列化失败", e);
        }
    }

    @Test
    @DisplayName("可选 is 前缀：带与不带产出相同 IR（lexer 已吸收，无需 transformer 改写）")
    void optional_is_prefix_is_semantically_transparent() {
        // ★这条锁住「移除 IsComparatorTransformer 是安全的」这一前提。
        //   该 transformer 原本把 `x is at least y` 改写成 `x at least y`，理由是
        //   「让两种写法走同一条比较路径」。但 lexer 本就吸收可选 `is` 前缀
        //   （见本类 is_prefix_comparators_parse_on_bare_parser），改写因此是冗余的，
        //   唯一效果是**缩短行 3 个字符**、使 origin.col 偏离用户原文
        //   （ADR 0032 的 trace 锚点 / ADR 0037 的 OriginMap 都按列定位）。
        //   故 transformer 已从 en-US 的 preTranslationTransformers 链移除。
        //   ★若将来有人想把它加回去，本测试不会变红——变红的是
        //     OperatorColumnPreservationTest（列位被移动）。两者分工：
        //     这里守语义等价，那里守列位不变。
        Canonicalizer canon = new Canonicalizer(LexiconRegistry.getInstance().getDefault());
        for (String cmp : new String[]{
                "at least", "at most", "greater than", "less than", "more than", "under", "over"}) {
            assertSameIr(canon, "s is " + cmp + " 700", "s " + cmp + " 700");
        }
    }
}
