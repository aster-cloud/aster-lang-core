package aster.core.lowering;

import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.ParseDiagnostics;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lowering 产出 Core IR v2（ADR 0055 #3、#4、#5、#8、#16、#17、#18；plan Task C2）。
 * 每条用例都走完整编译通道（规范化 → 解析 → R1 → lower → R2），再断言 IR，并要求输出通过 schema 与不变式。
 */
class CoreLoweringV2Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 注解（#3） ----------

    @Test
    void annotations_preserve_source_order() {
        CoreModel.Func f = func(compile("Module m.\n\n@control(\"B\")\n@id(\"X\")\nRule r given x as Int, produce Int:\n  Return x.\n"), "r");
        assertEquals(List.of("control", "id"), f.annotations.stream().map(a -> a.name).toList());
    }

    @Test
    void annotation_args_preserve_source_order() {
        CoreModel.Module m = compile("Module m.\n\nDefine User has @sensitive(\"a\", level: \"L2\", \"b\") email as Text.\n");
        CoreModel.Annotation a = ((CoreModel.Data) m.decls.get(0)).fields.get(0).annotations.get(0);
        assertEquals(List.of("$0", "level", "$1"), a.args.stream().map(CoreModel.AnnotationArg::name).toList());
        assertEquals(List.of("a", "L2", "b"), a.args.stream().map(CoreModel.AnnotationArg::value).toList());
    }

    @Test
    void annotation_values_are_string_int_or_boolean() {
        CoreModel.Module m = compile("Module m.\n\nDefine User has @meta(n: 7, flag: true, tag: Gold) email as Text.\n");
        CoreModel.Annotation a = ((CoreModel.Data) m.decls.get(0)).fields.get(0).annotations.get(0);
        assertEquals(List.of(7, true, "Gold"), a.args.stream().map(CoreModel.AnnotationArg::value).toList());
    }

    @Test
    void annotation_float_value_is_parse_error() {
        assertParseError("Module m.\n\nDefine User has @meta(n: 1.5) email as Text.\n", ErrorCode.PARSE_INVALID_VALUE);
    }

    @Test
    void annotation_long_value_is_parse_error() {
        assertParseError("Module m.\n\nDefine User has @meta(n: 5L) email as Text.\n", ErrorCode.PARSE_INVALID_VALUE);
    }

    // ---------- 约束（#4） ----------

    @Test
    void field_required_and_between_lowered() {
        CoreModel.Module m = compile("Module m.\n\nDefine Person has age as Int required between 0 and 120.\n");
        List<CoreModel.Constraint> cs = ((CoreModel.Data) m.decls.get(0)).fields.get(0).constraints;
        assertEquals(2, cs.size());
        assertNotNull(assertInstanceOf(CoreModel.Constraint.Required.class, cs.get(0)).origin);
        CoreModel.Constraint.Range range = assertInstanceOf(CoreModel.Constraint.Range.class, cs.get(1));
        assertEquals("0", range.min);
        assertEquals("120", range.max);
        assertNotNull(range.origin);
        assertEquals(3, range.origin.start.line);
    }

    @Test
    void between_decimal_and_negative_bounds_are_canonical() {
        CoreModel.Module m = compile("Module m.\n\nDefine Reading has level as Double between -0.0 and 2.50.\n");
        CoreModel.Constraint.Range range = (CoreModel.Constraint.Range) ((CoreModel.Data) m.decls.get(0)).fields.get(0).constraints.get(0);
        assertEquals("0", range.min);
        assertEquals("2.5", range.max);
    }

    @Test
    void param_matching_lowered() {
        CoreModel.Func f = func(compile("Module m.\n\nRule check given code as Text matching \"^[A-Z]+$\", produce Bool:\n  Return true.\n"), "check");
        CoreModel.Constraint.Pattern p = assertInstanceOf(CoreModel.Constraint.Pattern.class, f.params.get(0).constraints.get(0));
        assertEquals("^[A-Z]+$", p.regexp);
        assertNotNull(p.origin);
    }

    @Test
    void untyped_field_keeps_constraints() {
        CoreModel.Module m = compile("Module m.\n\nDefine Loan has applicantId required, amount.\n");
        CoreModel.Field id = ((CoreModel.Data) m.decls.get(0)).fields.get(0);
        assertTrue(id.typeInferred);
        assertInstanceOf(CoreModel.Constraint.Required.class, id.constraints.get(0));
        assertEquals(List.of(), ((CoreModel.Data) m.decls.get(0)).fields.get(1).constraints);
    }

    @Test
    void between_non_literal_is_parse_error() {
        var result = ParseDiagnostics.parse("Module m.\n\nDefine Person has age as Int between x and 120.\n",
            LexiconRegistry.getInstance().getOrThrow("en-US"));
        assertFalse(result.diagnostics().isEmpty(), "between 的两端只能是数值字面量");
    }

    // ---------- 效应（#5） ----------

    @Test
    void effects_lowercased() {
        assertEquals(List.of("io"), func(compile("Module m.\n\nRule r, produce Int. It performs IO:\n  Return 1.\n"), "r").effects);
        assertEquals(List.of("cpu"), func(compile("Module m.\n\nRule r, produce Int. It performs cpu:\n  Return 1.\n"), "r").effects);
    }

    @Test
    void non_authorizing_effects_are_omitted() {
        for (String effect : List.of("pure", "Pure", "teleport")) {
            CoreModel.Func f = func(compile("Module m.\n\nRule r, produce Int. It performs " + effect + ":\n  Return 1.\n"), "r");
            assertEquals(List.of(), f.effects, effect);
            assertEquals(List.of(), f.effectParams, effect);
        }
    }

    // ---------- 枚举变体模式（#16） ----------

    @Test
    void zero_arg_enum_variant_is_patctor_and_binding_is_patname() {
        CoreModel.Module m = compile("Module m.\n\nDefine Color as one of Red, Green.\n\n"
            + "Rule name given c as Color, produce Text:\n  Match c:\n    When Red, Return \"r\".\n    When other, Return \"x\".\n");
        List<CoreModel.Pattern> ps = patterns(func(m, "name"));
        CoreModel.PatCtor red = assertInstanceOf(CoreModel.PatCtor.class, ps.get(0));
        assertEquals("Red", red.typeName);
        assertEquals(List.of(), red.args);
        assertEquals("other", assertInstanceOf(CoreModel.PatName.class, ps.get(1)).name);
    }

    @Test
    void capitalized_binding_is_patname() {
        CoreModel.Module m = compile("Module m.\n\nDefine Color as one of Red, Green.\n\n"
            + "Rule name given c as Color, produce Text:\n  Match c:\n    When Red, Return \"r\".\n    When Other, Return \"x\".\n");
        assertEquals("Other", assertInstanceOf(CoreModel.PatName.class, patterns(func(m, "name")).get(1)).name);
    }

    // ---------- 浮点字面量（#18） ----------

    @Test
    void double_from_lexeme_not_double() {
        assertEquals("123456789012345678901.5", returnedDouble("123456789012345678901.5"));
        assertEquals("0.30000000000000004", returnedDouble("0.30000000000000004"));
        assertEquals("1.5", returnedDouble("1.50"));
        assertEquals("1", returnedDouble("1.0"));
        assertEquals("0", returnedDouble("0.0"));
    }

    // ---------- origin（#17）与整体合规 ----------

    @Test
    void every_node_has_origin() throws Exception {
        CoreModel.Module m = compile(RICH);
        List<String> missing = new ArrayList<>();
        walk(MAPPER.valueToTree(m), "", missing);
        assertEquals(List.of(), missing);
        assertEquals(List.of(), CoreIrJson.validate(MAPPER.readTree(CoreIrJson.serialize(m))));
    }

    @Test
    void lambda_without_declared_return_type_has_ret() {
        CoreModel.Module m = compile(RICH);
        List<JsonNode> lambdas = new ArrayList<>();
        collectKind(MAPPER.valueToTree(m), "Lambda", lambdas);
        assertFalse(lambdas.isEmpty());
        lambdas.forEach(l -> assertTrue(l.get("ret").isObject(), l.toString()));
    }

    @Test
    void no_pii_aggregate_fields() {
        String json = CoreIrJson.serialize(compile("Module m.\n\nRule mask given email as @pii(L2, email) Text, produce Text:\n  Return email.\n"));
        assertFalse(json.contains("piiLevel"), json);
        assertTrue(json.contains("\"kind\":\"PiiType\""), json);
    }

    /** 覆盖较多节点种类的样本：Data（含 PII 与约束）、Enum、Import、Func（Let / If / Match / Lambda / 调用 / 字面量）。 */
    private static final String RICH = """
        Module m.

        Use lib.util as Util.

        Define Person has name as Text required, age as Int between 0 and 150, email as @pii(L2, email) Text.

        Define Color as one of Red, Green.

        Rule label given c as Color, produce Text:
          Match c:
            When Red, Return "r".
            When other, Return "x".

        Rule score given p as Person, produce Double:
          Let base be 1.25.
          Let xs be [1, 2, 3].
          Let f be function with n, produce:
            Return n plus 1.
          If p.age at least 18:
            Return base.
          Return 0.5.
        """;

    // ---------- 工具 ----------

    private static CoreModel.Module compile(String source) {
        var parsed = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow("en-US"));
        assertEquals(List.of(), parsed.diagnostics(), "解析");
        return NameResolution.resolveAndLowerChecked(parsed.module(), ResolutionContext.none()).core();
    }

    private static void assertParseError(String source, ErrorCode code) {
        var parsed = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow("en-US"));
        assertNull(parsed.module());
        assertEquals(List.of(code), parsed.diagnostics().stream().map(d -> d.code()).toList());
    }

    private static CoreModel.Func func(CoreModel.Module m, String name) {
        return m.decls.stream().filter(d -> d instanceof CoreModel.Func f && f.name.equals(name))
            .map(CoreModel.Func.class::cast).findFirst().orElseThrow();
    }

    private static List<CoreModel.Pattern> patterns(CoreModel.Func f) {
        CoreModel.Match match = (CoreModel.Match) f.body.statements.get(0);
        return match.cases.stream().map(c -> c.pattern).toList();
    }

    private static String returnedDouble(String literal) {
        CoreModel.Func f = func(compile("Module m.\n\nRule r, produce Double:\n  Return " + literal + ".\n"), "r");
        return ((CoreModel.DoubleE) ((CoreModel.Return) f.body.statements.get(0)).expr).value;
    }

    /** 每个带 kind 的对象都必须有非 null 的 origin（Case / Step 也带 kind）。 */
    private static void walk(JsonNode node, String path, List<String> missing) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) walk(node.get(i), path + "/" + i, missing);
            return;
        }
        if (!node.isObject()) return;
        if (node.has("kind") && (!node.has("origin") || node.get("origin").isNull())) {
            missing.add(path + " " + node.get("kind").asText());
        }
        node.properties().forEach(e -> walk(e.getValue(), path + "/" + e.getKey(), missing));
    }

    private static void collectKind(JsonNode node, String kind, List<JsonNode> out) {
        if (node.isArray()) node.forEach(n -> collectKind(n, kind, out));
        if (!node.isObject()) return;
        if (kind.equals(node.path("kind").asText())) out.add(node);
        node.forEach(n -> collectKind(n, kind, out));
    }
}
