package aster.core.parser;

import aster.core.ast.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

class MatchDebugTest {

    @Test
    void debugMatchCases() {
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

        Decl.Func func = (Decl.Func) module.decls().get(0);
        Stmt.Match matchStmt = (Stmt.Match) func.body().statements().get(0);

        System.out.println("\nMatch cases count: " + matchStmt.cases().size());
        for (int i = 0; i < matchStmt.cases().size(); i++) {
            Stmt.Case c = matchStmt.cases().get(i);
            String bodyType = c.body() instanceof Stmt.Return ? "Return" : c.body() instanceof Block ? "Block" : "Unknown";
            System.out.println("  Case " + i + ": pattern=" + c.pattern().kind() + ", body=" + bodyType);
        }
    }
}
