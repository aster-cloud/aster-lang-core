package aster.core.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lexer 单元测试
 * <p>
 * 验证词法分析器的各项功能是否正确。
 */
class LexerTest {

    // ============================================================
    // 基础 Token 识别测试
    // ============================================================

    @Test
    void testEmptyInput() {
        List<Token> tokens = Lexer.lex("");
        assertEquals(1, tokens.size());
        assertEquals(TokenKind.EOF, tokens.get(0).kind());
    }

    @Test
    void testPunctuation() {
        List<Token> tokens = Lexer.lex(". : , ( ) [ ]");
        assertEquals(8, tokens.size()); // 7 punctuation + EOF
        assertEquals(TokenKind.DOT, tokens.get(0).kind());
        assertEquals(TokenKind.COLON, tokens.get(1).kind());
        assertEquals(TokenKind.COMMA, tokens.get(2).kind());
        assertEquals(TokenKind.LPAREN, tokens.get(3).kind());
        assertEquals(TokenKind.RPAREN, tokens.get(4).kind());
        assertEquals(TokenKind.LBRACKET, tokens.get(5).kind());
        assertEquals(TokenKind.RBRACKET, tokens.get(6).kind());
    }

    @Test
    void testOperators() {
        List<Token> tokens = Lexer.lex("+ - * / = < > <= >= !=");
        assertEquals(11, tokens.size()); // 10 operators + EOF
        assertEquals(TokenKind.PLUS, tokens.get(0).kind());
        assertEquals(TokenKind.MINUS, tokens.get(1).kind());
        assertEquals(TokenKind.STAR, tokens.get(2).kind());
        assertEquals(TokenKind.SLASH, tokens.get(3).kind());
        assertEquals(TokenKind.EQUALS, tokens.get(4).kind());
        assertEquals(TokenKind.LT, tokens.get(5).kind());
        assertEquals(TokenKind.GT, tokens.get(6).kind());
        assertEquals(TokenKind.LTE, tokens.get(7).kind());
        assertEquals(TokenKind.GTE, tokens.get(8).kind());
        assertEquals(TokenKind.NEQ, tokens.get(9).kind());
    }

    // ============================================================
    // 字符串字面量测试
    // ============================================================

    @Test
    void testStringLiteral_Simple() {
        List<Token> tokens = Lexer.lex("\"hello\"");
        assertEquals(2, tokens.size()); // STRING + EOF
        assertEquals(TokenKind.STRING, tokens.get(0).kind());
        assertEquals("hello", tokens.get(0).value());
    }

