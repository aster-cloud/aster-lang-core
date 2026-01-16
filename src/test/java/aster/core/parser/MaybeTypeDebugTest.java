package aster.core.parser;

import aster.core.ast.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

class MaybeTypeDebugTest {

    @Test
    void testWithSpaces() {
        String input = """
            To fromMaybe with x: Text ? , d: Text, produce Text:
              Return d.
            """;

        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        tokens.fill();

        System.out.println("=== Test with spaces around ? ===");
        System.out.println("Tokens:");
        for (org.antlr.v4.runtime.Token t : tokens.getTokens()) {
            if (t.getChannel() == org.antlr.v4.runtime.Token.DEFAULT_CHANNEL) {
                String name = lexer.getVocabulary().getSymbolicName(t.getType());
                System.out.println("  " + (name != null ? name : "type" + t.getType()) + ": '" + t.getText().replace("\n", "\\n") + "'");
            }
        }

        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();

        AsterParser.ModuleContext moduleCtx = parser.module();

        System.out.println("\nParse tree:");
        System.out.println(moduleCtx.toStringTree(parser));

        AstBuilder builder = new AstBuilder();
        aster.core.ast.Module module = builder.visitModule(moduleCtx);
        System.out.println("\nModule: " + (module != null ? module.name() : "null"));
    }

    @Test
    void testWithoutSpaces() {
        String input = """
            To fromMaybe with x: Text?, d: Text, produce Text:
              Return d.
            """;

        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        tokens.fill();

        System.out.println("=== Test without spaces around ? ===");
        System.out.println("Tokens:");
        for (org.antlr.v4.runtime.Token t : tokens.getTokens()) {
            if (t.getChannel() == org.antlr.v4.runtime.Token.DEFAULT_CHANNEL) {
                String name = lexer.getVocabulary().getSymbolicName(t.getType());
                System.out.println("  " + (name != null ? name : "type" + t.getType()) + ": '" + t.getText().replace("\n", "\\n") + "'");
            }
        }

        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();

        AsterParser.ModuleContext moduleCtx = parser.module();

        System.out.println("\nParse tree:");
        System.out.println(moduleCtx.toStringTree(parser));

        AstBuilder builder = new AstBuilder();
        aster.core.ast.Module module = builder.visitModule(moduleCtx);
        System.out.println("\nModule: " + (module != null ? module.name() : "null"));
    }
}
