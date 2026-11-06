package aster.core.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.typecheck.ErrorCode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR 0051 §16 R56：core 解析诊断码映射的单元测试（parity 夹具之外的细节）。 */
class ParseDiagnosticsTest {

    private static final Lexicon EN = LexiconRegistry.getInstance().getOrThrow("en-US");
    private static final String HEAD = "Module m.\n\nRule f given x as Int, produce Int:\n";

    private static List<ParseDiagnostic> diags(String source) {
        return ParseDiagnostics.parse(source, EN).diagnostics();
    }

    @Test
    void validSourceHasNoDiagnosticsAndBuildsModule() {
        var r = ParseDiagnostics.parse(HEAD + "  Return x.\n", EN);
        assertEquals(List.of(), r.diagnostics());
        assertNotNull(r.module());
        assertEquals("m", r.module().name());
    }

    @Test
    void isFollowedBySymbolCarriesHint() {
        assertTrue(diags(HEAD + "  If x is < 3:\n    Return 1.\n  Return 2.\n").stream()
            .anyMatch(d -> d.code() == ErrorCode.PARSE_NO_VIABLE && "hint.isBeforeSymbol".equals(d.params().get("hint"))));
    }

    @Test
    void missingStatementEndIsE059WithLiteralToken() {
        ParseDiagnostic d = diags(HEAD + "  Let y be 1\n  Return y.\n").get(0);
        assertEquals(ErrorCode.PARSE_EXPECTED_STATEMENT_END, d.code());
        assertEquals(Map.of("token", "'.'"), d.params());
        assertEquals(4, d.line());
    }

    @Test
    void literalOutOfRangeKeepsIllegalStateExceptionAndMessage() {
        // 既有调用方按 IllegalStateException + 原消息处理；改码不得改变这一行为
        String canonical = new Canonicalizer(EN, null).canonicalize(HEAD + "  Return 99999999999.\n");
        AsterParser parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical))));
        AsterParser.ModuleContext tree = parser.module();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AstBuilder().visitModule(tree));
        ParseDiagnosticException pde = assertInstanceOf(ParseDiagnosticException.class, e);
        assertEquals("Integer literal 99999999999 is out of range for Int (-2147483648..2147483647). "
            + "Add the 'L' suffix for a 64-bit Long, or use a Decimal literal.", e.getMessage());
        assertEquals(ErrorCode.PARSE_LITERAL_OUT_OF_RANGE, pde.code());
        assertEquals(Map.of("kind", "Int", "literal", "99999999999"), pde.params());
        assertEquals(4, pde.line());
    }

    /** 构造写法 `X with f = v`（cloud invalidConstructForm 启发式的对象）在 core 同为 E057，与 ts 一致。 */
    @Test
    void invalidConstructFormIsE057() {
        ParseDiagnostic d = diags("Module m.\n\nDefine User has name as Text.\n\nRule f, produce User:\n"
            + "  Return User with name = \"a\".\n").get(0);
        assertEquals(ErrorCode.PARSE_EXPECTED_IN, d.code());
        assertEquals(6, d.line());
    }

    @Test
    void everyParseCodeIsSyntaxCategoryError() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.code().compareTo("E050") >= 0 && code.code().compareTo("E069") <= 0) {
                assertEquals(ErrorCode.Category.SYNTAX, code.category(), code.name());
                assertEquals(ErrorCode.Severity.ERROR, code.severity(), code.name());
            }
        }
    }
}
