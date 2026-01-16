package aster.core.parser;

import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 Lexer 的关键字识别
 */
class AsterLexerKeywordTest {

    private List<Token> lex(String input) {
        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        List<Token> tokens = new ArrayList<>();

        Token token;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                tokens.add(token);
            }
        }
        tokens.add(token); // EOF

        return tokens;
    }

    @Test
    void testModuleHeaderTokens() {
        String input = "This module is app.";
        List<Token> tokens = lex(input);

        System.out.println("Tokens for: " + input);
        for (Token t : tokens) {
            String name = AsterLexer.VOCABULARY.getSymbolicName(t.getType());
            System.out.println("  " + name + ": '" + t.getText() + "'");
        }

        assertEquals(AsterLexer.THIS, tokens.get(0).getType(), "应该是 THIS keyword");
        assertEquals(AsterLexer.MODULE, tokens.get(1).getType(), "应该是 MODULE keyword");
        assertEquals(AsterLexer.IS, tokens.get(2).getType(), "应该是 IS keyword");
        assertEquals(AsterLexer.IDENT, tokens.get(3).getType(), "应该是 IDENT (app)");
        assertEquals(AsterLexer.DOT, tokens.get(4).getType(), "应该是 DOT");
    }

    @Test
    void testFunctionDeclTokens() {
        String input = "To helloMessage produce Text:";
        List<Token> tokens = lex(input);

        System.out.println("Tokens for: " + input);
        for (Token t : tokens) {
            String name = AsterLexer.VOCABULARY.getSymbolicName(t.getType());
            System.out.println("  " + name + ": '" + t.getText() + "'");
        }

        assertEquals(AsterLexer.TO, tokens.get(0).getType(), "应该是 TO keyword");
        assertEquals(AsterLexer.IDENT, tokens.get(1).getType(), "应该是 IDENT (helloMessage)");
        assertEquals(AsterLexer.PRODUCE, tokens.get(2).getType(), "应该是 PRODUCE keyword");
        assertEquals(AsterLexer.TYPE_IDENT, tokens.get(3).getType(), "应该是 TYPE_IDENT (Text)");
        assertEquals(AsterLexer.COLON, tokens.get(4).getType(), "应该是 COLON");
    }
}
