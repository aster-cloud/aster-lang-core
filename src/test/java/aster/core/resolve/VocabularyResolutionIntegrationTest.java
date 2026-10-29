package aster.core.resolve;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierIndex;
import aster.core.identifier.IdentifierKind;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreModel;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域词汇按位置解析接入 Java 编译流水线（ADR 0051 §1、§4、§8）。
 *
 * <p>端到端：canonicalize（关键词 / 字面量宏）→ 解析 → R1 → lower → R2 → 类型检查。覆盖 §1 四个生产复现、
 * 解析前标识符替换已删除、空词汇 / 只含字面量宏时 IR 逐字节不变、E8xx 不产出 IR。用例与 TS
 * vocabulary-resolution.test.ts 对应；Java 内置词法只有 en-US，故关键词写英文、标识符保持德语写法。
 * aster-lang-core 没有解释器（执行在 aster-lang-truffle），此处止于类型检查。
 */
class VocabularyResolutionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static VocabularyScopes scopesOf(List<IdentifierMapping> mappings) {
        DomainVocabulary vocab = new DomainVocabulary("test.vocab", "test", "de-DE", "1",
            ofKind(mappings, IdentifierKind.STRUCT), ofKind(mappings, IdentifierKind.FIELD),
            ofKind(mappings, IdentifierKind.FUNCTION), ofKind(mappings, IdentifierKind.ENUM_VALUE),
            ofKind(mappings, IdentifierKind.LITERAL), null);
        return VocabularyScopes.build(vocab);
    }

    /** canonicalize（只展开字面量宏）→ 解析 → R1 → lower → R2。 */
    private static NameResolution.ResolvedCore compile(String source, VocabularyScopes scopes) {
        String canonical = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), scopes).canonicalize(source);
        AsterParser parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical))));
        return NameResolution.resolveAndLower(new AstBuilder().visitModule(parser.module()), new ResolutionContext(scopes, null));
    }

    /** 编译 + 类型检查必须完全干净（无名称解析 / 类型错误），返回 Core。 */
    private static CoreModel.Module compileClean(String source, VocabularyScopes scopes) {
        NameResolution.ResolvedCore resolved = compile(source, scopes);
        assertEquals(List.of(), errors(resolved.diagnostics()), "名称解析");
        assertEquals(List.of(), errors(new TypeChecker().typecheckModule(resolved.core())), "类型检查");
        return resolved.core();
    }

    private static List<String> errors(List<Diagnostic> ds) {
        return ds.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR)
            .map(d -> d.code().code() + " " + d.message()).toList();
    }

    private static CoreModel.Data dataOf(CoreModel.Module core, String name) {
        return core.decls.stream().filter(d -> d instanceof CoreModel.Data x && x.name.equals(name))
            .map(CoreModel.Data.class::cast).findFirst().orElseThrow(() -> new AssertionError("缺少 Data " + name));
    }

    private static CoreModel.Func funcOf(CoreModel.Module core, String name) {
        return core.decls.stream().filter(d -> d instanceof CoreModel.Func x && x.name.equals(name))
            .map(CoreModel.Func.class::cast).findFirst().orElseThrow(() -> new AssertionError("缺少 Func " + name));
    }

    private static List<String> fieldNames(CoreModel.Data d) {
        return d.fields.stream().map(f -> f.name).toList();
    }

    private static String withoutOrigin(CoreModel.Module m) {
        JsonNode tree = MAPPER.valueToTree(m);
        strip(tree);
        return tree.toString();
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode o) o.remove("origin");
        node.forEach(VocabularyResolutionIntegrationTest::strip);
    }

    // §1 复现 1 + 3：类型 Stufe 与字段 stufe 并存；参数 fahrer 与类型 Fahrer 同词
    private static final String LEVEL_FIELD_SOURCE = "Module versicherung.\n\n"
        + "Define Stufe has punkte as Int.\n\n"
        + "Define Fahrer has stufe as Stufe, alter as Int.\n\n"
        + "Rule bewerten given fahrer as Fahrer, produce Int:\n"
        + "  Return fahrer.stufe.punkte plus fahrer.alter.\n";

    private static final List<IdentifierMapping> LEVEL_FIELD_VOCAB = List.of(
        struct("Level", "Stufe"), struct("Driver", "Fahrer"),
        field("Level", "points", "punkte"), field("Driver", "level", "stufe"), field("Driver", "age", "alter"));

    // §1 复现 2：规则 hoch 与枚举成员 Hoch 并存，限定写法 Stufe.Hoch
    private static String enumSource(String ret) {
        return "Module versicherung.\n\n"
            + "Define Stufe as one of Hoch, Niedrig.\n\n"
            + "Rule hoch given x as Int, produce Int:\n  Return x.\n\n"
            + "Rule einstufen given x as Int, produce Stufe:\n  Return " + ret + ".\n";
    }

    private static final List<IdentifierMapping> ENUM_VOCAB = List.of(
        struct("Level", "Stufe"), member("Level", "High", "Hoch"), member("Level", "Low", "Niedrig"), fn("high", "hoch"));

    // §1 复现 4：同一本地化字段名 alter 在两个 struct 中对应不同规范字段
    private static final String SAME_FIELD_SOURCE = "Module versicherung.\n\n"
        + "Define Fahrer has alter as Int.\n\n"
        + "Define Fahrzeug has alter as Int.\n\n"
        + "Rule summe given fahrer as Fahrer, wagen as Fahrzeug, produce Int:\n"
        + "  Return fahrer.alter plus wagen.alter.\n";

    private static final List<IdentifierMapping> SAME_FIELD_VOCAB = List.of(
        struct("Driver", "Fahrer"), struct("Vehicle", "Fahrzeug"),
        field("Driver", "age", "alter"), field("Vehicle", "vehicleAge", "alter"));

    @Nested
    class ProductionReproductions {

        @Test
        void levelTypeAndLevelFieldCoexistAndParameterFahrerUntouched() {
            CoreModel.Module core = compileClean(LEVEL_FIELD_SOURCE, scopesOf(LEVEL_FIELD_VOCAB));
            assertEquals(List.of("level", "age"), fieldNames(dataOf(core, "Driver")));
            assertEquals(List.of("points"), fieldNames(dataOf(core, "Level")));
            CoreModel.Func f = funcOf(core, "bewerten");
            assertEquals(List.of("fahrer"), f.params.stream().map(p -> p.name).toList());
            CoreModel.Call plus = (CoreModel.Call) ((CoreModel.Return) f.body.statements.get(0)).expr;
            assertEquals("fahrer.level.points", ((CoreModel.Name) plus.args.get(0)).name);
            assertEquals("fahrer.age", ((CoreModel.Name) plus.args.get(1)).name);
        }

        @Test
        void localizedAndCanonicalTwinProduceSameCore() {
            CoreModel.Module localized = compileClean(LEVEL_FIELD_SOURCE, scopesOf(LEVEL_FIELD_VOCAB));
            CoreModel.Module canonical = compileClean("Module versicherung.\n\n"
                + "Define Level has points as Int.\n\n"
                + "Define Driver has level as Level, age as Int.\n\n"
                + "Rule bewerten given fahrer as Driver, produce Int:\n"
                + "  Return fahrer.level.points plus fahrer.age.\n", null);
            assertEquals(withoutOrigin(canonical), withoutOrigin(localized));
        }

        @Test
        void qualifiedStufeHochCompilesAndTypechecksAlongsideRuleHoch() {
            CoreModel.Module core = compileClean(enumSource("Stufe.Hoch"), scopesOf(ENUM_VOCAB));
            assertTrue(core.decls.stream().anyMatch(d -> d instanceof CoreModel.Func f && f.name.equals("high")));
            CoreModel.Func f = funcOf(core, "einstufen");
            assertEquals("Level.High", ((CoreModel.Name) ((CoreModel.Return) f.body.statements.get(0)).expr).name);
        }

        @Test
        void bareHochReportsE804AndProducesNoIr() {
            NameResolution.ResolvedCore resolved = compile(enumSource("Hoch"), scopesOf(ENUM_VOCAB));
            assertTrue(resolved.diagnostics().stream().anyMatch(d -> d.code() == ErrorCode.AMBIGUOUS_NAME),
                resolved.diagnostics().toString());
            String canonical = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"))
                .canonicalize(enumSource("Hoch"));
            AsterParser parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical))));
            var ast = new AstBuilder().visitModule(parser.module());
            NameResolutionException e = assertThrows(NameResolutionException.class,
                () -> NameResolution.resolveAndLowerChecked(ast, new ResolutionContext(scopesOf(ENUM_VOCAB), null)));
            assertTrue(e.diagnostics().stream().allMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
            assertTrue(e.getMessage().startsWith("E804"), e.getMessage());
        }

        @Test
        void sameLocalizedFieldResolvesPerOwningStruct() {
            CoreModel.Module core = compileClean(SAME_FIELD_SOURCE, scopesOf(SAME_FIELD_VOCAB));
            assertEquals(List.of("age"), fieldNames(dataOf(core, "Driver")));
            assertEquals(List.of("vehicleAge"), fieldNames(dataOf(core, "Vehicle")));
            CoreModel.Call plus = (CoreModel.Call) ((CoreModel.Return) funcOf(core, "summe").body.statements.get(0)).expr;
            assertEquals("fahrer.age", ((CoreModel.Name) plus.args.get(0)).name);
            assertEquals("wagen.vehicleAge", ((CoreModel.Name) plus.args.get(1)).name);
        }
    }

    @Nested
    class DiagnosticsAndResult {

        @Test
        void anyE8xxIsAnErrorAndCheckedEntryThrows() {
            String src = "Module m.\n\nDefine Fahrer has alter as Int.\n\n"
                + "Rule f given x as Fahrer, produce Int:\n  Return x.gewicht.\n";
            NameResolution.ResolvedCore resolved = compile(src, scopesOf(List.of()));
            assertTrue(NameResolution.hasErrors(resolved.diagnostics()));
            assertTrue(resolved.diagnostics().stream().anyMatch(d -> d.code() == ErrorCode.UNRESOLVED_FIELD));
        }

        @Test
        void w8xxDoesNotBlock() {
            String src = "Module m.\n\nDefine Stufe as one of Hoch, Niedrig.\n\n"
                + "Rule f given hoch as Stufe, produce Stufe:\n  Return Hoch.\n";
            NameResolution.ResolvedCore resolved = compile(src, scopesOf(ENUM_VOCAB));
            assertFalse(NameResolution.hasErrors(resolved.diagnostics()), resolved.diagnostics().toString());
            assertTrue(resolved.diagnostics().stream().anyMatch(d -> d.code() == ErrorCode.SHADOWED_VOCABULARY));
        }

        @Test
        void importAliasQualifiedCallNeedsNoImportDeclarations() {
            String src = "Module m.\n\nUse mock.remote as R.\n\nRule f given x as Int, produce Int:\n  Return R.calc(x).\n";
            NameResolution.ResolvedCore resolved = compile(src, scopesOf(List.of(fn("compute", "calc"))));
            assertEquals(List.of(), resolved.diagnostics());
            assertTrue(MAPPER.valueToTree(resolved.core()).toString().contains("\"R.calc\""));
        }
    }

    @Nested
    class PreParseIdentifierTranslationRemoved {

        @Test
        void noTranslateIdentifiersInSources() throws IOException {
            String needle = "translate" + "Identifiers";
            List<Path> hits;
            try (Stream<Path> walk = Files.walk(Path.of("src"))) {
                hits = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> contains(p, needle)).toList();
            }
            assertEquals(List.of(), hits);
        }

        @Test
        void identifierIndexHasNoFlatTranslation() {
            List<String> names = Arrays.stream(IdentifierIndex.class.getMethods()).map(Method::getName).toList();
            assertFalse(names.contains("canonicalize"), names.toString());
            assertFalse(names.contains("getToCanonicalMap"), names.toString());
        }

        @Test
        void localNameSameAsVocabularyTypeKeepsWrittenForm() {
            String src = "Module m.\n\nRule f given stufe as Int, produce Int:\n  Return stufe.\n";
            CoreModel.Module core = compileClean(src, scopesOf(LEVEL_FIELD_VOCAB));
            assertEquals(List.of("stufe"), funcOf(core, "f").params.stream().map(p -> p.name).toList());
        }
    }

    private static boolean contains(Path p, String needle) {
        try {
            return Files.readString(p).contains(needle);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    class EmptyOrLiteralOnlyVocabularyKeepsIr {

        @Test
        void irByteIdenticalToNoVocabulary() throws Exception {
            for (String src : List.of(LEVEL_FIELD_SOURCE, SAME_FIELD_SOURCE, enumSource("Stufe.Hoch"))) {
                String none = MAPPER.writeValueAsString(compile(src, null).core());
                String empty = MAPPER.writeValueAsString(compile(src, scopesOf(List.of())).core());
                String literalsOnly = MAPPER.writeValueAsString(
                    compile(src, scopesOf(List.of(IdentifierMapping.literal("Hallo Welt", "gruss")))).core());
                assertEquals(none, empty);
                assertEquals(none, literalsOnly);
            }
        }

        @Test
        void literalMacroStillExpandsToStringLiteral() {
            String src = "Module m.\n\nRule f given x as Int, produce Text:\n  Return gruss.\n";
            CoreModel.Module core = compileClean(src, scopesOf(List.of(IdentifierMapping.literal("Hallo Welt", "gruss"))));
            CoreModel.Expr ret = ((CoreModel.Return) funcOf(core, "f").body.statements.get(0)).expr;
            assertEquals("Hallo Welt", ((CoreModel.StringE) ret).value);
        }
    }
}
