package aster.core.parser;

import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AsterCustomLexer 的缩进处理功能
 */
class AsterCustomLexerTest {

    /**
     * 辅助方法：将输入字符串词法分析为 token 列表（只收集默认通道的 token）
     */
    private List<Token> lex(String input) {
        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        List<Token> tokens = new ArrayList<>();

        Token token;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            // 只收集默认通道的 token（跳过 HIDDEN 通道的注释等）
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                tokens.add(token);
            }
        }
        tokens.add(token); // 添加 EOF token

        return tokens;
    }

    /**
     * 辅助方法：获取 token 类型名称（用于调试）
     */
    private String getTokenName(int type) {
        if (type == AsterParser.INDENT) return "INDENT";
        if (type == AsterParser.DEDENT) return "DEDENT";
        if (type == AsterLexer.NEWLINE) return "NEWLINE";
        if (type == AsterLexer.IDENT) return "IDENT";
        if (type == AsterLexer.TYPE_IDENT) return "TYPE_IDENT";
        if (type == AsterLexer.INT_LITERAL) return "INT_LITERAL";
        if (type == AsterLexer.DOT) return "DOT";
        if (type == AsterLexer.COLON) return "COLON";
        if (type == AsterLexer.COMMA) return "COMMA";
        if (type == AsterLexer.LPAREN) return "LPAREN";
        if (type == AsterLexer.RPAREN) return "RPAREN";
        if (type == AsterLexer.PLUS) return "PLUS";
        if (type == AsterLexer.MINUS) return "MINUS";
        if (type == AsterLexer.EQUALS) return "EQUALS";
        if (type == Token.EOF) return "EOF";
        return "UNKNOWN(" + type + ")";
    }

    @Test
    void testBasicToken() {
        String input = "hello";
        List<Token> tokens = lex(input);

        assertEquals(2, tokens.size(), "应该有 2 个 token (IDENT + EOF)");
        assertEquals(AsterLexer.IDENT, tokens.get(0).getType());
        assertEquals("hello", tokens.get(0).getText());
        assertEquals(Token.EOF, tokens.get(1).getType());
    }

    @Test
    void testIndent() {
        String input = "line1\n  line2";
        List<Token> tokens = lex(input);

        // 打印 token 序列用于调试
        System.out.println("Token sequence:");
        for (Token t : tokens) {
            System.out.println("  " + getTokenName(t.getType()) + ": " + t.getText());
        }

        assertEquals(AsterLexer.IDENT, tokens.get(0).getType(), "第一个应该是 IDENT (line1)");
        assertEquals(AsterLexer.NEWLINE, tokens.get(1).getType(), "第二个应该是 NEWLINE");
        assertEquals(AsterParser.INDENT, tokens.get(2).getType(), "第三个应该是 INDENT");
        assertEquals(AsterLexer.IDENT, tokens.get(3).getType(), "第四个应该是 IDENT (line2)");
        assertEquals(AsterParser.DEDENT, tokens.get(4).getType(), "第五个应该是 DEDENT (EOF 前)");
        assertEquals(Token.EOF, tokens.get(5).getType(), "最后应该是 EOF");
    }

    @Test
    void testMultipleIndents() {
        String input = "a\n  b\n    c";
        List<Token> tokens = lex(input);

        assertEquals(AsterLexer.IDENT, tokens.get(0).getType()); // a
        assertEquals(AsterLexer.NEWLINE, tokens.get(1).getType());
        assertEquals(AsterParser.INDENT, tokens.get(2).getType()); // indent level 1
        assertEquals(AsterLexer.IDENT, tokens.get(3).getType()); // b
        assertEquals(AsterLexer.NEWLINE, tokens.get(4).getType());
        assertEquals(AsterParser.INDENT, tokens.get(5).getType()); // indent level 2
        assertEquals(AsterLexer.IDENT, tokens.get(6).getType()); // c
        assertEquals(AsterParser.DEDENT, tokens.get(7).getType()); // dedent level 2 -> 1
        assertEquals(AsterParser.DEDENT, tokens.get(8).getType()); // dedent level 1 -> 0
        assertEquals(Token.EOF, tokens.get(9).getType());
    }

    @Test
    void testDedent() {
        String input = "a\n  b\nc";
        List<Token> tokens = lex(input);

        assertEquals(AsterLexer.IDENT, tokens.get(0).getType()); // a
        assertEquals(AsterLexer.NEWLINE, tokens.get(1).getType());
        assertEquals(AsterParser.INDENT, tokens.get(2).getType());
        assertEquals(AsterLexer.IDENT, tokens.get(3).getType()); // b
        assertEquals(AsterLexer.NEWLINE, tokens.get(4).getType());
        assertEquals(AsterParser.DEDENT, tokens.get(5).getType());
        assertEquals(AsterLexer.IDENT, tokens.get(6).getType()); // c
        assertEquals(Token.EOF, tokens.get(7).getType());
    }

    @Test
    void testBlankLinesIgnored() {
        String input = "a\n\n  b";
        List<Token> tokens = lex(input);

        // 空行应该被跳过，不影响缩进检测
        assertEquals(AsterLexer.IDENT, tokens.get(0).getType()); // a
        assertEquals(AsterLexer.NEWLINE, tokens.get(1).getType());
        assertEquals(AsterLexer.NEWLINE, tokens.get(2).getType()); // 空行的 NEWLINE
        assertEquals(AsterParser.INDENT, tokens.get(3).getType());
        assertEquals(AsterLexer.IDENT, tokens.get(4).getType()); // b
    }

    @Test
    void testInvalidIndentation_OddSpaces() {
        String input = "a\n b"; // 1 space (奇数)
        List<Token> tokens = lex(input);

        // 不再抛出异常，而是生成 INVALID_TYPE 错误 token（文本格式: <ERROR: ...>）
        boolean hasErrorToken = tokens.stream()
            .anyMatch(t -> t.getType() == Token.INVALID_TYPE
                && t.getText().contains("Invalid indentation"));
        assertTrue(hasErrorToken, "应该包含 Invalid indentation 错误 token");
    }

    @Test
    void testInvalidIndentation_InconsistentDedent() {
        String input = "a\n  b\n c"; // dedent 到 1 space（不匹配之前的 0 或 2）

        // 收集所有 token（包括默认通道外的）以便检查错误 token
        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        List<Token> allTokens = new ArrayList<>();
        Token token;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            allTokens.add(token);
        }

        // 不再抛出异常，而是生成 INVALID_TYPE 错误 token（文本格式: <ERROR: ...>）
        boolean hasErrorToken = allTokens.stream()
            .anyMatch(t -> t.getType() == Token.INVALID_TYPE
                && t.getText().contains("Inconsistent dedent"));
        assertTrue(hasErrorToken, "应该包含 Inconsistent dedent 错误 token");
    }

    @Test
    void testComments() {
        String input = "a # comment\n  b";
        List<Token> tokens = lex(input);

        // 注释应该在 HIDDEN 通道，通过 nextToken() 时会被跳过
        // 但它们仍然存在，只是不在默认通道
        assertEquals(AsterLexer.IDENT, tokens.get(0).getType()); // a
        assertEquals(AsterLexer.NEWLINE, tokens.get(1).getType());
        assertEquals(AsterParser.INDENT, tokens.get(2).getType());
        assertEquals(AsterLexer.IDENT, tokens.get(3).getType()); // b
    }

    @Test
    void testStringLiteral() {
        String input = "\"Hello, world!\"";
        List<Token> tokens = lex(input);

        assertEquals(2, tokens.size());
        assertEquals(AsterLexer.STRING_LITERAL, tokens.get(0).getType());
        assertEquals("\"Hello, world!\"", tokens.get(0).getText());
    }

    @Test
    void testNumbers() {
        String input = "42 3.14 100L";
        List<Token> tokens = lex(input);

        assertEquals(AsterLexer.INT_LITERAL, tokens.get(0).getType());
        assertEquals("42", tokens.get(0).getText());

        assertEquals(AsterLexer.FLOAT_LITERAL, tokens.get(1).getType());
        assertEquals("3.14", tokens.get(1).getText());

        assertEquals(AsterLexer.LONG_LITERAL, tokens.get(2).getType());
        assertEquals("100L", tokens.get(2).getText());
    }

    @Test
    void testBooleanAndNull() {
        String input = "true false null";
        List<Token> tokens = lex(input);

        assertEquals(AsterLexer.BOOL_LITERAL, tokens.get(0).getType());
        assertEquals("true", tokens.get(0).getText());

        assertEquals(AsterLexer.BOOL_LITERAL, tokens.get(1).getType());
        assertEquals("false", tokens.get(1).getText());

        assertEquals(AsterLexer.NULL_LITERAL, tokens.get(2).getType());
        assertEquals("null", tokens.get(2).getText());
    }

    @Test
    void testTypeIdentVsIdent() {
        String input = "Hello world";
        List<Token> tokens = lex(input);

        assertEquals(AsterLexer.TYPE_IDENT, tokens.get(0).getType(), "Uppercase 开头应该是 TYPE_IDENT");
        assertEquals("Hello", tokens.get(0).getText());

        assertEquals(AsterLexer.IDENT, tokens.get(1).getType(), "lowercase 开头应该是 IDENT");
        assertEquals("world", tokens.get(1).getText());
    }

    @Test
    void testOperators() {
        String input = "+ - * / = < > <= >= !=";
        List<Token> tokens = lex(input);

        assertEquals(AsterLexer.PLUS, tokens.get(0).getType());
        assertEquals(AsterLexer.MINUS, tokens.get(1).getType());
        assertEquals(AsterLexer.STAR, tokens.get(2).getType());
        assertEquals(AsterLexer.SLASH, tokens.get(3).getType());
        assertEquals(AsterLexer.EQUALS, tokens.get(4).getType());
        assertEquals(AsterLexer.LT, tokens.get(5).getType());
        assertEquals(AsterLexer.GT, tokens.get(6).getType());
        assertEquals(AsterLexer.LTE, tokens.get(7).getType());
        assertEquals(AsterLexer.GTE, tokens.get(8).getType());
        assertEquals(AsterLexer.NEQ, tokens.get(9).getType());
    }

    @Test
    void testPunctuation() {
        String input = ". : , ( ) [ ]";
        List<Token> tokens = lex(input);

        assertEquals(AsterLexer.DOT, tokens.get(0).getType());
        assertEquals(AsterLexer.COLON, tokens.get(1).getType());
        assertEquals(AsterLexer.COMMA, tokens.get(2).getType());
        assertEquals(AsterLexer.LPAREN, tokens.get(3).getType());
        assertEquals(AsterLexer.RPAREN, tokens.get(4).getType());
        assertEquals(AsterLexer.LBRACKET, tokens.get(5).getType());
        assertEquals(AsterLexer.RBRACKET, tokens.get(6).getType());
    }

    // ============================================================
    // 从已删除的手写 aster.core.lexer.Lexer 测试迁移而来（issue #153）
    // ============================================================
    //
    // ★那份 Lexer 在 src/main 零引用，但 LexerTest / DevanagariLexerTest 一直打在它
    //   身上形成假覆盖，且它与生产词法器已实际分叉（`//` 注释、CRLF 空行）。下列测试把
    //   仍有意义的行为钉在**真正被执行**的 AsterCustomLexer 上，并把两处分叉点锁成
    //   生产语义，防止再有人按死代码的行为去「修」生产。

    /** 收集全部通道的 token（含 HIDDEN 注释），并统计词法错误数。 */
    private record LexAll(List<Token> tokens, int errors) {}

    private LexAll lexAll(String input) {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(input));
        int[] errors = {0};
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object offending, int line,
                                    int col, String msg, RecognitionException e) {
                errors[0]++;
            }
        });
        List<Token> tokens = new ArrayList<>();
        Token token;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            tokens.add(token);
        }
        return new LexAll(tokens, errors[0]);
    }

    @Test
    void testEmptyInputYieldsOnlyEof() {
        List<Token> tokens = lex("");
        assertEquals(1, tokens.size());
        assertEquals(Token.EOF, tokens.get(0).getType());
    }

    @Test
    void testStringLiteralWithEscapedQuoteStaysOneToken() {
        // 转义引号不得提前终止字面量（解转义由 AstBuilder/StringEscapes 负责，
        // 见 AstBuilderTest.testStringLiteralEscapesInAst）
        List<Token> tokens = lex("\"hello \\\"world\\\"\"");
        assertEquals(2, tokens.size(), "STRING_LITERAL + EOF");
        assertEquals(AsterLexer.STRING_LITERAL, tokens.get(0).getType());
        assertEquals("\"hello \\\"world\\\"\"", tokens.get(0).getText());
    }

    @Test
    void testUnterminatedStringReportsLexError_notStringToken() {
        LexAll r = lexAll("\"unterminated");
        assertTrue(r.errors() > 0, "未闭合字符串必须产生词法错误");
        assertTrue(r.tokens().stream().noneMatch(t -> t.getType() == AsterLexer.STRING_LITERAL),
            "未闭合字符串不得被当成完整 STRING_LITERAL；实际 tokens=" + r.tokens());
    }

    @Test
    void testDoubleSlashIsIntegerDivision_notComment() {
        // ★分叉点 1：死 Lexer 把 `//` 当注释；生产文法只有 `#` 注释，`//` 是整除运算符。
        List<Token> tokens = lex("a // b");
        assertEquals(AsterLexer.IDENT, tokens.get(0).getType());
        assertEquals(AsterLexer.INTEGER_DIVIDED_BY_WORD, tokens.get(1).getType(),
            "`//` 必须是整除运算符而非注释；实际 tokens=" + tokens);
        assertEquals(AsterLexer.IDENT, tokens.get(2).getType());
        assertEquals(Token.EOF, tokens.get(3).getType());
    }

    @Test
    void testHashCommentGoesToHiddenChannel() {
        LexAll r = lexAll("a # note\nb");
        Token comment = r.tokens().stream()
            .filter(t -> t.getType() == AsterLexer.COMMENT)
            .findFirst()
            .orElseThrow(() -> new AssertionError("应有 COMMENT token；实际 " + r.tokens()));
        assertEquals(Token.HIDDEN_CHANNEL, comment.getChannel(), "注释必须在 HIDDEN 通道");
        assertEquals("# note", comment.getText());
        // 默认通道上注释不可见，b 紧跟 NEWLINE
        List<Token> visible = lex("a # note\nb");
        assertEquals(AsterLexer.NEWLINE, visible.get(1).getType());
        assertEquals(AsterLexer.IDENT, visible.get(2).getType());
    }

    @Test
    void testCrlfBlankLineDoesNotEmitSpuriousDedent() {
        // ★分叉点 2：死 Lexer 的空行跳过只认 '\n'，CRLF 空行会产出伪 DEDENT 拆块；
        //   生产 handleIndentation 对 '\r' 同样视为空行。
        List<Token> tokens = lex("a\r\n  b\r\n\r\n  c");
        long dedents = tokens.stream().filter(t -> t.getType() == AsterParser.DEDENT).count();
        assertEquals(1, dedents, "只应有 EOF 前的一个 DEDENT；实际 tokens=" + tokens);
        // c 仍在缩进块内：其前一个非 NEWLINE token 不是 DEDENT
        int cIdx = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if ("c".equals(tokens.get(i).getText())) cIdx = i;
        }
        assertTrue(cIdx > 0, "应有 token c");
        assertEquals(AsterLexer.NEWLINE, tokens.get(cIdx - 1).getType());
        assertEquals(AsterLexer.NEWLINE, tokens.get(cIdx - 2).getType());
        assertEquals(AsterParser.DEDENT, tokens.get(cIdx + 1).getType());
    }

    @Test
    void testTokenPositionsTrackLines() {
        List<Token> tokens = lex("line1\nline2");
        assertEquals(1, tokens.get(0).getLine());
        assertEquals(0, tokens.get(0).getCharPositionInLine());
        Token second = tokens.stream()
            .filter(t -> "line2".equals(t.getText()))
            .findFirst()
            .orElseThrow();
        assertEquals(2, second.getLine());
        assertEquals(0, second.getCharPositionInLine());
    }
}
