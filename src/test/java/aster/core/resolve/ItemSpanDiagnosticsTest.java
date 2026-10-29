package aster.core.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.ast.Span;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierKind;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreModel;
import aster.core.typecheck.model.Diagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 条目级诊断位置（aster-lang-test#174 子项 2）：枚举变体、Data 字段、构造字段上的 E801/E804/E805/E807
 * 落在该条目本身，而不是整条声明或整个构造表达式的起点。期望位置取自 TS 1.1.0 对同一输入的诊断
 * （起点为条目首字符；终点为条目最后一个字符之后，列从 1 起）。
 */
class ItemSpanDiagnosticsTest {

    private static IdentifierMapping member(String parent, String canonical, String localized) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.ENUM_VALUE, parent, null, null);
    }

    private static ResolutionContext ctx(List<IdentifierMapping> structs, List<IdentifierMapping> fields,
                                         List<IdentifierMapping> members) {
        DomainVocabulary vocab = new DomainVocabulary("t", "t", "de-DE", "1",
            structs, fields, List.of(), members, List.of(), null);
        return new ResolutionContext(VocabularyScopes.build(vocab), ImportedDeclarations.NONE);
    }

    private static final ResolutionContext LEVEL_DUP = ctx(
        List.of(IdentifierMapping.struct("Level", "Stufe")), List.of(),
        List.of(member("Level", "High", "Hoch"), member("Level", "High", "Hoch2")));

    private static final ResolutionContext LEVEL_AMBIGUOUS = ctx(
        List.of(IdentifierMapping.struct("Level", "Stufe")), List.of(),
        List.of(member("Level", "High", "Hoch"), member("Level", "Top", "HOCH")));

    private static final ResolutionContext DRIVER_DUP = ctx(
        List.of(IdentifierMapping.struct("Driver", "Fahrer")),
        List.of(IdentifierMapping.field("age", "alter", "Driver"), IdentifierMapping.field("age", "jahre", "Driver")),
        List.of());

    private static final ResolutionContext DRIVER_AMBIGUOUS = ctx(
        List.of(IdentifierMapping.struct("Driver", "Fahrer")),
        List.of(IdentifierMapping.field("age", "Alter", "Driver"), IdentifierMapping.field("ageGroup", "ALTER", "Driver")),
        List.of());

    /** 唯一一条诊断的 code 与位置 {起行, 起列, 止行, 止列}。 */
    private static void assertSingle(String code, int[] expected, List<Diagnostic> diags) {
        assertEquals(1, diags.size(), diags.toString());
        Diagnostic d = diags.get(0);
        assertEquals(code, d.code().code());
        CoreModel.Origin o = d.span().orElseThrow();
        assertEquals(List.of(expected[0], expected[1], expected[2], expected[3]),
            List.of(o.start.line, o.start.col, o.end.line, o.end.col), "诊断位置（起止行列）");
    }

    private static List<Diagnostic> r1(String src, ResolutionContext ctx) {
        return DeclarationResolver.resolve(DeclarationResolverTest.parse(src), ctx).diagnostics();
    }

    @Test
    void duplicateVariantReportedAtSecondVariant() {
        assertSingle("E807", new int[] {3, 39, 3, 44},
            r1("Module m.\n\nDefine Stufe as one of Hoch, Niedrig, Hoch2.\n", LEVEL_DUP));
    }

    @Test
    void ambiguousVariantReportedAtVariant() {
        assertSingle("E804", new int[] {3, 33, 3, 37},
            r1("Module m.\n\nDefine Stufe as one of Niedrig, HOCh.\n", LEVEL_AMBIGUOUS));
    }

    @Test
    void duplicateFieldReportedAtFieldFromNameToType() {
        assertSingle("E807", new int[] {3, 47, 3, 59},
            r1("Module m.\n\nDefine Fahrer has name as Text, alter as Int, jahre as Int.\n", DRIVER_DUP));
    }

    @Test
    void duplicateFieldOnItsOwnLineInMultilineDeclaration() {
        assertSingle("E807", new int[] {6, 3, 6, 15},
            r1("Module m.\n\nDefine Fahrer has\n  name as Text,\n  alter as Int,\n  jahre as Int.\n", DRIVER_DUP));
    }

    @Test
    void duplicateUntypedFieldCoversOnlyTheName() {
        assertSingle("E807", new int[] {3, 26, 3, 31},
            r1("Module m.\n\nDefine Fahrer has alter, jahre.\n", DRIVER_DUP));
    }

    @Test
    void ambiguousFieldDeclarationReportedAtField() {
        assertSingle("E804", new int[] {3, 33, 3, 45},
            r1("Module m.\n\nDefine Fahrer has name as Text, alter as Int.\n", DRIVER_AMBIGUOUS));
    }

    @Test
    void unresolvedConstructFieldReportedAtFieldItem() {
        assertSingle("E801", new int[] {6, 38, 6, 52},
            r1("Module m.\n\nDefine Fahrer has alter as Int, punkte as Int.\n\n"
                + "Rule f given x as Int, produce Fahrer:\n  Return Fahrer with alter set to 1, bogus set to 2.\n",
                DRIVER_DUP));
    }

    @Test
    void unresolvedFirstConstructFieldSpansItsWholeValueExpression() {
        assertSingle("E801", new int[] {6, 22, 6, 43},
            r1("Module m.\n\nDefine Fahrer has alter as Int, punkte as Int.\n\n"
                + "Rule f given x as Int, produce Fahrer:\n  Return Fahrer with bogus set to x plus 1, punkte set to 2.\n",
                DRIVER_DUP));
    }

    @Test
    void ambiguousConstructFieldReportedAtFieldItem() {
        assertSingle("E805", new int[] {6, 36, 6, 50},
            r1("Module m.\n\nDefine Fahrer has age as Int, ageGroup as Int.\n\n"
                + "Rule f, produce Fahrer:\n  Return Fahrer with age set to 1, alter set to 2.\n",
                DRIVER_AMBIGUOUS));
    }

    @Test
    void declarationLevelDuplicateStaysAtDeclarationStart() {
        // 反向护栏：声明级 E807 仍落在声明起点（两引擎本就一致）。
        ResolutionContext ctx = ctx(
            List.of(IdentifierMapping.struct("Driver", "Fahrer"), IdentifierMapping.struct("Driver", "Driver")),
            List.of(), List.of());
        List<Diagnostic> diags = r1("Module m.\n\nDefine Fahrer has alter as Int.\n\nDefine Driver has name as Text.\n", ctx);
        assertEquals(1, diags.size(), diags.toString());
        assertEquals(List.of(5, 1), List.of(diags.get(0).span().orElseThrow().start.line,
            diags.get(0).span().orElseThrow().start.col));
    }

    @Test
    void programmaticAstWithoutItemSpansFallsBackToDeclarationSpan() {
        // 手工构建、未带条目位置的 AST（旧构造器）退回声明位置，不丢诊断。
        Span declSpan = new Span(new Span.Position(3, 1), new Span.Position(3, 30));
        Module m = new Module("m", null, List.of(new Decl.Enum("Stufe", List.of("Hoch", "Hoch2"), declSpan)), declSpan);
        assertSingle("E807", new int[] {3, 1, 3, 30}, DeclarationResolver.resolve(m, LEVEL_DUP).diagnostics());
    }

    @Test
    void itemSpansDoNotChangeCoreIr() throws Exception {
        // 条目位置只在 AST 侧：同一源码的 Core IR 与此前逐字节相同（无 origin 以外的新增键）。
        Module m = DeclarationResolverTest.parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
            + "Define Fahrer has alter as Int, jahre as Int.\n\n"
            + "Rule f given x as Int, produce Fahrer:\n  Return Fahrer with alter set to x, jahre set to 2.\n");
        String ir = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            NameResolution.resolveAndLower(m, ResolutionContext.none()).core());
        assertEquals(false, ir.contains("variantSpans"), ir);
        assertEquals(false, ir.contains("\"span\""), ir);
    }
}
