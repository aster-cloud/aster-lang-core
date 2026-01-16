package aster.core.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

class Phase35DebugTest {

    @Test
    void debugMaybeType() {
        String input = """
            To fromMaybe with x: Text?, d: Text, produce Text:
              Return d.
            """;

        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        tokens.fill();

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

        System.out.println("Parse tree:");
        System.out.println(moduleCtx.toStringTree(parser));

        AstBuilder builder = new AstBuilder();
        try {
            aster.core.ast.Module module = builder.visitModule(moduleCtx);
            if (module == null) {
                System.err.println("visitModule returned null!");
            } else {
                System.out.println("\nModule: " + module.name());
                System.out.println("Decls: " + module.decls().size());
            }
        } catch (NullPointerException e) {
            System.err.println("NPE during AST construction:");
            e.printStackTrace();
            throw e;
        } catch (Exception e) {
            System.err.println("AST construction failed:");
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    void debugMatchStatement() {
        String input = """
            To check with x: Text?, produce Text:
              Match x:
                When null, Return "empty".
                When v, Return v.
            """;

        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        tokens.fill();
        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();

        AsterParser.ModuleContext moduleCtx = parser.module();

        System.out.println("Parse tree:");
        System.out.println(moduleCtx.toStringTree(parser));

        try {
            AstBuilder builder = new AstBuilder();
            aster.core.ast.Module module = builder.visitModule(moduleCtx);
            System.out.println("\nModule parsed successfully");
        } catch (Exception e) {
            System.err.println("AST construction failed:");
            e.printStackTrace();
        }
    }
}
