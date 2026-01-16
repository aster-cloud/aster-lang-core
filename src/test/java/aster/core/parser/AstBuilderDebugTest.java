package aster.core.parser;

import aster.core.ast.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

class AstBuilderDebugTest {

    @Test
    void debugLetStatement() {
        String input = """
            To test produce Int:
              Let x be 42.
              Return x.
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
        aster.core.ast.Module module = builder.visitModule(moduleCtx);

        System.out.println("\nModule: " + module.name());
        System.out.println("Decls: " + module.decls().size());

        Decl.Func func = (Decl.Func) module.decls().get(0);
        System.out.println("Function: " + func.name());
        System.out.println("Body statements: " + func.body().statements().size());

        for (int i = 0; i < func.body().statements().size(); i++) {
            Stmt stmt = func.body().statements().get(i);
            System.out.println("  Statement " + i + ": " + stmt.kind());
        }
    }

    @Test
    void debugBinaryExpression() {
        String input = """
            To calc produce Int:
              Return 1 + 2 * 3.
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
        Stmt.Return returnStmt = (Stmt.Return) func.body().statements().get(0);

        System.out.println("\nExpression type: " + returnStmt.expr().kind());
        if (returnStmt.expr() instanceof Expr.Call call) {
            System.out.println("Call target: " + ((Expr.Name) call.target()).name());
            System.out.println("Call args: " + call.args().size());
            for (int i = 0; i < call.args().size(); i++) {
                System.out.println("  Arg " + i + ": " + call.args().get(i).kind());
            }
        }
    }
}
