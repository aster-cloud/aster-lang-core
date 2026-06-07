package aster.core.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void full_canonicalize_path_lowers_under_over_to_symbols() {
        // 互补验证：完整 canonicalize 链下 under/over/is at least 归一为符号，
        // 与裸 parser 路径殊途同归（两条路径都被支持，行为一致）。
        Canonicalizer canon = new Canonicalizer(LexiconRegistry.getInstance().getDefault());
        String out = canon.canonicalize(rule("s is under 700") + "\n" + rule("s is over 700"));
        assertTrue(out.contains("s < 700"), "is under → <, got:\n" + out);
        assertTrue(out.contains("s > 700"), "is over → >, got:\n" + out);
    }
}
