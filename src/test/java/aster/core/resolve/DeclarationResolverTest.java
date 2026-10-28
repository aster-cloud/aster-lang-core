package aster.core.resolve;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Expr;
import aster.core.ast.Module;
import aster.core.ast.Pattern;
import aster.core.ast.Resolved;
import aster.core.ast.ResolvedRole;
import aster.core.ast.Span;
import aster.core.ast.Stmt;
import aster.core.ast.Type;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierKind;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 声明与类型位置名称解析（ADR 0051 §5、§6.1、§6.2 第 1/2/4/5 步）。用例清单与 TS
 * resolve-declarations.test.ts 同名同输入；Java 内置词法只有 en-US，故关键词写英文、标识符保持德语写法。
 */
class DeclarationResolverTest {

    private static final Span SPAN = new Span(new Span.Position(1, 1), new Span.Position(1, 2));

    // ---- 夹具 ----

    static Module parse(String source) {
        String canonical = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), null).canonicalize(source);
        List<String> errors = new ArrayList<>();
        BaseErrorListener collect = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
                errors.add("L" + line + ":" + col + " " + msg);
            }
        };
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        lexer.removeErrorListeners();
        lexer.addErrorListener(collect);
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(collect);
        AsterParser.ModuleContext tree = parser.module();
        assertEquals(List.of(), errors, "语法错误");
        return new AstBuilder().visitModule(tree);
    }

    private static IdentifierMapping struct(String canonical, String localized) {
        return IdentifierMapping.struct(canonical, localized);
    }

    private static IdentifierMapping field(String parent, String canonical, String localized) {
        return IdentifierMapping.field(canonical, localized, parent);
    }

    private static IdentifierMapping member(String parent, String canonical, String localized) {
        return new IdentifierMapping(canonical, localized, IdentifierKind.ENUM_VALUE, parent, null, null);
    }

    private static IdentifierMapping fn(String canonical, String localized) {
        return IdentifierMapping.function(canonical, localized);
    }

    private static List<IdentifierMapping> ofKind(List<IdentifierMapping> ms, IdentifierKind kind) {
        return ms.stream().filter(m -> m.kind() == kind).toList();
    }

    private static ResolutionContext ctxOf(List<IdentifierMapping> mappings, ImportedDeclarations imports) {
        DomainVocabulary vocab = new DomainVocabulary("t", "t", "de-DE", "1",
            ofKind(mappings, IdentifierKind.STRUCT), ofKind(mappings, IdentifierKind.FIELD),
            ofKind(mappings, IdentifierKind.FUNCTION), ofKind(mappings, IdentifierKind.ENUM_VALUE), List.of(), null);
        return new ResolutionContext(VocabularyScopes.build(vocab), imports);
    }

    private static ResolutionContext ctxOf(List<IdentifierMapping> mappings) {
        return ctxOf(mappings, ImportedDeclarations.NONE);
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        return Stream.of(lists).flatMap(List::stream).toList();
    }

    private static final List<IdentifierMapping> DRIVER = List.of(struct("Driver", "Fahrer"), field("Driver", "age", "alter"));
    private static final List<IdentifierMapping> LEVEL = List.of(
        struct("Level", "Stufe"),
        field("Driver", "level", "stufe"),
        member("Level", "High", "Hoch"),
        member("Level", "Low", "Niedrig"));

    private static <T extends Decl> T decl(Module m, int i, Class<T> kind) {
        return assertInstanceOf(kind, m.decls().get(i), "decls[" + i + "]");
    }

    private static Stmt bodyStmt(Decl.Func f, int i) {
        return f.body().statements().get(i);
    }

    private static Expr returned(Decl.Func f, int i) {
        return assertInstanceOf(Stmt.Return.class, bodyStmt(f, i)).expr();
    }

    private static List<ErrorCode> codes(ResolutionResult<Module> r) {
        return r.diagnostics().stream().map(Diagnostic::code).toList();
    }

    private static Resolved res(String canonical, ResolvedRole role, String written) {
        return new Resolved(canonical, role, written);
    }

    private static Type.TypeName typeName(String name) {
        return new Type.TypeName(name, List.of(), SPAN);
    }

    private static Resolved typeResolved(Type t) {
        return assertInstanceOf(Type.TypeName.class, t).resolved();
    }

    private static Decl.Func func(String name, List<Decl.Parameter> params, Type ret, Block body) {
        return new Decl.Func(name, SPAN, List.of(), params, ret, List.of(), List.of(), body, List.of(), List.of(), false, SPAN);
    }

    private static Decl.Parameter param(String name, Type type) {
        return new Decl.Parameter(name, type, List.of(), SPAN);
    }

    // ---- 用例 ----

    @Test
    void definiereFahrerHatAlterResolvesToDriverAndAge() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
        Decl.Data data = decl(r.value(), 0, Decl.Data.class);
        assertEquals("Fahrer", data.name(), "原写法保持");
        assertEquals(res("Driver", ResolvedRole.TYPE_DECL, "Fahrer"), data.resolved());
        assertEquals(res("age", ResolvedRole.FIELD_DECL, "alter"), data.fields().get(0).resolved());
        assertEquals(List.of(), codes(r));
    }

    @Test
    void structStufeAndFieldStufeCoexist() {
        Module m = parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\nDefine Fahrer has stufe as Stufe.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(concat(DRIVER, LEVEL)));
        Decl.Enum en = decl(r.value(), 0, Decl.Enum.class);
        assertEquals("Level", en.resolved().canonical());
        assertEquals(List.of(res("High", ResolvedRole.MEMBER_DECL, "Hoch"), res("Low", ResolvedRole.MEMBER_DECL, "Niedrig")),
            en.resolvedVariants());
        Decl.Field f = decl(r.value(), 1, Decl.Data.class).fields().get(0);
        assertEquals(res("level", ResolvedRole.FIELD_DECL, "stufe"), f.resolved());
        assertEquals(res("Level", ResolvedRole.TYPE, "Stufe"), typeResolved(f.type()));
        assertEquals(List.of(), codes(r));
    }

    @Test
    void parameterNameStaysLocalAndTypeResolves() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n\n"
            + "Rule f given fahrer as Fahrer, produce Fahrer:\n  Return fahrer.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
        Decl.Func f = decl(r.value(), 1, Decl.Func.class);
        Decl.Parameter p = f.params().get(0);
        assertEquals("fahrer", p.name());
        assertEquals(res("fahrer", ResolvedRole.LOCAL, "fahrer"), p.resolved());
        assertEquals("Driver", typeResolved(p.type()).canonical());
        assertEquals("Driver", typeResolved(f.retType()).canonical());
        assertEquals(res("fahrer", ResolvedRole.LOCAL, "fahrer"), ((Expr.Name) returned(f, 0)).resolved());
        assertEquals(List.of(), codes(r), "折叠命中词汇不构成遮蔽");
    }

    @Test
    void nestedListResultMapArgumentsResolve() {
        Module m = parse("Module m.\n\nDefine Fahrer has freunde as list of Fahrer, index as Map Text to Fahrer.\n\n"
            + "Rule f given fahrer as Fahrer, produce Result of Fahrer and Text:\n  Return ok of fahrer.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
        Decl.Data data = decl(r.value(), 0, Decl.Data.class);
        Type.List list = assertInstanceOf(Type.List.class, data.fields().get(0).type());
        assertEquals("Driver", typeResolved(list.type()).canonical());
        Type.Map map = assertInstanceOf(Type.Map.class, data.fields().get(1).type());
        assertEquals("Driver", typeResolved(map.val()).canonical());
        assertEquals("Text", typeResolved(map.key()).canonical());
        // Java 解析器把 `Result of A and B` 产出为基名小写的 TypeApp（TS 为 Result 节点）：基名按内置处理，参数按 TYPE 解析
        Type.TypeApp res = assertInstanceOf(Type.TypeApp.class, decl(r.value(), 1, Decl.Func.class).retType());
        assertEquals("result", res.resolved().canonical());
        assertEquals(List.of(), codes(r));
        assertEquals("Driver", typeResolved(res.args().get(0)).canonical());
        assertEquals("Text", typeResolved(res.args().get(1)).canonical());
    }

    @Test
    void funcTypeTypeAppAndOptionResolve() {
        Type.FuncType funcType = new Type.FuncType(List.of(typeName("Fahrer")),
            new Type.Maybe(typeName("Fahrer"), List.of(), SPAN), List.of(), SPAN);
        Type.TypeApp app = new Type.TypeApp("Fahrer", List.of(), List.of(typeName("Fahrer")), SPAN);
        Module m = new Module("m", null, List.of(
            new Decl.Data("Fahrer", List.of(), SPAN),
            func("f", List.of(param("cb", funcType), param("box", app)),
                new Type.Option(typeName("Fahrer"), List.of(), SPAN), null)), SPAN);
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
        Decl.Func f = decl(r.value(), 1, Decl.Func.class);
        Type.FuncType ft = assertInstanceOf(Type.FuncType.class, f.params().get(0).type());
        assertEquals("Driver", typeResolved(ft.params().get(0)).canonical());
        assertEquals("Driver", typeResolved(((Type.Maybe) ft.ret()).type()).canonical());
        Type.TypeApp outApp = assertInstanceOf(Type.TypeApp.class, f.params().get(1).type());
        assertEquals(res("Driver", ResolvedRole.TYPE, "Fahrer"), outApp.resolved());
        assertEquals("Fahrer", outApp.base(), "原写法保持");
        assertEquals("Driver", typeResolved(outApp.args().get(0)).canonical());
        assertEquals("Driver", typeResolved(((Type.Option) f.retType()).type()).canonical());
        assertEquals(List.of(), codes(r));
    }

    @Test
    void sameStructFieldsAlterAndAlterCapitalResolveExactly() {
        Module m = new Module("m", null, List.of(new Decl.Data("Fahrer", List.of(
            new Decl.Field("alter", typeName("Int"), List.of()),
            new Decl.Field("Alter", typeName("Text"), List.of())), SPAN)), SPAN);
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(concat(DRIVER, List.of(field("Driver", "ageGroup", "Alter")))));
        Decl.Data data = decl(r.value(), 0, Decl.Data.class);
        assertEquals("age", data.fields().get(0).resolved().canonical());
        assertEquals("ageGroup", data.fields().get(1).resolved().canonical());
        assertEquals(List.of(), codes(r));
    }

    @Test
    void importedDeclarationShadowsVocabularyWithWarning() {
        Module m = parse("Module m.\n\nRule f given x as Fahrer, produce Fahrer:\n  Return x.\n");
        ImportedDeclarations imports = new ImportedDeclarations(List.of("Fahrer"), List.of(), Map.of(), Map.of());
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER, imports));
        Decl.Func f = decl(r.value(), 0, Decl.Func.class);
        assertEquals("Fahrer", typeResolved(f.params().get(0).type()).canonical());
        assertEquals("Fahrer", typeResolved(f.retType()).canonical());
        assertFalse(codes(r).isEmpty(), "应有 W800");
        assertTrue(codes(r).stream().allMatch(c -> c == ErrorCode.SHADOWED_VOCABULARY));
    }

    @Test
    void qualifiedEnumMemberKeepsQualifiedShape() {
        Module m = parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
            + "Rule f given x as Int, produce Stufe:\n  Return Stufe.Hoch.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(LEVEL));
        Expr.Name name = assertInstanceOf(Expr.Name.class, returned(decl(r.value(), 1, Decl.Func.class), 0));
        assertEquals("Stufe.Hoch", name.name());
        assertEquals(res("Level.High", ResolvedRole.MEMBER_REF, "Stufe.Hoch"), name.resolved());
        assertEquals(List.of(), codes(r));
    }

    @Test
    void functionDeclarationCallTargetAndConstruct() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n\n"
            + "Rule pruefe given n as Int, produce Fahrer:\n  Return Fahrer with alter set to n.\n\n"
            + "Rule g given n as Int, produce Fahrer:\n  Return pruefe(n).\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(concat(DRIVER, List.of(fn("check", "pruefe")))));
        Decl.Func f = decl(r.value(), 1, Decl.Func.class);
        assertEquals(res("check", ResolvedRole.FUNC_DECL, "pruefe"), f.resolved());
        Expr.Construct ctor = assertInstanceOf(Expr.Construct.class, returned(f, 0));
        assertEquals(res("Driver", ResolvedRole.TYPE, "Fahrer"), ctor.resolved());
        assertEquals(res("age", ResolvedRole.FIELD_REF, "alter"), ctor.fields().get(0).resolved());
        Expr.Call call = assertInstanceOf(Expr.Call.class, returned(decl(r.value(), 2, Decl.Func.class), 0));
        assertEquals(res("check", ResolvedRole.FUNC_REF, "pruefe"), ((Expr.Name) call.target()).resolved());
        assertEquals(ResolvedRole.LOCAL, ((Expr.Name) call.args().get(0)).resolved().role());
    }

    @Test
    void twoDeclarationsWithSameCanonicalReportE807() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n\nDefine Driver has age as Int.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
        assertEquals(List.of(ErrorCode.DUPLICATE_DECLARATION), codes(r));
        Diagnostic d = r.diagnostics().get(0);
        assertTrue(d.message().contains("Driver"), d.message());
        assertEquals(Diagnostic.Severity.ERROR, d.severity());
    }

    @Test
    void multipleFoldedCandidatesWithoutExactReportE804() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n");
        List<IdentifierMapping> vocab = List.of(struct("Driver", "Fahrer"),
            field("Driver", "ageGroup", "Alter"), field("Driver", "maxAge", "ALTER"));
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(vocab));
        assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), codes(r));
        Map<String, Object> data = r.diagnostics().get(0).data();
        assertTrue(data.get("candidates").toString().contains("ageGroup"));
        assertTrue(data.get("candidates").toString().contains("maxAge"));
    }

    @Test
    void exactHitWinsOverFoldedHits() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n");
        List<IdentifierMapping> vocab = concat(DRIVER, List.of(field("Driver", "ageGroup", "Alter"), field("Driver", "maxAge", "ALTER")));
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(vocab));
        assertEquals("age", decl(r.value(), 0, Decl.Data.class).fields().get(0).resolved().canonical());
        assertEquals(List.of(), codes(r));
    }

    @Test
    void localShadowsVocabularyWithW800() {
        Module m = parse("Module m.\n\nRule f given pruefe as Int, produce Int:\n  Return pruefe.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(List.of(fn("check", "pruefe"))));
        Expr.Name name = (Expr.Name) returned(decl(r.value(), 0, Decl.Func.class), 0);
        assertEquals(ResolvedRole.LOCAL, name.resolved().role());
        assertEquals(List.of(ErrorCode.SHADOWED_VOCABULARY), codes(r));
    }

    @Test
    void modulePathWorkflowStepAnnotationArgsUntouched() {
        String src = "Module fahrer.pruefe.\n\n@id(\"Fahrer\")\nRule run, produce. It performs io:\n"
            + "  workflow:\n    step pruefe:\n      Return ok of 1.\n\n  .\n";
        Module m = parse(src);
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(concat(DRIVER, List.of(fn("check", "pruefe")))));
        assertEquals("fahrer.pruefe", r.value().name());
        Decl.Func f = decl(r.value(), 0, Decl.Func.class);
        Decl.Func before = decl(m, 0, Decl.Func.class);
        assertEquals(before.annotations(), f.annotations());
        Stmt.Workflow wf = assertInstanceOf(Stmt.Workflow.class, bodyStmt(f, 0));
        Stmt.Workflow wfBefore = (Stmt.Workflow) bodyStmt(before, 0);
        assertEquals("pruefe", wf.steps().get(0).name());
        assertEquals(wfBefore.steps().get(0).name(), wf.steps().get(0).name());
        assertEquals(List.of(), codes(r));
    }

    /** Java 独有：retry 配置（RetryPolicy）与 timeout（Timeout）为语法配置，原样保留；步骤体照常解析。 */
    @Test
    void retryPolicyAndTimeoutUntouched() {
        Stmt.RetryPolicy retry = new Stmt.RetryPolicy(3, "exponential");
        Stmt.Timeout timeout = new Stmt.Timeout(30_000L);
        Block stepBody = new Block(List.of(new Stmt.Return(new Expr.Name("pruefe", SPAN), SPAN)), SPAN);
        Stmt.Workflow wf = new Stmt.Workflow(List.of(new Stmt.WorkflowStep("pruefe", stepBody, null, List.of(), SPAN)),
            retry, timeout, SPAN);
        Module m = new Module("m", null, List.of(
            func("pruefe", List.of(), typeName("Int"), null),
            func("run", List.of(), typeName("Int"), new Block(List.of(wf), SPAN))), SPAN);
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(List.of(fn("check", "pruefe"))));
        Stmt.Workflow out = assertInstanceOf(Stmt.Workflow.class, bodyStmt(decl(r.value(), 1, Decl.Func.class), 0));
        assertSame(retry, out.retry());
        assertSame(timeout, out.timeout());
        Stmt.WorkflowStep step = out.steps().get(0);
        assertEquals("pruefe", step.name());
        Expr.Name ref = (Expr.Name) ((Stmt.Return) step.body().statements().get(0)).expr();
        assertEquals(res("check", ResolvedRole.FUNC_REF, "pruefe"), ref.resolved());
        assertEquals(List.of(), codes(r));
    }

    @Test
    void patternCtorOnKnownDataSubjectResolvesAsType() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n\n"
            + "Rule f given fahrer as Fahrer, produce Int:\n  Match fahrer:\n    When Fahrer(a), Return a.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
        Stmt.Match match = assertInstanceOf(Stmt.Match.class, bodyStmt(decl(r.value(), 1, Decl.Func.class), 0));
        Pattern.PatternCtor pat = assertInstanceOf(Pattern.PatternCtor.class, match.cases().get(0).pattern());
        assertEquals(res("Driver", ResolvedRole.TYPE, "Fahrer"), pat.resolved());
        Stmt.Return ret = assertInstanceOf(Stmt.Return.class, match.cases().get(0).body());
        assertEquals(ResolvedRole.LOCAL, ((Expr.Name) ret.expr()).resolved().role());
    }

    @Test
    void bareNameUnderExpectedEnumLeftForR2() {
        Module m = parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
            + "Rule f given x as Int, produce Stufe:\n  Return Hoch.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(LEVEL));
        Expr.Name name = (Expr.Name) returned(decl(r.value(), 1, Decl.Func.class), 0);
        assertNull(name.resolved());
        assertEquals(List.of(), codes(r));
    }

    /** Java 独有：无括号的裸构造器模式（解析器产出无参 PatternCtor，TS 为 PatternName）同样留给 R2。 */
    @Test
    void barePatternCtorWithoutParensLeftForR2() {
        Module m = parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
            + "Rule f given s as Stufe, produce Int:\n  Match s:\n    When Hoch, Return 1.\n    When Mittel, Return 2.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(LEVEL));
        Stmt.Match match = assertInstanceOf(Stmt.Match.class, bodyStmt(decl(r.value(), 1, Decl.Func.class), 0));
        assertNull(assertInstanceOf(Pattern.PatternCtor.class, match.cases().get(0).pattern()).resolved());
        assertNull(assertInstanceOf(Pattern.PatternCtor.class, match.cases().get(1).pattern()).resolved());
        assertEquals(List.of(), codes(r));
    }

    @Nested
    class ImplicitTypeParams {

        @Test
        void implicitTypeParamHittingVocabularyIsRemoved() {
            Module m = parse("Module m.\n\nRule f given x as Fahrer, produce Fahrer:\n  Return x.\n");
            Decl.Func parsed = decl(m, 0, Decl.Func.class);
            assertEquals(List.of("Fahrer"), parsed.typeParams(), "前提：解析器把 Fahrer 推断为类型参数");
            assertTrue(parsed.typeParamsInferred(), "前提：类型参数为隐式推断");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            Decl.Func f = decl(r.value(), 0, Decl.Func.class);
            assertEquals(List.of(), f.typeParams());
            assertEquals("Driver", typeResolved(f.params().get(0).type()).canonical());
            assertEquals(List.of(), codes(r));
        }

        @Test
        void implicitTypeParamMissingEverywhereIsKept() {
            Module m = parse("Module m.\n\nRule f given x as Element, produce Element:\n  Return x.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            Decl.Func f = decl(r.value(), 0, Decl.Func.class);
            assertEquals(List.of("Element"), f.typeParams());
            assertNull(typeResolved(f.params().get(0).type()));
            assertNull(typeResolved(f.retType()));
            assertEquals(List.of(), codes(r));
        }

        @Test
        void implicitTypeParamFoldAmbiguousReportsE804AndIsKept() {
            Module m = parse("Module m.\n\nRule f given x as FaHrer, produce Int:\n  Return 1.\n");
            assertEquals(List.of("FaHrer"), decl(m, 0, Decl.Func.class).typeParams(), "前提：解析器推断为类型参数");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(List.of(struct("Driver", "Fahrer"), struct("Pilot", "FAHRER"))));
            assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), codes(r));
            assertEquals(List.of("FaHrer"), decl(r.value(), 0, Decl.Func.class).typeParams());
        }

        /** Java 解析器对显式 `of T` 的使用仍产出 TypeName（不产出 TypeVar），以 typeParamsInferred=false 区分。 */
        @Test
        void explicitTypeParamKeptAndNotLookedUp() {
            Module m = parse("Module m.\n\nRule f of Fahrer given x as Fahrer, produce Fahrer:\n  Return x.\n");
            assertFalse(decl(m, 0, Decl.Func.class).typeParamsInferred(), "前提：显式类型参数");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            Decl.Func f = decl(r.value(), 0, Decl.Func.class);
            assertEquals(List.of("Fahrer"), f.typeParams());
            assertEquals(decl(m, 0, Decl.Func.class).params().get(0).type(), f.params().get(0).type());
            assertNull(typeResolved(f.params().get(0).type()));
            assertEquals(List.of(), codes(r));
        }
    }

    @Nested
    class Unresolved {

        @Test
        void typeMissEverywhereReportsE800() {
            Module m = parse("Module m.\n\nDefine Fahrer has alter as Unbekannt.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            assertEquals(List.of(ErrorCode.UNRESOLVED_TYPE), codes(r));
            assertTrue(r.diagnostics().get(0).message().contains("Unbekannt"));
        }

        @Test
        void declaredBuiltinImportedAndVocabularyTypesDoNotReportE800() {
            Module m = parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
                + "Define Fahrer has alter as Int, stufe as Stufe, konto as Konto, name as Text, "
                + "betrag as Decimal, frei as Bool, freund as Driver.\n");
            ImportedDeclarations imports = new ImportedDeclarations(List.of("Konto"), List.of(), Map.of(), Map.of());
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(concat(DRIVER, LEVEL), imports));
            assertEquals(List.of(), codes(r));
        }

        @Test
        void dateTimeIsBuiltin() {
            Module m = parse("Module m.\n\nDefine Fahrer has seit as DateTime.\n");
            assertEquals(List.of(), codes(DeclarationResolver.resolve(m, ctxOf(DRIVER))));
        }

        @Test
        void unresolvedConstructTypeSuppressesFieldE801() {
            Module m = parse("Module m.\n\nRule f given x as Int, produce Int:\n"
                + "  Let p be Unbekannt with foo set to x, bar set to x.\n  Return x.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            assertEquals(List.of(ErrorCode.UNRESOLVED_TYPE), codes(r));
        }

        @Test
        void constructFieldMissReportsE801() {
            Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n\n"
                + "Rule f given x as Int, produce Fahrer:\n  Return Fahrer with gewicht set to x.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            assertEquals(List.of(ErrorCode.UNRESOLVED_FIELD), codes(r));
            assertTrue(r.diagnostics().get(0).message().contains("gewicht"));
        }

        @Test
        void constructFieldFoldAmbiguousReportsE805() {
            Module m = parse("Module m.\n\nRule f given x as Int, produce Driver:\n  Return Driver with alter set to x.\n");
            ImportedDeclarations imports = new ImportedDeclarations(List.of("Driver"), List.of(), Map.of(),
                Map.of("Driver", List.of("age")));
            List<IdentifierMapping> vocab = List.of(struct("Driver", "Fahrer"),
                field("Driver", "ageGroup", "Alter"), field("Driver", "maxAge", "ALTER"));
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(vocab, imports));
            assertEquals(List.of(ErrorCode.AMBIGUOUS_FIELD), codes(r));
        }

        @Test
        void qualifiedMemberMissReportsE802WithMemberRef() {
            Module m = parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
                + "Rule f given x as Int, produce Stufe:\n  Return Stufe.Mittel.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(LEVEL));
            assertEquals(List.of(ErrorCode.UNRESOLVED_NAME), codes(r));
            String message = r.diagnostics().get(0).message();
            assertTrue(message.contains("Mittel"), message);
            assertTrue(message.contains("MEMBER_REF"), message);
        }

        @Test
        void patternCtorMemberMissOnKnownEnumReportsE803() {
            Block body = new Block(List.of(new Stmt.Match(new Expr.Name("s", SPAN), List.of(new Stmt.Case(
                new Pattern.PatternCtor("Mittel", List.of("x"), List.of(new Pattern.PatternName("x", SPAN)), SPAN),
                new Stmt.Return(new Expr.Int(1, SPAN), SPAN), SPAN)), SPAN)), SPAN);
            Module m = new Module("m", null, List.of(
                new Decl.Enum("Stufe", List.of("Hoch"), SPAN),
                func("f", List.of(param("s", typeName("Stufe"))), typeName("Int"), body)), SPAN);
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(LEVEL));
            assertEquals(List.of(ErrorCode.UNRESOLVED_PATTERN), codes(r));
        }

        @Test
        void bareValueNameMissIsLeftForR2() {
            Module m = parse("Module m.\n\nRule f given x as Int, produce Int:\n  Return unbekannt.\n");
            assertEquals(List.of(), codes(DeclarationResolver.resolve(m, ctxOf(DRIVER))));
        }

        @Test
        void dottedHeadProbedSilentlyAndVocabularyFunctionHeadNotEnum() {
            String src = "Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
                + "Rule f given x as Text, produce Stufe:\n  Let n be Unbekannt.laenge.\n  Return Stufe.Hoch.\n";
            ResolutionResult<Module> r = DeclarationResolver.resolve(parse(src), ctxOf(concat(LEVEL, List.of(fn("grade", "Stufe")))));
            assertEquals(List.of(), codes(r));
            Decl.Func f = decl(r.value(), 1, Decl.Func.class);
            assertNull(((Expr.Name) assertInstanceOf(Stmt.Let.class, bodyStmt(f, 0)).expr()).resolved());
            assertNull(((Expr.Name) returned(f, 1)).resolved());
        }
    }

    @Nested
    class TypeAliases {

        @Test
        void aliasReferenceResolvesWithoutE800() {
            Module m = parse("Module m.\n\ntype Score as Int.\n\nDefine Fahrer has punkte as Score.\n\n"
                + "Rule f given s as Score, produce Score:\n  Return s.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            assertEquals(List.of(), codes(r));
            assertEquals(res("Score", ResolvedRole.TYPE_DECL, "Score"), decl(r.value(), 0, Decl.TypeAlias.class).resolved());
            Decl.Func f = decl(r.value(), 2, Decl.Func.class);
            assertEquals(List.of(), f.typeParams());
            assertEquals("Score", typeResolved(f.params().get(0).type()).canonical());
            assertEquals("Score", typeResolved(decl(r.value(), 1, Decl.Data.class).fields().get(0).type()).canonical());
        }

        @Test
        void aliasDeclarationHittingVocabularyTakesCanonical() {
            Module m = parse("Module m.\n\ntype Punktzahl as Int.\n\nDefine Fahrer has punkte as Punktzahl.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(concat(DRIVER, List.of(struct("Score", "Punktzahl")))));
            assertEquals(List.of(), codes(r));
            Decl.TypeAlias alias = decl(r.value(), 0, Decl.TypeAlias.class);
            assertEquals("Punktzahl", alias.name(), "原写法保留");
            assertEquals(res("Score", ResolvedRole.TYPE_DECL, "Punktzahl"), alias.resolved());
            assertEquals(res("Score", ResolvedRole.TYPE, "Punktzahl"), typeResolved(decl(r.value(), 1, Decl.Data.class).fields().get(0).type()));
        }

        @Test
        void aliasAndDataWithSameCanonicalReportE807() {
            Module m = parse("Module m.\n\ntype Fahrer as Int.\n\nDefine Driver has age as Int.\n");
            assertEquals(List.of(ErrorCode.DUPLICATE_DECLARATION), codes(DeclarationResolver.resolve(m, ctxOf(DRIVER))));
        }

        /** Java 独有：别名右侧类型保留在 AST 中，但与 TS 一致不解析、不产生诊断。 */
        @Test
        void aliasRightHandSideIsNotResolved() {
            Module m = parse("Module m.\n\ntype Punktzahl as Unbekannt.\n");
            ResolutionResult<Module> r = DeclarationResolver.resolve(m, ctxOf(DRIVER));
            assertEquals(List.of(), codes(r));
            Decl.TypeAlias alias = decl(r.value(), 0, Decl.TypeAlias.class);
            assertSame(decl(m, 0, Decl.TypeAlias.class).type(), alias.type());
        }
    }

    /** resolved 字段可经 JSON 往返；未解析节点不输出 resolved，保持既有 AST JSON 不变。 */
    @Test
    void resolvedSurvivesJsonRoundTripAndIsOmittedWhenAbsent() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Module m = parse("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
            + "Rule f given x as Fahrer, produce Stufe:\n  Let y be x.\n  Return Stufe.Hoch.\n");
        assertFalse(mapper.writeValueAsString(m).contains("resolved"));
        Module resolved = DeclarationResolver.resolve(m, ctxOf(concat(DRIVER, LEVEL))).value();
        assertEquals(resolved, mapper.readValue(mapper.writeValueAsString(resolved), Module.class));
    }

    @Test
    void inputAstIsNotModified() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n");
        Module copy = parse("Module m.\n\nDefine Fahrer has alter as Int.\n");
        DeclarationResolver.resolve(m, ctxOf(DRIVER));
        assertEquals(copy, m);
    }

    @Test
    void withoutVocabularyDeclarationsAndTypesKeepWrittenForm() {
        Module m = parse("Module m.\n\nDefine Fahrer has alter as Int.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ResolutionContext.none());
        Decl.Data data = decl(r.value(), 0, Decl.Data.class);
        assertEquals("Fahrer", data.resolved().canonical());
        assertEquals("alter", data.fields().get(0).resolved().canonical());
        assertEquals("Int", typeResolved(data.fields().get(0).type()).canonical());
    }

    /** Java 独有：省略返回类型时解析器按函数名合成的占位类型（如 generateQuote → Quote）不按 TYPE 解析。 */
    @Test
    void inferredReturnTypePlaceholderIsNotResolved() {
        Module m = parse("Module m.\n\nRule generateQuote given x as Int, produce:\n  Return x.\n");
        ResolutionResult<Module> r = DeclarationResolver.resolve(m, ResolutionContext.none());
        assertEquals(List.of(), codes(r));
        assertNull(typeResolved(decl(r.value(), 0, Decl.Func.class).retType()));
    }
}
