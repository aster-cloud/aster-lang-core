package aster.core.parser;

import aster.core.ast.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Block span 对齐测试
 * <p>
 * 验证 Block.span 与首尾语句一致，不包含 INDENT/DEDENT token。
 */
class BlockSpanTest {

    private aster.core.ast.Module parseAndBuild(String input) {
        CharStream charStream = CharStreams.fromString(input);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        tokens.fill();
        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();

        AsterParser.ModuleContext moduleCtx = parser.module();
        assertNotNull(moduleCtx, "ModuleContext 不应为 null");

        AstBuilder builder = new AstBuilder();
        aster.core.ast.Module module = builder.visitModule(moduleCtx);
        assertNotNull(module, "AstBuilder 返回的 module 不应为 null");
        return module;
    }

    @Test
    void blockSpanMatchesSingleStatement() {
        String input = """
            To single produce Int:
              Return 1.
            """;

        Decl.Func func = (Decl.Func) parseAndBuild(input).decls().get(0);
        Block body = func.body();
        assertNotNull(body);
        assertEquals(1, body.statements().size());

        Stmt.Return returnStmt = (Stmt.Return) body.statements().get(0);
        Span stmtSpan = returnStmt.span();
        Span blockSpan = body.span();

        assertNotNull(stmtSpan);
        assertNotNull(blockSpan);
        assertEquals(stmtSpan.start(), blockSpan.start(), "Block 起点应与唯一语句一致");
        assertEquals(stmtSpan.end(), blockSpan.end(), "Block 终点应与唯一语句一致");
    }

    @Test
    void blockSpanCoversFirstAndLastStatement() {
        String input = """
            To multi produce Int:
              Let x be 1.
              Return x.
            """;

        Decl.Func func = (Decl.Func) parseAndBuild(input).decls().get(0);
        Block body = func.body();
        assertNotNull(body);
        assertEquals(2, body.statements().size());

        Stmt first = body.statements().get(0);
        Stmt last = body.statements().get(1);

        Span blockSpan = body.span();
        assertNotNull(blockSpan);
        assertEquals(first.span().start(), blockSpan.start(), "Block 起点应来自首语句");
        assertEquals(last.span().end(), blockSpan.end(), "Block 终点应来自末语句");
    }

    @Test
    void nestedBlockSpanIgnoresIndentTokens() {
        String input = """
            To nested produce Int:
              Let flag be 1.
              If flag > 0:
                Return flag.
              Return 0.
            """;

        Decl.Func func = (Decl.Func) parseAndBuild(input).decls().get(0);
        Block body = func.body();
        assertNotNull(body);
        assertEquals(3, body.statements().size());

        Stmt.If ifStmt = (Stmt.If) body.statements().get(1);
        Block thenBlock = ifStmt.thenBlock();
        assertNotNull(thenBlock);
        assertEquals(1, thenBlock.statements().size());

        Stmt.Return innerReturn = (Stmt.Return) thenBlock.statements().get(0);
        Span innerSpan = innerReturn.span();
        Span blockSpan = thenBlock.span();

        assertNotNull(innerSpan);
        assertNotNull(blockSpan);
        assertEquals(innerSpan.start(), blockSpan.start(), "then Block 起点应与内部语句一致");
        assertEquals(innerSpan.end(), blockSpan.end(), "then Block 终点应与内部语句一致");
    }
}
