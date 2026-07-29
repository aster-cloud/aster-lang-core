package aster.core.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天城文标识符在**生产词法器**上的支持（2026-07-29 审计修复）。
 *
 * <h2>为什么已有 DevanagariLexerTest 还要再加一个</h2>
 *
 * <p>{@code aster.core.lexer.DevanagariLexerTest} 测的是
 * {@code aster.core.lexer.Lexer}——那个类**零引用，是死代码**
 * （{@code grep -rl "import aster.core.lexer"} 在 src/main 下无结果）。
 * 生产路径走的是本包的 {@code AsterLexer}（ANTLR 由 AsterLexer.g4 生成，
 * 经 {@code AsterCustomLexer} 继承）。
 *
 * <p>结果是：ADR 0017 的天城文修复打在了错误的词法器上，测试一直绿，而
 * <b>生产引擎对天城文标识符完全不可用</b>。实测修复前 {@code राशि} 产生
 * 4 个 lexError、0 个 token，同一输入在 TS 引擎上得到正常 IDENT——
 * Hindi 是已发版特性，这是无条件坏的双引擎分歧。
 *
 * <p>本测试直接打 {@link AsterLexer}，确保修复钉在真正被执行的那条路径上。
 */
@DisplayName("生产 ANTLR 词法器的天城文支持")
class DevanagariProductionLexerTest {

    /** 词法结果：错误数 + token 文本列表。 */
    private record LexResult(int errors, List<String> tokens) {}

    private static LexResult lex(String src) {
        AsterLexer lexer = new AsterLexer(CharStreams.fromString(src));
        int[] errors = {0};
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object offending, int line,
                                    int col, String msg, RecognitionException e) {
                errors[0]++;
            }
        });
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        List<String> texts = new ArrayList<>();
        for (Token t : stream.getTokens()) {
            if (t.getType() != Token.EOF) {
                texts.add(t.getText());
            }
        }
        return new LexResult(errors[0], texts);
    }

    @Test
    @DisplayName("★天城文标识符可词法通过（修复前：4 个 lexError、0 token）")
    void devanagariIdentifierLexes() {
        LexResult r = lex("राशि");

        assertEquals(0, r.errors(),
            "天城文标识符必须无词法错误——生产 AsterLexer.g4 的 IdentContinue 与 IDENT "
                + "起始分支都要含 DevanagariChar");
        assertEquals(List.of("राशि"), r.tokens(),
            "必须整体成一个 token，不能在 matra/virama 组合记号处碎裂");
    }

    @Test
    @DisplayName("含 matra 与 virama 的多音节词不碎裂")
    void devanagariWithCombiningMarksStaysWhole() {
        // मॉड्यूल 含 matra ◌ॉ ◌ू 与 virama ◌्——POC 阶段正是在这里断开的
        LexResult r = lex("मॉड्यूल");

        assertEquals(0, r.errors());
        assertEquals(List.of("मॉड्यूल"), r.tokens(),
            "组合记号（NON_SPACING_MARK / COMBINING_SPACING_MARK）必须算作标识符续字符");
    }

    @Test
    @DisplayName("天城文数字可作标识符续字符")
    void devanagariDigitsAreIdentifierContinuation() {
        LexResult r = lex("मूल्य२");

        assertEquals(0, r.errors());
        assertEquals(List.of("मूल्य२"), r.tokens());
    }

    @Test
    @DisplayName("★danda「।」不得被吞进标识符——它是句末符，不是标识符字符")
    void dandaIsNotPartOfIdentifier() {
        LexResult r = lex("राशि।");

        assertTrue(r.tokens().contains("राशि"),
            "标识符必须在 danda 处结束；若 danda 被吞入标识符，Hindi 语句边界会消失");
        assertTrue(r.tokens().stream().noneMatch(t -> t.contains("।")),
            "任何 token 都不应包含 danda U+0964（与 TS 侧 lexer.ts 的排除逐字对齐）");
    }

    @Test
    @DisplayName("回归护栏：ASCII 与 CJK 标识符不受影响")
    void asciiAndCjkUnaffected() {
        assertEquals(List.of("amount"), lex("amount").tokens());
        assertEquals(0, lex("amount").errors());

        assertEquals(List.of("金额"), lex("金额").tokens());
        assertEquals(0, lex("金额").errors());
    }
}