    @Test
    void testStringLiteral_WithEscape() {
        List<Token> tokens = Lexer.lex("\"hello \\\"world\\\"\"");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.STRING, tokens.get(0).kind());
        assertEquals("hello \"world\"", tokens.get(0).value());
    }

    @Test
    void testStringLiteral_WithCommonEscapes() {
        List<Token> tokens = Lexer.lex("\"line1\\nline2\\t\"");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.STRING, tokens.get(0).kind());
        assertEquals("line1\nline2\t", tokens.get(0).value());
    }

    @Test
    void testStringLiteral_WithUnicodeEscape() {
        List<Token> tokens = Lexer.lex("\"\\u0041\"");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.STRING, tokens.get(0).kind());
        assertEquals("A", tokens.get(0).value());
    }

    @Test
    void testStringLiteral_Empty() {
        List<Token> tokens = Lexer.lex("\"\"");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.STRING, tokens.get(0).kind());
        assertEquals("", tokens.get(0).value());
    }

    @Test
    void testStringLiteral_InvalidEscape() {
        assertThrows(LexerException.class, () -> Lexer.lex("\"\\z\""));
    }

    @Test
    void testStringLiteral_Unterminated() {
        assertThrows(LexerException.class, () -> Lexer.lex("\"unterminated"));
    }

    // ============================================================
    // 数字字面量测试
    // ============================================================

    @Test
    void testIntLiteral() {
        List<Token> tokens = Lexer.lex("42");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.INT, tokens.get(0).kind());
        assertEquals(42, tokens.get(0).value());
    }

    @Test
    void testFloatLiteral() {
        List<Token> tokens = Lexer.lex("3.14");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.FLOAT, tokens.get(0).kind());
        assertEquals(3.14, tokens.get(0).value());
    }

    @Test
    void testLongLiteral() {
        List<Token> tokens = Lexer.lex("123L");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.LONG, tokens.get(0).kind());
        assertEquals(123L, tokens.get(0).value());
    }

    // ============================================================
    // 标识符和关键字测试
    // ============================================================

    @Test
    void testIdentifier() {
        List<Token> tokens = Lexer.lex("myVar");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.IDENT, tokens.get(0).kind());
        assertEquals("myVar", tokens.get(0).value());
    }

    @Test
    void testTypeIdentifier() {
        List<Token> tokens = Lexer.lex("MyType");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.TYPE_IDENT, tokens.get(0).kind());
        assertEquals("MyType", tokens.get(0).value());
    }

    @Test
    void testBoolLiteral_True() {
        List<Token> tokens = Lexer.lex("true");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.BOOL, tokens.get(0).kind());
        assertEquals(true, tokens.get(0).value());
    }

    @Test
    void testBoolLiteral_False() {
        List<Token> tokens = Lexer.lex("false");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.BOOL, tokens.get(0).kind());
        assertEquals(false, tokens.get(0).value());
    }

    @Test
    void testNullLiteral() {
        List<Token> tokens = Lexer.lex("null");
        assertEquals(2, tokens.size());
        assertEquals(TokenKind.NULL, tokens.get(0).kind());
        assertNull(tokens.get(0).value());
    }

    // ============================================================
    // 缩进测试
    // ============================================================

    @Test
    void testIndent() {
        String input = "line1\n  line2";
        List<Token> tokens = Lexer.lex(input);

        // line1, NEWLINE, INDENT, line2, DEDENT, EOF
        assertEquals(TokenKind.IDENT, tokens.get(0).kind()); // line1
        assertEquals(TokenKind.NEWLINE, tokens.get(1).kind());
        assertEquals(TokenKind.INDENT, tokens.get(2).kind());
        assertEquals(TokenKind.IDENT, tokens.get(3).kind()); // line2
        assertEquals(TokenKind.DEDENT, tokens.get(4).kind());
        assertEquals(TokenKind.EOF, tokens.get(5).kind());
    }

    @Test
    void testDedent() {
        String input = "line1\n  line2\nline3";
        List<Token> tokens = Lexer.lex(input);

        // line1, NEWLINE, INDENT, line2, NEWLINE, DEDENT, line3, EOF
        assertEquals(TokenKind.IDENT, tokens.get(0).kind()); // line1
        assertEquals(TokenKind.NEWLINE, tokens.get(1).kind());
        assertEquals(TokenKind.INDENT, tokens.get(2).kind());
        assertEquals(TokenKind.IDENT, tokens.get(3).kind()); // line2
        assertEquals(TokenKind.NEWLINE, tokens.get(4).kind());
        assertEquals(TokenKind.DEDENT, tokens.get(5).kind());
        assertEquals(TokenKind.IDENT, tokens.get(6).kind()); // line3
    }

    @Test
    void testMultipleDedent() {
        String input = "line1\n  line2\n    line3\nline4";
        List<Token> tokens = Lexer.lex(input);

        // 应该有 2 个 DEDENT（从 4 spaces → 0 spaces）
        long dedentCount = tokens.stream()
            .filter(t -> t.kind() == TokenKind.DEDENT)
            .count();
        assertEquals(2, dedentCount);
    }

    @Test
    void testInvalidIndentation_OddSpaces() {
        String input = "line1\n line2"; // 1 space (奇数)
        assertThrows(LexerException.class, () -> Lexer.lex(input));
    }

    @Test
    void testInconsistentDedent() {
        String input = "line1\n    line2\n  line3"; // 4 spaces → 2 spaces (但栈中没有 2)
        assertThrows(LexerException.class, () -> Lexer.lex(input));
    }

    // ============================================================
    // 注释测试
    // ============================================================

    @Test
    void testComment_Hash() {
        String input = "# comment\ncode";
        List<Token> tokens = Lexer.lex(input);

        Token commentToken = tokens.stream()
            .filter(Token::isComment)
            .findFirst()
            .orElseThrow();

        assertEquals(TokenKind.COMMENT, commentToken.kind());
        assertTrue(commentToken.isTrivia());

        CommentValue cv = commentToken.getCommentValue();
        assertEquals("# comment", cv.raw());
        assertEquals("comment", cv.text());
        assertEquals("standalone", cv.trivia());
    }

    @Test
    void testComment_DoubleSlash() {
        String input = "// comment\ncode";
        List<Token> tokens = Lexer.lex(input);

        Token commentToken = tokens.stream()
            .filter(Token::isComment)
            .findFirst()
            .orElseThrow();

        CommentValue cv = commentToken.getCommentValue();
        assertEquals("// comment", cv.raw());
        assertEquals("comment", cv.text());
    }

    @Test
    void testComment_Inline() {
        String input = "code // inline comment";
        List<Token> tokens = Lexer.lex(input);

        Token commentToken = tokens.stream()
            .filter(Token::isComment)
            .findFirst()
            .orElseThrow();

        CommentValue cv = commentToken.getCommentValue();
        assertEquals("inline", cv.trivia());
    }

    // ============================================================
    // 综合测试
    // ============================================================

    @Test
    void testComplexExpression() {
        String input = "x + 1 * 2";
        List<Token> tokens = Lexer.lex(input);

        assertEquals(TokenKind.IDENT, tokens.get(0).kind());
        assertEquals(TokenKind.PLUS, tokens.get(1).kind());
        assertEquals(TokenKind.INT, tokens.get(2).kind());
        assertEquals(TokenKind.STAR, tokens.get(3).kind());
        assertEquals(TokenKind.INT, tokens.get(4).kind());
        assertEquals(TokenKind.EOF, tokens.get(5).kind());
    }

    @Test
    void testFunctionDefinition() {
        String input = "To greet:\n  Return \"Hello\".";
        List<Token> tokens = Lexer.lex(input);

        assertTrue(tokens.size() > 0);
        // "To" 和 "Return" 都是大写开头，会被识别为 TYPE_IDENT
        assertEquals(TokenKind.TYPE_IDENT, tokens.get(0).kind()); // To
        assertEquals("To", tokens.get(0).value());
    }

    @Test
    void testPositionTracking() {
        String input = "line1\nline2";
        List<Token> tokens = Lexer.lex(input);

        Token firstToken = tokens.get(0);
        assertEquals(1, firstToken.start().line());
        assertEquals(1, firstToken.start().col());

        // Find token on line 2
        Token line2Token = tokens.stream()
            .filter(t -> t.start().line() == 2)
            .findFirst()
            .orElseThrow();
        assertEquals(2, line2Token.start().line());
    }

    @Test
    void testUTF8BOM() {
        String input = "\uFEFFcode";
        List<Token> tokens = Lexer.lex(input);

        // BOM should be skipped
        assertEquals(TokenKind.IDENT, tokens.get(0).kind());
        assertEquals("code", tokens.get(0).value());
    }

    @Test
    void testBlankLines() {
        String input = "line1\n\nline2";
        List<Token> tokens = Lexer.lex(input);

        // Blank lines generate NEWLINE tokens (与 TypeScript 行为一致)
        long newlineCount = tokens.stream()
            .filter(t -> t.kind() == TokenKind.NEWLINE)
            .count();
        assertEquals(2, newlineCount); // Two newlines: after line1 and the blank line
    }
}
