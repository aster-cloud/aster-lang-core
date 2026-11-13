package aster.core.ir;

import aster.core.ast.CoreIrSchemaVersion;
import aster.core.canonical.CanonicalJson;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core IR v2 的唯一 JSON 出入口（ADR 0055 §5）。
 *
 * <p>读入流程固定为四步，顺序不可调换：
 * <ol>
 *   <li>解析为 JSON 树，先单独检查 {@code /schemaVersion}：缺失、非整数或不为 2 即报
 *       {@code unsupported_schema_version}，不依赖校验器报出的路径；</li>
 *   <li>对输入做 JSON Schema 2020-12 校验（类路径副本 {@code core-ir/core-ir.v2.schema.json}）：缺字段、类型错误、
 *       可空性错误、未知字段都在这一步拒收——光靠严格反序列化不等于符合 schema；</li>
 *   <li>严格绑定到 {@link CoreModel}（{@code FAIL_ON_UNKNOWN_PROPERTIES=true}，第二道防线）；</li>
 *   <li>不变式检查（schema 表达不了的约束，规则与 path 见 locales {@code core-ir/README.md}）。</li>
 * </ol>
 *
 * <p>序列化出口 {@link #serialize} 输出 {@link CanonicalJson} 规则下的字节（键按码点升序、null 显式、
 * 数值只允许 safe integer）。凡是给持久化、哈希或 parity 用的 IR 都经此产出。数值规范化不在这里：
 * {@code Double.value} 等规范十进制串由 lowering 求出，这里原样输出。
 */
public final class CoreIrJson {

    /** 版本错误的问题 code，同时是 {@link CoreIrFormatException#code()} 的取值。 */
    public static final String UNSUPPORTED_SCHEMA_VERSION = "unsupported_schema_version";
    /** 版本之外的所有读入错误的 {@link CoreIrFormatException#code()}。 */
    public static final String INVALID_REQUEST = "invalid_request";
    /** JSON Schema 拒收的问题 code。 */
    public static final String SCHEMA = "schema";

    static final String DUP_ANNOTATION_ARG = "dup-annotation-arg";
    static final String UNDECLARED_EFFECT_VAR = "undeclared-effect-var";
    static final String DUPLICATE_EFFECT = "duplicate-effect";
    static final String EFFECT_PARAM_SHADOWS_BUILTIN = "effect-param-shadows-builtin";
    static final String INPUT_REFS_STATE = "input-refs-state";

    /** 类路径上的 schema 副本，由 {@code CoreIrSchemaSiblingParityTest} 守护与 locales 源文件逐字节相同。 */
    public static final String SCHEMA_RESOURCE = "/core-ir/core-ir.v2.schema.json";

    private static final Set<String> VERDICT_CALLS =
        Set.of("Verdict.allow", "Verdict.deny", "Verdict.require_approval", "Verdict.escalate");

    private static final Comparator<CoreIrProblem> PROBLEM_ORDER =
        Comparator.comparing(CoreIrProblem::path).thenComparing(CoreIrProblem::code);

    /**
     * 严格 mapper：未知字段、重复键、尾随内容、整数位置的浮点数、基本类型位置的 null 一律失败。
     * 字符串到数值的标量转换保留开启，因为 {@code Long.value} 以 JSON 字符串承载 64 位整数；其余类型错误已在
     * schema 校验一步拒收。序列化侧输出全部字段（CoreModel 不再有 {@code @JsonInclude} 省略规则）。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .build();

    /** 严格绑定器。包内私有：调用方只能经 {@link #read} 读入，不能绕开 schema 校验。 */
    static final ObjectReader READER = MAPPER.readerFor(CoreModel.Module.class);

    private static final JsonNode SCHEMA_NODE = loadSchemaNode();
    private static final Schema SCHEMA_VALIDATOR = SchemaRegistry
        .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(SchemaLocation.of(SCHEMA_NODE.path("$id").asText()), SCHEMA_NODE);
    /** 授权型具体效应（io、cpu），取自 schema {@code $defs/BuiltinEffect}。 */
    private static final Set<String> BUILTIN_EFFECTS = enumOf("BuiltinEffect");
    /** 效应参数不得使用的名字（大小写不敏感），取自 schema {@code $defs/ReservedEffectName}。 */
    private static final Set<String> RESERVED_EFFECT_NAMES = enumOf("ReservedEffectName");

    private CoreIrJson() {}

    /**
     * 严格读入 Core IR v2。
     *
     * @throws CoreIrFormatException 版本错误（code 为 {@link #UNSUPPORTED_SCHEMA_VERSION}），或 JSON 语法、schema、
     *     绑定、不变式任一失败（code 为 {@link #INVALID_REQUEST}）
     */
    public static CoreModel.Module read(String json) throws CoreIrFormatException {
        JsonNode tree = readTree(json);
        try {
            return READER.readValue(tree);
        } catch (IOException e) {
            throw new CoreIrFormatException(INVALID_REQUEST, List.of(), "Core IR 无法严格绑定：" + e.getMessage(), e);
        }
    }

    /** 同 {@link #read} 的前两步与不变式检查，返回校验通过的树，供只读树的调用方使用。 */
    public static JsonNode readTree(String json) throws CoreIrFormatException {
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new CoreIrFormatException(INVALID_REQUEST, List.of(), "Core IR 不是合法 JSON：" + e.getOriginalMessage(), e);
        }
        if (tree == null || tree.isMissingNode()) {
            throw new CoreIrFormatException(INVALID_REQUEST, List.of(), "Core IR 为空", null);
        }
        List<CoreIrProblem> problems = validate(tree);
        if (!problems.isEmpty()) {
            String code = problems.getFirst().code().equals(UNSUPPORTED_SCHEMA_VERSION)
                ? UNSUPPORTED_SCHEMA_VERSION : INVALID_REQUEST;
            throw new CoreIrFormatException(code, problems, "Core IR 不符合 v2 schema：" + problems, null);
        }
        return tree;
    }

    /**
     * 版本检查、schema 校验、不变式检查（供 Truffle 等自带 DTO 的读方使用）。
     * 版本错误时只返回这一个问题；schema 拒收时不再检查不变式（不变式要求输入已通过 schema）。
     *
     * @return 空列表表示通过
     */
    public static List<CoreIrProblem> validate(JsonNode tree) {
        JsonNode version = tree.path("schemaVersion");
        if (!tree.isObject() || !version.isIntegralNumber() || !version.canConvertToInt()
            || !CoreIrSchemaVersion.isCompatible(version.intValue())) {
            return List.of(new CoreIrProblem("/schemaVersion", UNSUPPORTED_SCHEMA_VERSION));
        }
        List<CoreIrProblem> schemaProblems = new ArrayList<>();
        for (com.networknt.schema.Error e : SCHEMA_VALIDATOR.validate(tree)) {
            String at = e.getInstanceLocation().toString();
            schemaProblems.add(new CoreIrProblem(e.getProperty() == null ? at : at + "/" + e.getProperty(), SCHEMA));
        }
        if (!schemaProblems.isEmpty()) {
            return List.copyOf(new LinkedHashSet<>(schemaProblems.stream().sorted(PROBLEM_ORDER).toList()));
        }
        return invariantProblems(tree);
    }

    /** 规范序列化（ADR 0055 §5）：{@link CanonicalJson} 字节。 */
    public static String serialize(CoreModel.Module m) {
        return CanonicalJson.canonicalJson(MAPPER.valueToTree(m));
    }

    /**
     * schema 表达不了的约束（locales {@code core-ir/README.md}「不变式」）。输入须能通过 schema；
     * 结果按 {@code (path, code)} 排序，path 为 JSON Pointer。
     */
    public static List<CoreIrProblem> validateCoreIrInvariants(CoreModel.Module m) {
        return invariantProblems(MAPPER.valueToTree(m));
    }

    // ---------- 不变式（在 JSON 树上检查，path 即 IR 的 JSON Pointer） ----------

    static List<CoreIrProblem> invariantProblems(JsonNode module) {
        List<CoreIrProblem> out = new ArrayList<>();
        duplicateArgs(module, "", out);
        boolean hasProfile = !module.path("profile").isNull();
        JsonNode decls = module.path("decls");
        for (int i = 0; i < decls.size(); i++) {
            JsonNode decl = decls.get(i);
            if ("Func".equals(decl.path("kind").asText())) {
                funcProblems(decl, "/decls/" + i, hasProfile, out);
            }
        }
        out.sort(PROBLEM_ORDER);
        return List.copyOf(out);
    }

    /** 同一注解内参数名重复：报后出现的那个参数（树中任何位置的 annotations / retAnnotations）。 */
    private static void duplicateArgs(JsonNode node, String path, List<CoreIrProblem> out) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                duplicateArgs(node.get(i), path + "/" + i, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            JsonNode child = field.getValue();
            if ((key.equals("annotations") || key.equals("retAnnotations")) && child.isArray()) {
                for (int j = 0; j < child.size(); j++) {
                    reportDuplicateArgs(child.get(j).path("args"), path + "/" + key + "/" + j + "/args", out);
                }
            }
            duplicateArgs(child, path + "/" + escape(key), out);
        }
    }

    private static void reportDuplicateArgs(JsonNode args, String path, List<CoreIrProblem> out) {
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < args.size(); k++) {
            if (!seen.add(args.get(k).path("name").asText())) {
                out.add(new CoreIrProblem(path + "/" + k, DUP_ANNOTATION_ARG));
            }
        }
    }

    private static void funcProblems(JsonNode func, String path, boolean hasProfile, List<CoreIrProblem> out) {
        List<String> effectParams = new ArrayList<>();
        JsonNode params = func.path("effectParams");
        for (int k = 0; k < params.size(); k++) {
            String p = params.get(k).asText();
            effectParams.add(p);
            if (RESERVED_EFFECT_NAMES.contains(asciiLower(p))) {
                out.add(new CoreIrProblem(path + "/effectParams/" + k, EFFECT_PARAM_SHADOWS_BUILTIN));
            }
        }
        // effects 按首次声明顺序去重：重复条目报后出现的那个；非 io / cpu 的条目必须是已声明的效应变量
        Set<String> seen = new HashSet<>();
        JsonNode effects = func.path("effects");
        for (int k = 0; k < effects.size(); k++) {
            String name = effects.get(k).asText();
            if (!seen.add(name)) {
                out.add(new CoreIrProblem(path + "/effects/" + k, DUPLICATE_EFFECT));
            } else if (!effectParams.contains(name) && !BUILTIN_EFFECTS.contains(name)) {
                out.add(new CoreIrProblem(path + "/effects/" + k, UNDECLARED_EFFECT_VAR));
            }
        }
        // ADR 0055 #12：档案模块中返回 Verdict 的规则必须是数组，其余函数必须为 null
        if (func.path("inputRefs").isArray() != (hasProfile && returnsVerdict(func))) {
            out.add(new CoreIrProblem(path + "/inputRefs", INPUT_REFS_STATE));
        }
    }

    /** 与 {@code GovernanceChecker.returnsVerdict} 同义：声明返回 Verdict，或任一可达 Return 直接调用 Verdict.*。 */
    private static boolean returnsVerdict(JsonNode func) {
        JsonNode ret = func.path("ret");
        if ("TypeName".equals(ret.path("kind").asText()) && "Verdict".equals(ret.path("name").asText())) {
            return true;
        }
        return anyReturnIsVerdictCall(func.path("body"));
    }

    private static boolean anyReturnIsVerdictCall(JsonNode stmt) {
        return switch (stmt.path("kind").asText()) {
            case "Return" -> isVerdictCall(stmt.path("expr"));
            case "Block", "Scope" -> anyOf(stmt.path("statements"));
            case "If" -> anyReturnIsVerdictCall(stmt.path("thenBlock")) || anyReturnIsVerdictCall(stmt.path("elseBlock"));
            case "Match" -> {
                boolean any = false;
                for (JsonNode c : stmt.path("cases")) {
                    any |= anyReturnIsVerdictCall(c.path("body"));
                }
                yield any;
            }
            default -> false;
        };
    }

    private static boolean anyOf(JsonNode statements) {
        for (JsonNode s : statements) {
            if (anyReturnIsVerdictCall(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVerdictCall(JsonNode expr) {
        JsonNode target = expr.path("target");
        return "Call".equals(expr.path("kind").asText()) && "Name".equals(target.path("kind").asText())
            && VERDICT_CALLS.contains(target.path("name").asText());
    }

    /** 按 ASCII 大小写不敏感比较所用的小写化（内建效应名都是 ASCII）。 */
    private static String asciiLower(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return b.toString();
    }

    /** JSON Pointer 段转义（RFC 6901）。IR 的键都是标识符，这里只为路径严格正确。 */
    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    // ---------- schema ----------

    /** 类路径 schema 副本的解析结果（只读）。 */
    static JsonNode schemaNode() {
        return SCHEMA_NODE;
    }

    private static JsonNode loadSchemaNode() {
        try (InputStream in = CoreIrJson.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("类路径缺少 Core IR schema：" + SCHEMA_RESOURCE);
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 Core IR schema：" + SCHEMA_RESOURCE, e);
        }
    }

    private static Set<String> enumOf(String def) {
        Set<String> out = new HashSet<>();
        SCHEMA_NODE.path("$defs").path(def).path("enum").forEach(v -> out.add(v.asText()));
        if (out.isEmpty()) {
            throw new IllegalStateException("Core IR schema 缺少 $defs/" + def + " 的 enum");
        }
        return Set.copyOf(out);
    }
}
