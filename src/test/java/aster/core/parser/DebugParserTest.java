package aster.core.parser;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

/**
 * Debug parser to understand what's happening
 */
class DebugParserTest {

    @Test
    void debugSimpleModule() {
        String input = """
            This module is app.

            To helloMessage produce Text:
              Return "Hello, world!".
            """;

        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // Fill buffer and print all tokens
        tokens.fill();
        System.out.println("Tokens:");
        for (Token t : tokens.getTokens()) {
            String name = lexer.getVocabulary().getSymbolicName(t.getType());
            if (t.getChannel() == Token.DEFAULT_CHANNEL) {
                System.out.println("  " + name + ": '" + t.getText() + "' (type=" + t.getType() + ")");
            }
        }

        // Reset for parsing
        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);

        // Enable detailed error messages
        parser.removeErrorListeners();
        parser.addErrorListener(new DiagnosticErrorListener(true));

        try {
            ParseTree tree = parser.module();
            System.out.println("\nParse tree:");
            System.out.println(tree.toStringTree(parser));
        } catch (Exception e) {
            System.err.println("\nParsing failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
