package aster.core.resolve;

import aster.core.ast.Block;
import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.ast.ResolvedRole;
import aster.core.ast.Stmt;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierKind;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 类型驱动解析（ADR 0051 §4 三态表、§6.2 期望枚举跨层裁决）。用例清单与 TS
 * resolve-typed-references.test.ts 同名同输入；Java 内置词法只有 en-US，故关键词写英文、标识符保持德语写法。
 * 除特别注明外均为 解析器 → R1 → lower → R2 端到端。
 */
class TypedReferenceResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- 夹具 ----

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

    static ResolutionContext ctxOf(List<IdentifierMapping> mappings) {
        DomainVocabulary vocab = new DomainVocabulary("t", "t", "de-DE", "1",
            ofKind(mappings, IdentifierKind.STRUCT), ofKind(mappings, IdentifierKind.FIELD),
            ofKind(mappings, IdentifierKind.FUNCTION), ofKind(mappings, IdentifierKind.ENUM_VALUE), List.of(), null);
        return new ResolutionContext(VocabularyScopes.build(vocab), ImportedDeclarations.NONE);
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        return Stream.of(lists).flatMap(List::stream).toList();
    }

    private record Run(CoreModel.Module module, List<Diagnostic> r1, List<Diagnostic> r2) {
        List<ErrorCode> all() {
            return Stream.concat(r1.stream(), r2.stream()).map(Diagnostic::code).toList();
        }

        List<ErrorCode> r2Codes() {
            return codes(r2);
        }
    }

    /** 解析器 → R1 → lower → R2；edit 用于改写解析器无法直接产出的 AST 形状。 */
    private static Run run(String source, List<IdentifierMapping> mappings, UnaryOperator<Module> edit) {
        ResolutionContext ctx = ctxOf(mappings);
        ResolutionResult<Module> r1 = DeclarationResolver.resolve(edit.apply(DeclarationResolverTest.parse(source)), ctx);
        ResolutionResult<CoreModel.Module> r2 = TypedReferenceResolver.resolve(new CoreLowering().lowerModule(r1.value()), ctx);
        return new Run(r2.value(), r1.diagnostics(), r2.diagnostics());
    }

    private static Run run(String source, List<IdentifierMapping> mappings) {
        return run(source, mappings, UnaryOperator.identity());
    }

    private static List<ErrorCode> codes(List<Diagnostic> ds) {
        return ds.stream().map(Diagnostic::code).toList();
    }

    private static CoreModel.Func func(CoreModel.Module m, String name) {
        return m.decls.stream()
            .filter(d -> d instanceof CoreModel.Func f && f.name.equals(name))
            .map(CoreModel.Func.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("函数 " + name + " 不存在"));
    }

    private static <T extends CoreModel.Stmt> T stmt(CoreModel.Func f, int i, Class<T> kind) {
        return assertInstanceOf(kind, f.body.statements.get(i), "body[" + i + "]");
    }

    private static CoreModel.Expr returned(CoreModel.Func f, int i) {
        return stmt(f, i, CoreModel.Return.class).expr;
    }

    private static CoreModel.Name nameOf(CoreModel.Expr e) {
        return assertInstanceOf(CoreModel.Name.class, e);
    }

    private static final String MODULE = "Module m.\n\n";
    private static final String DRIVER_DECL = "Define Fahrer has alter as Int.\n\n";
    private static final String LEVEL_DECL = "Define Stufe as one of Hoch, Niedrig.\n\n";
    private static final List<IdentifierMapping> DRIVER = List.of(struct("Driver", "Fahrer"), field("Driver", "age", "alter"));
    private static final List<IdentifierMapping> LEVEL = List.of(
        struct("Level", "Stufe"), member("Level", "High", "Hoch"), member("Level", "Low", "Niedrig"));

    // ---- FIELD_REF：点分后续段与 Set 目标 ----

    @Nested
    class FieldRef {

        @Test
        void paramOfFahrerResolvesFieldAndRecordsOrigin() {
            Run r = run(MODULE + DRIVER_DECL + "Rule f given x as Fahrer, produce Int:\n  Return x.alter.\n", DRIVER);
            assertEquals(List.of(), r.all());
            CoreModel.Name n = nameOf(returned(func(r.module(), "f"), 0));
            assertEquals("x.age", n.name);
            assertEquals("x.alter", n.origin.written);
            assertEquals(ResolvedRole.FIELD_REF, n.origin.role);
        }

        @Test
        void multiSegmentPathUsesDeclaredTypeOfEachSegment() {
            Run r = run(MODULE + "Define Halter has fahrer as Fahrer.\n\n" + DRIVER_DECL
                    + "Rule f given h as Halter, produce Int:\n  Return h.fahrer.alter.\n",
                concat(DRIVER, List.of(struct("Owner", "Halter"), field("Owner", "driver", "fahrer"))));
            assertEquals(List.of(), r.r2Codes());
            assertEquals("h.driver.age", nameOf(returned(func(r.module(), "f"), 0)).name);
        }

        @Test
        void setTargetPathResolves() {
            // Java 解析器不产出 Set 语句：在 AST 上把首条 Let 改写为点分目标的 Set
            UnaryOperator<Module> toSet = m -> new Module(m.name(), m.profile(), m.decls().stream().map(d -> {
                if (!(d instanceof Decl.Func f) || f.body() == null) return d;
                List<Stmt> stmts = new ArrayList<>(f.body().statements());
                Stmt.Let let = (Stmt.Let) stmts.get(0);
                stmts.set(0, new Stmt.Set("x.alter", let.expr(), let.span()));
                return (Decl) withBody(f, new Block(stmts, f.body().span()));
            }).toList(), m.span());
            Run r = run(MODULE + DRIVER_DECL
                + "Rule f given x as Fahrer, produce Int:\n  Let unused be 5.\n  Return 1.\n", DRIVER, toSet);
            assertEquals(List.of(), r.r2Codes());
            CoreModel.Set set = stmt(func(r.module(), "f"), 0, CoreModel.Set.class);
            assertEquals("x.age", set.name);
            assertEquals("x.alter", set.origin.written);
            assertEquals(ResolvedRole.FIELD_REF, set.origin.role);
        }

        @Test
        void knownTypeMissingFieldReportsE801() {
            Run r = run(MODULE + DRIVER_DECL + "Rule f given x as Fahrer, produce Int:\n  Return x.gewicht.\n", DRIVER);
            assertEquals(List.of(ErrorCode.UNRESOLVED_FIELD), r.r2Codes());
        }

        @Test
        void qualifiedCallNamespaceKeptVerbatim() {
            Run r = run("Module m.\n\nRule f given t as Text, produce Int:\n  Return Text.length(t).\n", List.of());
            assertEquals(List.of(), r.r2Codes());
            CoreModel.Call call = assertInstanceOf(CoreModel.Call.class, returned(func(r.module(), "f"), 0));
            assertEquals("Text.length", nameOf(call.target).name);
        }
    }

    private static Decl.Func withBody(Decl.Func f, Block body) {
        return new Decl.Func(f.name(), f.nameSpan(), f.typeParams(), f.params(), f.retType(), f.annotations(),
            f.retAnnotations(), body, f.effects(), f.effectCaps(), f.effectCapsExplicit(), f.span(),
            f.retTypeInferred(), f.typeParamsInferred(), f.resolved());
    }

    // ---- Let 无注解右侧：只用显式声明类型 ----

    @Nested
    class LetRightHandSide {

        @ParameterizedTest(name = "右侧为{0} → 类型未知，全局唯一候选采用 + W801")
        @CsvSource(delimiter = '|', value = {
            "字面量|1",
            "ListLiteral|[x]",
            "Some|some of x",
            "IfExpr|if true then x else x",
        })
        void unknownRhsFallsBackToGlobalCandidateWithW801(String label, String rhs) {
            Run r = run(MODULE + DRIVER_DECL
                + "Rule f given x as Fahrer, produce Int:\n  Let y be " + rhs + ".\n  Return y.alter.\n", DRIVER);
            assertEquals(List.of(ErrorCode.RESOLVED_WITHOUT_TYPE), r.r2Codes(), label);
            assertEquals(Diagnostic.Severity.WARNING, r.r2().get(0).severity());
            assertEquals("y.age", nameOf(returned(func(r.module(), "f"), 1)).name);
        }

        @Test
        void constructRhsTypesAsConstructedData() {
            Run r = run(MODULE + DRIVER_DECL
                + "Rule f given x as Int, produce Int:\n  Let y be Fahrer with alter set to x.\n  Return y.alter.\n", DRIVER);
            assertEquals(List.of(), r.r2Codes());
            assertEquals("y.age", nameOf(returned(func(r.module(), "f"), 1)).name);
        }

        @Test
        void directCallRhsTypesAsDeclaredReturn() {
            Run r = run(MODULE + DRIVER_DECL
                + "Rule neu given x as Int, produce Fahrer:\n  Return Fahrer with alter set to x.\n\n"
                + "Rule f given x as Int, produce Int:\n  Let y be neu(x).\n  Return y.alter.\n", DRIVER);
            assertEquals(List.of(), r.r2Codes());
            assertEquals("y.age", nameOf(returned(func(r.module(), "f"), 1)).name);
        }

        @Test
        void bareTypedLocalInheritsDeclaredType() {
            // 裁决 R20：`Let y be x`（x 为已声明类型的局部名）继承 x 的类型
            Run r = run(MODULE + DRIVER_DECL
                + "Rule f given x as Fahrer, produce Int:\n  Let y be x.\n  Return y.alter.\n", DRIVER);
            assertEquals(List.of(), r.r2Codes());
            assertEquals("y.age", nameOf(returned(func(r.module(), "f"), 1)).name);
        }

        @Test
        void unknownTypeAndSeveralStructsReportE805() {
            Run r = run(MODULE + DRIVER_DECL + "Define Halter has alter as Int.\n\n"
                    + "Rule f given x as Int, produce Int:\n  Let y be 1.\n  Return y.alter.\n",
                concat(DRIVER, List.of(struct("Owner", "Halter"), field("Owner", "years", "alter"))));
            assertEquals(List.of(ErrorCode.AMBIGUOUS_FIELD), r.r2Codes());
        }

        @Test
        void unknownTypeAndNoCandidateReportsE801() {
            Run r = run(MODULE + DRIVER_DECL
                + "Rule f given x as Int, produce Int:\n  Let y be 1.\n  Return y.gewicht.\n", DRIVER);
            assertEquals(List.of(ErrorCode.UNRESOLVED_FIELD), r.r2Codes());
        }
    }

    // ---- 期望类型为枚举时的裸单段名 ----

    @Nested
    class ExpectedEnum {

        @Test
        void returnHochUnderLevelKeepsBareShape() {
            Run r = run(MODULE + LEVEL_DECL + "Rule f given x as Int, produce Stufe:\n  Return Hoch.\n", LEVEL);
            assertEquals(List.of(), r.all());
            CoreModel.Name n = nameOf(returned(func(r.module(), "f"), 0));
            assertEquals("High", n.name);
            assertEquals("Hoch", n.origin.written);
            assertEquals(ResolvedRole.MEMBER_REF, n.origin.role);
        }

        @Test
        void localizedAndCanonicalProgramsProduceSameCore() {
            Run de = run(MODULE + LEVEL_DECL + "Rule f given x as Stufe, produce Stufe:\n  Return Hoch.\n\n"
                + "Rule g given x as Stufe, produce Stufe:\n  Return Stufe.Niedrig.\n", LEVEL);
            Run en = run("Module m.\n\nDefine Level as one of High, Low.\n\n"
                + "Rule f given x as Level, produce Level:\n  Return High.\n\n"
                + "Rule g given x as Level, produce Level:\n  Return Level.Low.\n", List.of());
            assertEquals(List.of(), Stream.concat(de.all().stream(), en.all().stream()).toList());
            assertEquals(withoutOrigin(en.module()), withoutOrigin(de.module()));
            assertEquals("Level.Low", nameOf(returned(func(de.module(), "g"), 0)).name);
        }

        @Test
        void ruleHochAlongsideMemberReportsE804() {
            Run r = run(MODULE + LEVEL_DECL
                + "Rule hoch given x as Int, produce Int:\n  Return x.\n\n"
                + "Rule f given x as Int, produce Stufe:\n  Return Hoch.\n", LEVEL);
            assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), r.r2Codes());
            assertTrue(r.r2().get(0).message().contains("High"), r.r2().get(0).message());
        }

        @Test
        void vocabularyFunctionWithSameWritingReportsE804() {
            Run r = run(MODULE + LEVEL_DECL + "Rule f given x as Int, produce Stufe:\n  Return Hoch.\n",
                concat(LEVEL, List.of(fn("raise", "hoch"))));
            assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), r.r2Codes());
        }

        @Test
        void localHitWinsWithW800() {
            Run r = run(MODULE + LEVEL_DECL + "Rule f given hoch as Stufe, produce Stufe:\n  Return Hoch.\n", LEVEL);
            assertEquals(List.of(ErrorCode.SHADOWED_VOCABULARY), r.r2Codes());
            assertEquals("hoch", nameOf(returned(func(r.module(), "f"), 0)).name);
        }

        @Test
        void callArgumentUsesCalleeParameterType() {
            Run r = run(MODULE + LEVEL_DECL
                + "Rule g given s as Stufe, produce Stufe:\n  Return s.\n\n"
                + "Rule f given x as Int, produce Stufe:\n  Return g(Niedrig).\n", LEVEL);
            assertEquals(List.of(), r.r2Codes());
            CoreModel.Call call = assertInstanceOf(CoreModel.Call.class, returned(func(r.module(), "f"), 0));
            assertEquals("Low", nameOf(call.args.get(0)).name);
        }

        @Test
        void constructFieldUsesFieldDeclaredType() {
            Run r = run(MODULE + LEVEL_DECL + "Define Fahrer has stufe as Stufe.\n\n"
                    + "Rule f given x as Int, produce Fahrer:\n  Return Fahrer with stufe set to Hoch.\n",
                concat(LEVEL, List.of(struct("Driver", "Fahrer"), field("Driver", "level", "stufe"))));
            assertEquals(List.of(), r.r2Codes());
            CoreModel.Construct c = assertInstanceOf(CoreModel.Construct.class, returned(func(r.module(), "f"), 0));
            assertEquals("Driver", c.typeName);
            assertEquals("level", c.fields.get(0).name);
            assertEquals("High", nameOf(c.fields.get(0).expr).name);
        }

        // ---- 相等比较：一侧为已知枚举时另一侧裸名以其为期望类型（裁决 R49） ----

        private static final String CMP_DECLS = MODULE + LEVEL_DECL + "Define Fahrer has stufe as Stufe.\n\n";
        private static final List<IdentifierMapping> CMP_VOCAB =
            concat(LEVEL, List.of(struct("Driver", "Fahrer"), field("Driver", "level", "stufe")));

        private static CoreModel.Call ifCond(CoreModel.Module m) {
            return assertInstanceOf(CoreModel.Call.class, stmt(func(m, "f"), 0, CoreModel.If.class).cond);
        }

        @Test
        void equalityWithKnownEnumOperandResolvesBareMember() {
            Run r = run(CMP_DECLS + "Rule f given d as Fahrer, produce Int:\n  If d.stufe equals to Hoch:\n    Return 1.\n  Return 0.\n",
                CMP_VOCAB);
            assertEquals(List.of(), r.all());
            CoreModel.Call cond = ifCond(r.module());
            assertEquals("==", nameOf(cond.target).name);
            assertEquals("d.level", nameOf(cond.args.get(0)).name);
            CoreModel.Name member = nameOf(cond.args.get(1));
            assertEquals("High", member.name);
            assertEquals("Hoch", member.origin.written);
            assertEquals(ResolvedRole.MEMBER_REF, member.origin.role);
        }

        @Test
        void bareMemberOnLeftAndEnumLocalOnRightResolves() {
            Run r = run(CMP_DECLS + "Rule f given s as Stufe, produce Int:\n  If Niedrig equals to s:\n    Return 1.\n  Return 0.\n",
                CMP_VOCAB);
            assertEquals(List.of(), r.all());
            CoreModel.Call cond = ifCond(r.module());
            assertEquals("Low", nameOf(cond.args.get(0)).name);
            assertEquals("s", nameOf(cond.args.get(1)).name);
        }

        @Test
        void inequalityAlsoProvidesExpectedType() {
            Run r = run("Module m.\n\nDefine Level as one of High, Low.\n\n"
                + "Rule f given s as Level, produce Int:\n  If s not equal to Low:\n    Return 1.\n  Return 0.\n", List.of());
            assertEquals(List.of(), r.all());
            CoreModel.Call cond = ifCond(r.module());
            assertEquals("!=", nameOf(cond.target).name);
            assertEquals("Low", nameOf(cond.args.get(1)).name);
        }

        @Test
        void vocabularyFunctionHochInComparisonReportsE804WithQualifiedHelp() {
            Run r = run(CMP_DECLS + "Rule f given d as Fahrer, produce Int:\n  If d.stufe equals to Hoch:\n    Return 1.\n  Return 0.\n",
                concat(CMP_VOCAB, List.of(fn("high", "hoch"))));
            // error 级诊断使调用方不发出 IR，因此 R1 的函数命中不会静默进入产物
            assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), r.r2Codes());
            Diagnostic d = r.r2().get(0);
            assertTrue(d.message().contains("High (Hoch)"), d.message());
            assertTrue(d.help().orElse("").contains("Stufe.Hoch"), d.help().toString());
        }

        @Test
        void sourceRuleAndVocabularyFunctionCandidatesAreDeduplicated() {
            Run r = run(CMP_DECLS
                    + "Rule hoch given x as Int, produce Int:\n  Return x.\n\n"
                    + "Rule f given s as Stufe, produce Int:\n  If s equals to Hoch:\n    Return 1.\n  Return 0.\n",
                concat(CMP_VOCAB, List.of(fn("high", "hoch"))));
            assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), r.r2Codes());
            String message = r.r2().get(0).message();
            List<String> candidates = List.of(message.substring(message.lastIndexOf(": ") + 2).split(", "));
            assertEquals(candidates.stream().distinct().toList(), candidates, message);
        }

        @Test
        void nonEnumOtherOperandKeepsE802WithValueRole() {
            Run r = run(CMP_DECLS + "Rule f given x as Int, produce Int:\n  If x equals to Hoch:\n    Return 1.\n  Return 0.\n",
                CMP_VOCAB);
            assertEquals(List.of(ErrorCode.UNRESOLVED_NAME), r.r2Codes());
            Diagnostic d = r.r2().get(0);
            assertTrue(d.message().contains("VALUE_REF"), d.message());
            assertTrue(d.help().orElse("").contains("Stufe.Hoch"), d.help().toString());
        }

        @Test
        void bareMemberWithoutExpectedTypeReportsE802() {
            Run r = run(MODULE + LEVEL_DECL
                + "Rule f given x as Int, produce Int:\n  Let y be Hoch.\n  Return x.\n", LEVEL);
            assertEquals(List.of(ErrorCode.UNRESOLVED_NAME), r.r2Codes());
        }

        @Test
        void unknownBareNameReportsE802ButOperatorsAndR1NamesDoNot() {
            Run r = run(MODULE + "Rule f given x as Int, produce Int:\n  Return x plus unbekannt.\n", List.of());
            assertEquals(List.of(ErrorCode.UNRESOLVED_NAME), r.r2Codes());
            assertTrue(r.r2().get(0).message().contains("unbekannt"), r.r2().get(0).message());
        }
    }

    // ---- 模式匹配 ----

    @Nested
    class Patterns {

        @Test
        void bareMemberPatternUnderSubjectEnumResolves() {
            // Java 把裸 `When Hoch,` 解析为无参数的 PatCtor（TS 为 PatName）：按裸名规则解析（裁决 R12）
            Run r = run(MODULE + LEVEL_DECL
                + "Rule f given s as Stufe, produce Int:\n  Match s:\n    When Hoch, Return 1.\n    When andere, Return 2.\n",
                LEVEL);
            assertEquals(List.of(), r.r2Codes());
            List<CoreModel.Case> cases = stmt(func(r.module(), "f"), 0, CoreModel.Match.class).cases;
            CoreModel.PatCtor first = assertInstanceOf(CoreModel.PatCtor.class, cases.get(0).pattern);
            assertEquals("High", first.typeName);
            assertEquals("Hoch", first.origin.written);
            assertEquals(ResolvedRole.MEMBER_REF, first.origin.role);
            assertEquals("andere", assertInstanceOf(CoreModel.PatName.class, cases.get(1).pattern).name);
        }

        @Test
        void constructedLetSubjectResolvesCtorPattern() {
            Run r = run(MODULE + DRIVER_DECL
                + "Rule f given x as Int, produce Int:\n  Let d be Fahrer with alter set to x.\n"
                + "  Match d:\n    When Fahrer(a), Return a.\n", DRIVER);
            assertEquals(List.of(), r.r2Codes());
            CoreModel.PatCtor pat = assertInstanceOf(CoreModel.PatCtor.class,
                stmt(func(r.module(), "f"), 1, CoreModel.Match.class).cases.get(0).pattern);
            assertEquals("Driver", pat.typeName);
            assertEquals(List.of("a"), pat.names);
        }

        @Test
        void unknownSubjectUniqueMemberAdoptedWithW801() {
            // TS 另有 `bei Hoch()`（空参数构造器）：Java 语法不接受空括号，两处均为裸名
            Run r = run(MODULE + LEVEL_DECL
                + "Rule f given x as Int, produce Int:\n  Let s be 1.\n"
                + "  Match s:\n    When Hoch, Return 1.\n    When Niedrig, Return 2.\n", LEVEL);
            assertEquals(List.of(ErrorCode.RESOLVED_WITHOUT_TYPE, ErrorCode.RESOLVED_WITHOUT_TYPE), r.r2Codes());
            List<CoreModel.Case> cases = stmt(func(r.module(), "f"), 1, CoreModel.Match.class).cases;
            assertEquals("High", ((CoreModel.PatCtor) cases.get(0).pattern).typeName);
            assertEquals("Low", ((CoreModel.PatCtor) cases.get(1).pattern).typeName);
        }

        @Test
        void unknownSubjectSeveralCandidatesReportE806() {
            Run r = run(MODULE + LEVEL_DECL + "Define Rang as one of Hoch, Mittel.\n\n"
                    + "Rule f given x as Int, produce Int:\n  Let s be 1.\n"
                    + "  Match s:\n    When Hoch(z), Return 1.\n    When Hoch, Return 2.\n",
                concat(LEVEL, List.of(struct("Priority", "Rang"), member("Priority", "Top", "Hoch"), member("Priority", "Mid", "Mittel"))));
            assertEquals(List.of(ErrorCode.AMBIGUOUS_PATTERN, ErrorCode.AMBIGUOUS_PATTERN), r.r2Codes());
        }

        @Test
        void unknownSubjectNoCandidateCtorReportsE803AndLowercaseStaysBinding() {
            Run r = run(MODULE + "Rule f given x as Int, produce Int:\n  Let s be 1.\n"
                + "  Match s:\n    When Riesig(z), Return 1.\n    When wert, Return wert.\n", List.of());
            assertEquals(List.of(ErrorCode.UNRESOLVED_PATTERN), r.r2Codes());
            List<CoreModel.Case> cases = stmt(func(r.module(), "f"), 1, CoreModel.Match.class).cases;
            assertEquals("wert", ((CoreModel.PatName) cases.get(1).pattern).name);
        }
    }

    // ---- 其它位置 ----

    @Nested
    class OtherPositions {

        @Test
        void ufcsMethodNameResolvesAsFunction() {
            Run r = run(MODULE + DRIVER_DECL
                    + "Rule berechne given d as Fahrer, produce Int:\n  Return d.alter.\n\n"
                    + "Rule f given x as Fahrer, produce Int:\n  Return x.berechne().\n",
                concat(DRIVER, List.of(fn("compute", "berechne"))));
            assertEquals(List.of(), r.r2Codes());
            CoreModel.Call call = assertInstanceOf(CoreModel.Call.class, returned(func(r.module(), "f"), 0));
            assertEquals("compute", nameOf(call.target).name);
            assertEquals("x", nameOf(call.args.get(0)).name);
        }

        @Test
        void qualifiedCallAndUfcsNamespaceReceiverNotE802() {
            Run r = run("Module m.\n\nRule f given action as Text, produce Bool. It performs io:\n"
                + "  Let x be io.get(action).\n  Return Action.equals(action, \"a\").\n", List.of());
            assertEquals(List.of(), r.r2Codes());
            CoreModel.Call call = assertInstanceOf(CoreModel.Call.class, returned(func(r.module(), "f"), 1));
            assertEquals("Action.equals", nameOf(call.target).name);
            CoreModel.Call ufcs = assertInstanceOf(CoreModel.Call.class, stmt(func(r.module(), "f"), 0, CoreModel.Let.class).expr);
            assertEquals(List.of("get", "io"), List.of(nameOf(ufcs.target).name, nameOf(ufcs.args.get(0)).name));
        }

        @Test
        void lambdaExpectedTypeFromDeclaredReturnAndCapturesRewritten() {
            Run r = run("Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
                + "Rule f given outer as Int, produce Int:\n"
                + "  Let g be function with x as Int, produce Stufe:\n"
                + "    Let unused be outer.\n"
                + "    Return Hoch.\n"
                + "  Return outer.\n", LEVEL);
            assertEquals(List.of(), r.r2Codes());
            CoreModel.Lambda lambda = assertInstanceOf(CoreModel.Lambda.class, stmt(func(r.module(), "f"), 0, CoreModel.Let.class).expr);
            CoreModel.Return ret = assertInstanceOf(CoreModel.Return.class, lambda.body.statements.get(1));
            assertEquals("High", nameOf(ret.expr).name);
            assertEquals(List.of("outer", "High"), lambda.captures);
        }
    }

    // ---- 裁决 R19：未知调用目标交由类型检查 ----

    @Test
    void unknownCallTargetKeepsWrittenFormValuePositionStillReports() {
        Run r = run(MODULE + "Rule f given x as Int, produce Int:\n  Return unbekannt(x).\n\n"
            + "Rule g given x as Int, produce Int:\n  Return fehlend.\n", List.of());
        assertEquals(List.of(ErrorCode.UNRESOLVED_NAME), r.r2Codes());
        assertTrue(r.r2().get(0).message().contains("fehlend"), r.r2().get(0).message());
        CoreModel.Call call = assertInstanceOf(CoreModel.Call.class, returned(func(r.module(), "f"), 0));
        assertEquals("unbekannt", nameOf(call.target).name);
    }

    // ---- Core 不变量 ----

    @Nested
    class CoreInvariants {

        private static final String SOURCE = MODULE + LEVEL_DECL
            + "Define Fahrer has alter as Int, stufe as Stufe.\n\n"
            + "Rule bewerten given fahrer as Fahrer, produce Stufe:\n"
            + "  Let a be fahrer.alter.\n"
            + "  Match fahrer.stufe:\n    When Hoch, Return Niedrig.\n    When Niedrig, Return Hoch.\n";
        private static final List<IdentifierMapping> VOCAB =
            concat(LEVEL, DRIVER, List.of(field("Driver", "level", "stufe"), fn("check", "bewerten")));
        private static final List<String> LOCALIZED = List.of("Stufe", "Hoch", "Niedrig", "Fahrer", "alter", "stufe", "bewerten");

        @Test
        void noLocalizedNamesAndWrittenRoleOnlyWhenDifferent() {
            Run r = run(SOURCE, VOCAB);
            assertEquals(List.of(), r.all());
            List<String> names = new ArrayList<>();
            List<JsonNode> origins = new ArrayList<>();
            collect(MAPPER.valueToTree(r.module()), names, origins);
            assertEquals(List.of(), names.stream().filter(LOCALIZED::contains).toList());
            for (JsonNode o : origins) {
                assertEquals(o.has("written"), o.has("role"), "written 与 role 同时出现：" + o);
            }
            List<String> written = origins.stream().filter(o -> o.has("written")).map(o -> o.get("written").asText()).toList();
            assertTrue(written.contains("fahrer.alter"), written.toString());
            assertTrue(written.contains("Hoch"), written.toString());
            CoreModel.Func f = func(r.module(), "check");
            assertEquals("bewerten", f.origin.written);
            assertEquals(ResolvedRole.FUNC_DECL, f.origin.role);
            assertNull(stmt(f, 0, CoreModel.Let.class).origin.written, "局部名 a 未改写");
        }

        @Test
        void canonicalSourceHasNoWrittenOrRole() {
            Run r = run("Module m.\n\nDefine Driver has age as Int.\n\nRule f given d as Driver, produce Int:\n  Return d.age.\n",
                DRIVER);
            assertEquals(List.of(), r.r2Codes());
            List<JsonNode> origins = new ArrayList<>();
            collect(MAPPER.valueToTree(r.module()), new ArrayList<>(), origins);
            assertEquals(List.of(), origins.stream().filter(o -> o.has("written") || o.has("role")).toList());
        }

        @Test
        void anyE8xxMakesDiagnosticsNonEmpty() {
            Run r = run(MODULE + DRIVER_DECL + "Rule f given x as Fahrer, produce Int:\n  Return x.gewicht.\n", DRIVER);
            assertTrue(r.r2().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR
                && d.code().code().startsWith("E80")));
        }

        @Test
        void marksAreReadOnlyAndNotCarriedToOutput() {
            ResolutionContext ctx = ctxOf(DRIVER);
            ResolutionResult<Module> r1 = DeclarationResolver.resolve(DeclarationResolverTest.parse(
                MODULE + DRIVER_DECL + "Rule f given x as Fahrer, produce Int:\n  Return x.alter.\n"), ctx);
            CoreModel.Module core = new CoreLowering().lowerModule(r1.value());
            CoreModel.Name before = nameOf(returned(func(core, "f"), 0));
            assertEquals("x.alter", before.name);
            assertNotNull(before.mark);
            CoreModel.Module out = TypedReferenceResolver.resolve(core, ctx).value();
            assertEquals("x.alter", before.name);
            assertNotNull(before.mark, "输入标记不被删除");
            assertNull(nameOf(returned(func(out, "f"), 0)).mark);
        }
    }

    private static void collect(JsonNode node, List<String> names, List<JsonNode> origins) {
        if (node.isArray()) {
            node.forEach(x -> collect(x, names, origins));
            return;
        }
        if (!node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (k.equals("origin")) {
                if (v.isObject()) origins.add(v);
            } else if (v.isTextual() && List.of("name", "typeName", "base").contains(k)) {
                names.addAll(List.of(v.asText().split("\\.")));
            } else if ((k.equals("variants") || k.equals("names")) && v.isArray()) {
                v.forEach(x -> names.add(x.asText()));
            } else {
                collect(v, names, origins);
            }
        }
    }

    private static String withoutOrigin(CoreModel.Module m) {
        JsonNode tree = MAPPER.valueToTree(m);
        strip(tree);
        return tree.toString();
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode o) o.remove("origin");
        node.forEach(TypedReferenceResolverTest::strip);
    }

    // ---- 裁决 R16：规范名直写不告警、动态字段透传 ----

    @Nested
    class CanonicalDirectWriting {

        private static final String TIER = "Module m.\n\nDefine Tier as one of Gold, Silver.\n\n";

        @Test
        void canonicalFieldAndMemberWithUnknownTypeNoW801() {
            Run r = run(TIER + "Define Driver has age as Int.\n\n"
                + "Rule f given x as Int, produce Int:\n  Let y be 1.\n  Let a be y.age.\n"
                + "  Match y:\n    When Gold, Return 1.\n    When Silver, Return a.\n", List.of());
            assertEquals(List.of(), r.all());
            assertEquals("y.age", nameOf(stmt(func(r.module(), "f"), 1, CoreModel.Let.class).expr).name);
            List<CoreModel.Case> cases = stmt(func(r.module(), "f"), 2, CoreModel.Match.class).cases;
            assertEquals("Gold", ((CoreModel.PatCtor) cases.get(0).pattern).typeName);
            assertEquals("Silver", ((CoreModel.PatCtor) cases.get(1).pattern).typeName);
        }

        @Test
        void bareCanonicalMemberWithoutExpectedTypeAcceptedAndUnderEnumNotRewritten() {
            Run r = run(TIER + "Rule f given t as Text:\n  Return Gold.\n\n"
                + "Rule g given t as Text, produce Bool:\n  Return Text.equals(t, Silver).\n\n"
                + "Rule h given t as Text, produce Tier:\n  Return Gold.\n", List.of());
            assertEquals(List.of(), r.all());
            assertEquals("Gold", nameOf(returned(func(r.module(), "f"), 0)).name);
            CoreModel.Call call = assertInstanceOf(CoreModel.Call.class, returned(func(r.module(), "g"), 0));
            assertEquals("Silver", nameOf(call.args.get(1)).name);
            CoreModel.Name h = nameOf(returned(func(r.module(), "h"), 0));
            assertEquals("Gold", h.name);
            assertNull(h.origin.written);
        }

        @Test
        void canonicalMemberOfSeveralEnumsWithoutExpectedTypeReportsE804() {
            Run r = run(TIER + "Define Medal as one of Gold, Bronze.\n\nRule f given t as Text:\n  Return Gold.\n",
                List.of());
            assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), r.r2Codes());
        }

        @Test
        void segmentsAfterUntypedFieldAreDynamicKeys() {
            Run r = run("Module m.\n\nDefine GuardRequest has action, context.\n\n"
                + "Rule decide given req as GuardRequest, produce Int:\n"
                + "  Let large be req.context.amount.\n  Let c be req.context.\n  Let b be c.limit.\n"
                + "  Let n be req.action.size.\n  Return 1.\n", List.of());
            assertEquals(List.of(), r.all());
            CoreModel.Func f = func(r.module(), "decide");
            assertEquals("req.context.amount", nameOf(stmt(f, 0, CoreModel.Let.class).expr).name);
            assertEquals("c.limit", nameOf(stmt(f, 2, CoreModel.Let.class).expr).name);
            assertEquals("req.action.size", nameOf(stmt(f, 3, CoreModel.Let.class).expr).name);
        }

        @Test
        void segmentsAfterKnownNonDataTypeKeptVerbatim() {
            Run r = run("Module m.\n\nRule f given t as Text, produce Int:\n  Let n be t.size.\n  Return 1.\n", List.of());
            assertEquals(List.of(), r.r2Codes());
        }

        @Test
        void fieldSegmentOnTypeParameterReportsE801() {
            Run r = run("Module m.\n\nRule f given x as Foo, produce Int:\n  Return x.age.\n", List.of());
            assertEquals(List.of(ErrorCode.UNRESOLVED_FIELD), r.r2Codes());
        }

        @Test
        void lambdaLocalsAndOperatorsNotE802() {
            Run r = run("Module m.\n\nRule f given x as Int, produce Int:\n"
                + "  Let inc be function with v as Int, produce Int:\n    Return v plus 1.\n"
                + "  Return inc(x) times 2.\n", List.of());
            assertEquals(List.of(), r.all());
        }
    }

    // ---- 透传白名单、标记幂等与期望枚举裁决 ----

    @Nested
    class WhitelistIdempotencyAndEnumRulings {

        private static final String EN_DRIVER = "Module m.\n\nDefine Fahrer has alter as Int.\n\n";

        @ParameterizedTest(name = "接收者类型为{0} → 不透传，按全局候选采用 + W801")
        @CsvSource(delimiter = '|', value = {
            "类型别名|type Fz as Fahrer.\\n\\nRule f given x as Fz, produce Int:\\n  Return x.alter.\\n",
            "Option of|Rule f given x as Option of Fahrer, produce Int:\\n  Return x.alter.\\n",
            "可空 ?|Rule f given x as Fahrer?, produce Int:\\n  Return x.alter.\\n",
            "未声明类型名（Data 字段）|Define Halter has fz as Unbekannt.\\n\\nRule f given x as Halter, produce Int:\\n  Return x.fz.alter.\\n",
        })
        void wrappedOrUndeclaredReceiverFallsBackWithW801(String label, String body) {
            Run r = run(EN_DRIVER + body.replace("\\n", "\n"), DRIVER);
            assertEquals(List.of(ErrorCode.RESOLVED_WITHOUT_TYPE), r.r2Codes(), label);
            assertTrue(nameOf(returned(func(r.module(), "f"), 0)).name.endsWith(".age"));
        }

        @Test
        void twoRunsOnSameCoreAreIdentical() throws Exception {
            ResolutionContext ctx = ctxOf(DRIVER);
            ResolutionResult<Module> r1 = DeclarationResolver.resolve(DeclarationResolverTest.parse(MODULE + DRIVER_DECL
                + "Rule f given x as Int, produce Int:\n  Let y be 1.\n  Let z be hoch.\n  Return y.alter.\n"), ctx);
            CoreModel.Module core = new CoreLowering().lowerModule(r1.value());
            ResolutionResult<CoreModel.Module> a = TypedReferenceResolver.resolve(core, ctx);
            ResolutionResult<CoreModel.Module> b = TypedReferenceResolver.resolve(core, ctx);
            assertEquals(List.of(ErrorCode.UNRESOLVED_NAME, ErrorCode.RESOLVED_WITHOUT_TYPE), codes(a.diagnostics()));
            assertEquals(codes(a.diagnostics()), codes(b.diagnostics()));
            assertEquals(a.diagnostics().stream().map(Diagnostic::message).toList(),
                b.diagnostics().stream().map(Diagnostic::message).toList());
            assertEquals(MAPPER.writeValueAsString(a.value()), MAPPER.writeValueAsString(b.value()));
            assertEquals("y.age", nameOf(returned(func(b.value(), "f"), 2)).name);
        }

        @Test
        void otherEnumsSameMemberDoNotConflictUnderExpectedEnum() {
            Run r = run("Module m.\n\nDefine Tier as one of Gold, Silver.\n\nDefine Medal as one of Gold, Bronze.\n\n"
                + "Rule f given x as Int, produce Tier:\n  Return Gold.\n", List.of());
            assertEquals(List.of(), r.all());
            assertEquals("Gold", nameOf(returned(func(r.module(), "f"), 0)).name);
        }

        @Test
        void localExactBeatsMemberFoldAmbiguity() {
            Run r = run(MODULE + "Define Stufe as one of Hoch, HOCH.\n\n"
                    + "Rule f given hoch as Stufe, produce Stufe:\n  Return hoch.\n",
                List.of(struct("Level", "Stufe"), member("Level", "High", "Hoch"), member("Level", "Top", "HOCH")));
            assertEquals(List.of(), r.all());
            assertEquals("hoch", nameOf(returned(func(r.module(), "f"), 0)).name);
        }

        @Test
        void localShadowingModuleFunctionDoesNotTakeItsReturnType() {
            Run r = run(EN_DRIVER + "Rule make given x as Int, produce Fahrer:\n  Return Fahrer with alter set to x.\n\n"
                + "Rule f given x as Int, produce Int:\n"
                + "  Let make be function with v as Int, produce Int:\n    Return v.\n"
                + "  Let y be make(x).\n  Return y.alter.\n", DRIVER);
            assertEquals(List.of(ErrorCode.RESOLVED_WITHOUT_TYPE), r.r2Codes());
        }

        @Test
        void noDuplicateE804WhenR1AlreadyReported() {
            Run r = run(MODULE + LEVEL_DECL + "Rule f given x as Int, produce Stufe:\n  Return hoch.\n",
                concat(LEVEL, List.of(fn("raise", "HoCh"), fn("lift", "HOCH"))));
            assertEquals(List.of(ErrorCode.AMBIGUOUS_NAME), r.all());
        }
    }

    // ---- 裁决 R30：未注解参数类型未知、关键词构造器不是待解析的名字 ----

    @Nested
    class UntypedParametersAndKeywordCtors {

        @Test
        void untypedParamPathFallsBackToGlobalCandidateWithW801() {
            Run r = run(MODULE + DRIVER_DECL + "Rule f given fahrer, produce Int:\n  Return fahrer.alter.\n", DRIVER);
            assertEquals(List.of(ErrorCode.RESOLVED_WITHOUT_TYPE), r.r2Codes());
            assertEquals("fahrer.age", nameOf(returned(func(r.module(), "f"), 0)).name);
        }

        @Test
        void untypedSubjectBareMemberAdoptedWithW801() {
            Run r = run(MODULE + LEVEL_DECL
                + "Rule f given s, produce Int:\n  Match s:\n    When Hoch, Return 1.\n    When andere, Return 2.\n", LEVEL);
            assertEquals(List.of(ErrorCode.RESOLVED_WITHOUT_TYPE), r.r2Codes());
            List<CoreModel.Case> cases = stmt(func(r.module(), "f"), 0, CoreModel.Match.class).cases;
            assertEquals("High", ((CoreModel.PatCtor) cases.get(0).pattern).typeName);
        }

        @Test
        void untypedParamUnknownFieldReportsE801() {
            Run r = run(MODULE + DRIVER_DECL + "Rule f given x, produce Int:\n  Return x.foo.\n", DRIVER);
            assertEquals(List.of(ErrorCode.UNRESOLVED_FIELD), r.r2Codes());
        }

        @Test
        void untypedLambdaParamPathFallsBackWithW801() {
            Run r = run(MODULE + DRIVER_DECL + "Rule f given x as Int, produce Int:\n"
                + "  Let g be function with fahrer, produce Int:\n    Return fahrer.alter.\n  Return x.\n", DRIVER);
            assertEquals(List.of(ErrorCode.RESOLVED_WITHOUT_TYPE), r.r2Codes());
            CoreModel.Lambda lambda = assertInstanceOf(CoreModel.Lambda.class, stmt(func(r.module(), "f"), 0, CoreModel.Let.class).expr);
            assertEquals("fahrer.age", nameOf(((CoreModel.Return) lambda.body.statements.get(0)).expr).name);
        }

        @Test
        void untypedLambdaParamUnknownFieldReportsE801() {
            Run r = run(MODULE + DRIVER_DECL + "Rule f given x as Int, produce Int:\n"
                + "  Let g be function with y, produce Int:\n    Return y.foo.\n  Return x.\n", DRIVER);
            assertEquals(List.of(ErrorCode.UNRESOLVED_FIELD), r.r2Codes());
        }

        @Test
        void keywordCtorNameIsNotE802() {
            Run r = run(MODULE + "Rule f given x as Int, produce Int:\n  Let g be Some.\n  Return x.\n", List.of());
            assertEquals(List.of(), r.all());
        }
    }

    // ---- lower 在未运行 R1 时行为不变 ----

    @Test
    void lowerWithoutR1KeepsWrittenNamesAndNoWrittenRole() throws Exception {
        Module ast = DeclarationResolverTest.parse(MODULE + DRIVER_DECL
            + "Rule f given x as Fahrer, produce Int:\n  Return x.alter.\n");
        CoreModel.Module core = new CoreLowering().lowerModule(ast);
        String json = MAPPER.writeValueAsString(core);
        assertFalse(json.contains("\"written\""));
        assertFalse(json.contains("\"role\""));
        assertEquals("x.alter", nameOf(returned(func(core, "f"), 0)).name);
        assertEquals("Fahrer", ((CoreModel.Data) core.decls.get(0)).name);
    }
}
