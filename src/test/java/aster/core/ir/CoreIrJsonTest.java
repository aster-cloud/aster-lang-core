package aster.core.ir;

import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CoreIrJson} 的严格读入、规范序列化与不变式（ADR 0055 §5；plan Task C1 Step 1）。
 *
 * <p>基准模块由 {@link #baseModule()} 用代码构造：一个带注解字段的 Data 与一个返回 Double 字面量的 Func，
 * 每个用例在其规范 JSON 上只改一处，再交给 {@link CoreIrJson#read}。
 */
class CoreIrJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 拒收 ----------

    @Test
    void rejects_unknown_field() {
        assertInvalid(t -> func(t).putArray("declaredEffects"), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_missing_schema_version() {
        assertInvalid(t -> t.remove("schemaVersion"), CoreIrJson.UNSUPPORTED_SCHEMA_VERSION);
    }

    @Test
    void rejects_schema_version_1() {
        assertInvalid(t -> t.put("schemaVersion", 1), CoreIrJson.UNSUPPORTED_SCHEMA_VERSION);
    }

    @Test
    void rejects_schema_version_string() {
        assertInvalid(t -> t.put("schemaVersion", "2"), CoreIrJson.UNSUPPORTED_SCHEMA_VERSION);
    }

    @Test
    void rejects_schema_version_float() {
        assertInvalid(t -> t.put("schemaVersion", 2.0), CoreIrJson.UNSUPPORTED_SCHEMA_VERSION);
    }

    @Test
    void rejects_missing_required_field() {
        assertInvalid(t -> func(t).remove("effectParams"), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_wrong_type() {
        assertInvalid(t -> field(t).put("typeInferred", "no"), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_null_list() {
        assertInvalid(t -> func(t).putNull("annotations"), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_noncanonical_decimal() {
        assertInvalid(t -> doubleLiteral(t).put("value", "1.50"), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_annotation_value_float() {
        assertInvalid(t -> firstArg(t).put("value", 1.5), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_annotation_value_null() {
        assertInvalid(t -> firstArg(t).putNull("value"), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_annotation_value_int32_overflow() {
        assertInvalid(t -> firstArg(t).put("value", 2147483648L), CoreIrJson.INVALID_REQUEST);
    }

    @Test
    void rejects_malformed_json() {
        CoreIrFormatException e = assertThrows(CoreIrFormatException.class, () -> CoreIrJson.read("{\"kind\":"));
        assertEquals(CoreIrJson.INVALID_REQUEST, e.code());
    }

    @Test
    void rejects_duplicate_json_key() {
        String json = CoreIrJson.serialize(baseModule()).replaceFirst("\\{", "{\"name\":\"x\",");
        CoreIrFormatException e = assertThrows(CoreIrFormatException.class, () -> CoreIrJson.read(json));
        assertEquals(CoreIrJson.INVALID_REQUEST, e.code());
    }

    @Test
    void rejects_dup_annotation_arg() {
        CoreIrFormatException e = assertInvalid(t -> args(t).addObject().put("name", "$0").put("value", "b"),
            CoreIrJson.INVALID_REQUEST);
        assertEquals(List.of(new CoreIrProblem("/decls/0/fields/0/annotations/0/args/1", "dup-annotation-arg")),
            e.problems());
    }

    @Test
    void rejects_undeclared_effect_var() {
        CoreIrFormatException e = assertInvalid(t -> ((ArrayNode) func(t).get("effects")).add("E"),
            CoreIrJson.INVALID_REQUEST);
        assertEquals(List.of(new CoreIrProblem("/decls/1/effects/0", "undeclared-effect-var")), e.problems());
    }

    @Test
    void declared_effect_var_accepted() throws Exception {
        ObjectNode tree = baseTree();
        ((ArrayNode) func(tree).get("effectParams")).add("E");
        ((ArrayNode) func(tree).get("effects")).add("io").add("E");
        CoreModel.Module m = CoreIrJson.read(MAPPER.writeValueAsString(tree));
        assertEquals(List.of("io", "E"), ((CoreModel.Func) m.decls.get(1)).effects);
    }

    // ---------- 接受与序列化 ----------

    @Test
    void accepts_int32_bounds() throws Exception {
        for (long bound : new long[] {2147483647L, -2147483648L}) {
            ObjectNode tree = baseTree();
            firstArg(tree).put("value", bound);
            String json = MAPPER.writeValueAsString(tree);
            CoreModel.Module m = CoreIrJson.read(json);
            CoreModel.Annotation a = ((CoreModel.Data) m.decls.get(0)).fields.get(0).annotations.get(0);
            assertEquals(Optional.of((int) bound), a.arg("$0"));
            assertEquals(CoreIrJson.serialize(CoreIrJson.read(json)), CoreIrJson.serialize(m));
            assertTrue(CoreIrJson.serialize(m).contains("\"value\":" + bound));
        }
    }

    @Test
    void serialize_emits_empty_arrays_and_nulls() {
        String json = CoreIrJson.serialize(baseModule());
        assertTrue(json.contains("\"annotations\":[]"), json);
        assertTrue(json.contains("\"profile\":null"), json);
        assertTrue(json.contains("\"inputRefs\":null"), json);
        assertTrue(json.contains("\"written\":null"), json);
        assertTrue(json.contains("\"retTypeInferred\":false"), json);
        assertTrue(json.contains("\"schemaVersion\":2"), json);
        assertFalse(json.contains("piiLevel"), json);
    }

    @Test
    void serialize_round_trips_byte_identical() throws Exception {
        String json = CoreIrJson.serialize(baseModule());
        assertEquals(json, CoreIrJson.serialize(CoreIrJson.read(json)));
    }

    @Test
    void validate_reports_only_version_problem() {
        ObjectNode tree = baseTree();
        tree.put("schemaVersion", 1);
        func(tree).putArray("declaredEffects");
        assertEquals(List.of(new CoreIrProblem("/schemaVersion", CoreIrJson.UNSUPPORTED_SCHEMA_VERSION)),
            CoreIrJson.validate(tree));
    }

    @Test
    void double_literals_match_fixture() throws Exception {
        Optional<Path> fixture = CoreIrLocales.locate("core-ir/test-fixtures/double-literals.json");
        assumeTrue(fixture.isPresent(), "本地未并列 checkout aster-lang-locales，跳过");
        JsonNode rows = MAPPER.readTree(fixture.get().toFile());
        assertFalse(rows.isEmpty());
        for (JsonNode row : rows) {
            assertEquals(row.path("canonical").asText(), CoreLowering.canonicalDecimal(row.path("source").asText()),
                row.path("source").asText());
        }
    }

    /**
     * locales 的全部夹具（plan C1 比较规则）：期望 code 为 schema 的只比 code；三个 schemaVersion 夹具读方报
     * unsupported_schema_version（locales README）；不变式类比 {path, code} 全等；正例必须通过，且读入后
     * 再规范序列化与输入的规范字节相同（模型没有丢字段）。
     */
    @Test
    void locales_bad_fixtures_rejected() throws Exception {
        Optional<Path> expectedFile = CoreIrLocales.locate("core-ir/test-fixtures/expected-problems.json");
        assumeTrue(expectedFile.isPresent(), "本地未并列 checkout aster-lang-locales，跳过");
        Path dir = expectedFile.get().getParent();
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (JsonNode entry : MAPPER.readTree(expectedFile.get().toFile())) {
            String name = entry.path("file").asText();
            String json = Files.readString(dir.resolve(name));
            List<CoreIrProblem> want = new ArrayList<>();
            entry.path("problems").forEach(p -> want.add(new CoreIrProblem(p.path("path").asText(), p.path("code").asText())));
            checked++;
            if (want.isEmpty()) {
                try {
                    String roundTrip = CoreIrJson.serialize(CoreIrJson.read(json));
                    String input = aster.core.canonical.CanonicalJson.canonicalJson(MAPPER.readTree(json));
                    if (!roundTrip.equals(input)) mismatches.add(name + "：读入后规范序列化与输入不同");
                } catch (CoreIrFormatException e) {
                    mismatches.add(name + "：正例被拒收 " + e.getMessage() + " " + e.problems());
                }
                continue;
            }
            CoreIrFormatException e;
            try {
                CoreIrJson.read(json);
                mismatches.add(name + "：期望拒收，实际通过");
                continue;
            } catch (CoreIrFormatException thrown) {
                e = thrown;
            }
            boolean schemaKind = want.size() == 1 && want.getFirst().code().equals(CoreIrJson.SCHEMA);
            if (schemaKind && want.getFirst().path().equals("/schemaVersion")) {
                if (!e.code().equals(CoreIrJson.UNSUPPORTED_SCHEMA_VERSION)) mismatches.add(name + "：应报版本错误，实际 " + e.code());
            } else if (schemaKind) {
                if (!e.code().equals(CoreIrJson.INVALID_REQUEST)
                    || e.problems().stream().noneMatch(p -> p.code().equals(CoreIrJson.SCHEMA))) {
                    mismatches.add(name + "：应被 schema 拒收，实际 " + e.code() + " " + e.problems());
                }
            } else if (!e.problems().equals(want)) {
                mismatches.add(name + "：期望 " + want + "，实际 " + e.problems());
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(checked >= 30, "夹具数量异常：" + checked);
    }

    // ---------- 夹具构造 ----------

    private static CoreIrFormatException assertInvalid(Consumer<ObjectNode> mutate, String code) {
        ObjectNode tree = baseTree();
        mutate.accept(tree);
        CoreIrFormatException e = assertThrows(CoreIrFormatException.class,
            () -> CoreIrJson.read(MAPPER.writeValueAsString(tree)));
        assertEquals(code, e.code(), () -> String.valueOf(e.problems()));
        return e;
    }

    private static ObjectNode baseTree() {
        try {
            return (ObjectNode) MAPPER.readTree(CoreIrJson.serialize(baseModule()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode func(ObjectNode t) {
        return (ObjectNode) t.get("decls").get(1);
    }

    private static ObjectNode field(ObjectNode t) {
        return (ObjectNode) t.get("decls").get(0).get("fields").get(0);
    }

    private static ArrayNode args(ObjectNode t) {
        return (ArrayNode) field(t).get("annotations").get(0).get("args");
    }

    private static ObjectNode firstArg(ObjectNode t) {
        return (ObjectNode) args(t).get(0);
    }

    private static ObjectNode doubleLiteral(ObjectNode t) {
        return (ObjectNode) func(t).get("body").get("statements").get(0).get("expr");
    }

    static CoreModel.Origin origin(int line) {
        CoreModel.Origin o = new CoreModel.Origin();
        o.start = position(line, 1);
        o.end = position(line, 10);
        return o;
    }

    private static CoreModel.Position position(int line, int col) {
        CoreModel.Position p = new CoreModel.Position();
        p.line = line;
        p.col = col;
        return p;
    }

    private static CoreModel.TypeName typeName(String name, int line) {
        CoreModel.TypeName t = new CoreModel.TypeName();
        t.name = name;
        t.origin = origin(line);
        return t;
    }

    /** 基准模块：Data Person(@sensitive("a") name: Text) 与 Rule ratio produce Double: Return 0.5。 */
    static CoreModel.Module baseModule() {
        CoreModel.Annotation sensitive = new CoreModel.Annotation();
        sensitive.name = "sensitive";
        sensitive.args = List.of(new CoreModel.AnnotationArg("$0", "a"));
        CoreModel.Field name = new CoreModel.Field();
        name.name = "name";
        name.type = typeName("Text", 2);
        name.annotations = List.of(sensitive);
        CoreModel.Data person = new CoreModel.Data();
        person.name = "Person";
        person.fields = List.of(name);
        person.origin = origin(2);

        CoreModel.DoubleE half = new CoreModel.DoubleE();
        half.value = "0.5";
        half.origin = origin(5);
        CoreModel.Return ret = new CoreModel.Return();
        ret.expr = half;
        ret.origin = origin(5);
        CoreModel.Block body = new CoreModel.Block();
        body.statements = List.of(ret);
        body.origin = origin(5);
        CoreModel.Func ratio = new CoreModel.Func();
        ratio.name = "ratio";
        ratio.typeParams = List.of();
        ratio.params = List.of();
        ratio.ret = typeName("Double", 4);
        ratio.body = body;
        ratio.origin = origin(4);

        CoreModel.Module m = new CoreModel.Module();
        m.name = "demo";
        m.decls = List.of(person, ratio);
        m.origin = origin(1);
        return m;
    }
}
