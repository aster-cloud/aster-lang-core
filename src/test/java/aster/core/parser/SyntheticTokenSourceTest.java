package aster.core.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词法器合成的 token（INDENT / DEDENT 等）必须带来源（token source + char stream）。
 *
 * <p>ANTLR 的单 token 补全恢复（{@code DefaultErrorStrategy.getMissingSymbol}）用当前 token 的
 * {@code getTokenSource().getInputStream()} 构造「缺失的 token」。源码在缺句末标点处直接结束时，
 * 当前 token 是文件末尾合成的 DEDENT；它没有来源，恢复过程抛 NullPointerException，
 * 调用方（aster-api 编译门禁）拿到的是「Cannot invoke TokenSource.getInputStream()」这类内部错误，
 * 而不是「缺少句末标点」的语法错误。
 */
class SyntheticTokenSourceTest {

    private static final String MISSING_END_AT_EOF =
        "Module m.\n\nRule r given n as Int, produce Int:\n  Return n";

    @Test
    void syntheticLayoutTokens_carryTheirSource() {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(MISSING_END_AT_EOF));
        List<Token> layout = new ArrayList<>();
        for (Token t = lexer.nextToken(); t.getType() != Token.EOF; t = lexer.nextToken()) {
            if (t.getType() == AsterParser.INDENT || t.getType() == AsterParser.DEDENT) {
                layout.add(t);
            }
        }
        assertFalse(layout.isEmpty(), "源码含缩进块，应产生 INDENT/DEDENT");
        for (Token t : layout) {
            assertNotNull(t.getTokenSource(), "合成 token 缺少 token source: " + t);
            assertNotNull(t.getInputStream(), "合成 token 缺少 char stream: " + t);
        }
    }

    @Test
    void missingStatementEndAtEndOfFile_isReportedAsSyntaxError_notNpe() {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(MISSING_END_AT_EOF));
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        List<String> errors = new ArrayList<>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                errors.add(msg);
            }
        });

        assertDoesNotThrow(parser::module);
        assertTrue(errors.stream().anyMatch(m -> m.startsWith("missing '.'")), "应报缺句末标点，实际: " + errors);
    }
}
