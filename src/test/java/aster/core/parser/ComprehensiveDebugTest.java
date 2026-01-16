package aster.core.parser;

import aster.core.ast.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

class ComprehensiveDebugTest {

    @Test
    void debugComprehensive() {
        String input = """
            This module is demo.lambdamatchmaybe.

            To fromMaybe with x: Text? and d: Text, produce Text:
              Let f be function with x: Text?, produce Text:
                Match x:
                  When null, Return d.
                  When v, Return v.
              Return f(x).
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

        AstBuilder builder = new AstBuilder();
        aster.core.ast.Module module = builder.visitModule(moduleCtx);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        System.out.println("\nFunction body statements: " + func.body().statements().size());
        for (int i = 0; i < func.body().statements().size(); i++) {
            Stmt stmt = func.body().statements().get(i);
            System.out.println("  Statement " + i + ": " + stmt.kind());
        }
    }
}
