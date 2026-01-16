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

        RuntimeException exception = assertThrows(RuntimeException.class, () -> lex(input));
        assertTrue(exception.getMessage().contains("Invalid indentation"),
            "应该抛出 Invalid indentation 错误");
    }

    @Test
    void testInvalidIndentation_InconsistentDedent() {
        String input = "a\n  b\n c"; // dedent 到 1 space（不匹配之前的 0 或 2）

        RuntimeException exception = assertThrows(RuntimeException.class, () -> lex(input));
        assertTrue(exception.getMessage().contains("Inconsistent dedent"),
            "应该抛出 Inconsistent dedent 错误");
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
}
