package aster.core.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：workflow retry 语法的 `max attempts` 让 lexer 把 max/attempts 提升为硬 token
 * （AsterLexer.g4 MAX/ATTEMPTS），但在标识符位置（DOT 成员名、变量名、参数、字段）应当
 * 软关键字——否则 stdlib `List.max(xs)` / `List.attempts(xs)` 的成员名被卡，生产实测
 * 报 "extraneous 'max'"。
 * <p>
 * 修复：把 MAX/ATTEMPTS 收进 structKeywordName 软关键字集（同 ADR 0019 G1 的
 * LET/IF/RETURN 范式），retryDirective 起点仍按 MAX/ATTEMPTS token 分派不受影响。
 * 这是 ADR 0024 poker 纯 CNL 重写（用 List.max/List.min 比牌力）的前置修复。
 */
class MaxAttemptsMemberNameTest {

    /** 走生产 canonicalize→lex→parse 路径（en-US）。返回是否无语法错误。 */
    private boolean parses(String src) {
        var canon = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), null);
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

    // ── 主修复：DOT 成员位置的 max/attempts（生产挂点） ──────────────────────

    @Test
    void listMaxAsMemberCall() {
        assertTrue(parses("Module a.b.\n\nRule f given xs as List, produce Int:\n  Return List.max(xs).\n"),
            "List.max(xs) 成员调用");
    }

    @Test
    void listMinAsMemberCall() {
        // min 从来不是 token，本就该通过——作 unaffected baseline 锁住
        assertTrue(parses("Module a.b.\n\nRule f given xs as List, produce Int:\n  Return List.min(xs).\n"),
            "List.min(xs) 成员调用（baseline）");
    }

    @Test
    void listAttemptsAsMemberCall() {
        assertTrue(parses("Module a.b.\n\nRule f given xs as List, produce Int:\n  Return List.attempts(xs).\n"),
            "List.attempts(xs) 成员调用");
    }

    @Test
    void chainedMaxMember() {
        assertTrue(parses("Module a.b.\n\nRule f given xs as List, produce Int:\n  Return List.max(xs) minus List.min(xs).\n"),
            "List.max minus List.min（poker span 模式）");
    }

    // ── 变量名 / 参数名位置 ────────────────────────────────────────────────

    @Test
    void maxAsParamAndVar() {
        assertTrue(parses("Module a.b.\n\nRule f given max as Int, produce Int:\n  Return max.\n"),
            "max 当参数名 + 引用");
        assertTrue(parses("Module a.b.\n\nRule f given x as Int, produce Int:\n  Let max be x.\n  Return max.\n"),
            "max 当变量名 + 引用");
    }

    @Test
    void attemptsAsParamAndVar() {
        assertTrue(parses("Module a.b.\n\nRule f given attempts as Int, produce Int:\n  Return attempts.\n"),
            "attempts 当参数名 + 引用");
    }

    // ── 字段名位置 ────────────────────────────────────────────────────────

    @Test
    void maxAttemptsAsFieldNames() {
        assertTrue(parses("Module a.b.\n\nDefine Box has max, attempts.\n"),
            "max/attempts 当字段名");
    }

    // 注：retry block 不回归的保障 = 全量套件里既有的 workflow/retry corpus 测试
    // （aster-lang-test runtime-retry/type-checker fixtures）。本修复只把 MAX/ATTEMPTS
    // 加进 structKeywordName 软关键字集，retryDirective: MAX ATTEMPTS COLON ... 规则与
    // lexer token 定义都未动，故 retry 解析不受影响——不在此处重复弱 helper 的 workflow 解析。
}
