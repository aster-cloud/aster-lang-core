package aster.core.typecheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码单源一致性测试（防 drift 复发，Java 侧）。
 *
 * <p>{@code shared/error_codes.json}（byte-identical 副本置于 test resources
 * {@code diagnostics/error_codes.json}）是错误码码表数据的唯一真源。本测试锁定
 * Java 枚举 {@link ErrorCode} 与该 json 的「结构字段」一致——name 集合、code、
 * category、severity。
 *
 * <p>★message/help 现在**也逐字节比对**（aster-lang-core#137 修复后）。
 * 此前不比对的理由是「Java 用 {@code %s} 位置占位符、json 用 {@code {name}} 命名占位符，
 * 两种表示无法逐字节相等」——那个描述准确，却掩盖了真正的问题：Java 的主渲染路径
 * {@code DiagnosticBuilder.formatMessage} **只认 {@code {name}}**，对 {@code %s}
 * 不做任何替换，于是 23 个已 emit 的码把字面的 {@code %s} 直接呈现给用户
 * （实测 E101「Undefined variable: %s」连是哪个变量都不说）。
 * 生成器不再把 {@code {name}} 改写成 {@code %s}，两端逐字对齐，这条强校验随之可以打开——
 * 它同时是防该缺陷复发的守卫。ts 侧 error-codes-parity 测试对同一份 json 做同样的强校验，
 * 两份 json 的 byte-identical 由 ts 侧断言保证——传递性上确保 ts ↔ Java 码表一致。
 */
class ErrorCodeParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode loadTable() throws Exception {
        try (InputStream in =
                     getClass().getClassLoader().getResourceAsStream("diagnostics/error_codes.json")) {
            assertNotNull(in, "diagnostics/error_codes.json 应在 test classpath 中");
            return MAPPER.readTree(in);
        }
    }

    /** json category（小写）→ Java Category 枚举名。 */
    private static ErrorCode.Category toCategory(String jsonCategory) {
        return ErrorCode.Category.valueOf(jsonCategory.toUpperCase());
    }

    /** json severity（小写）→ Java Severity 枚举名。 */
    private static ErrorCode.Severity toSeverity(String jsonSeverity) {
        return ErrorCode.Severity.valueOf(jsonSeverity.toUpperCase());
    }

    @Test
    void enumNameSetMatchesJson() throws Exception {
        JsonNode table = loadTable();

        Set<String> jsonNames = new TreeSet<>();
        for (Iterator<String> it = table.fieldNames(); it.hasNext(); ) {
            jsonNames.add(it.next());
        }

        Set<String> enumNames = new TreeSet<>();
        for (ErrorCode ec : ErrorCode.values()) {
            enumNames.add(ec.name());
        }

        assertEquals(jsonNames, enumNames,
                "ErrorCode 枚举名集合必须与 shared/error_codes.json 完全一致（防码表 drift）");
    }

    /**
     * ★message/help 与 json 逐字节一致（aster-lang-core#137 的复发守卫）。
     *
     * <p>没有这条，生成器再把 {@code {name}} 改写回 {@code %s}（或有人手改生成物）
     * 都不会有任何测试变红——那正是本缺陷能存活到 23 个码的原因。
     */
    @Test
    void messageAndHelpMatchJsonByteForByte() throws Exception {
        JsonNode table = loadTable();
        Map<String, ErrorCode> byName = new HashMap<>();
        for (ErrorCode ec : ErrorCode.values()) {
            byName.put(ec.name(), ec);
        }

        List<String> mismatches = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = table.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            ErrorCode ec = byName.get(e.getKey());
            if (ec == null) {
                continue;   // 名集合由 enumNameSetMatchesJson 单独守
            }
            String jsonMessage = e.getValue().get("message").asText();
            if (!jsonMessage.equals(ec.messageTemplate())) {
                mismatches.add(e.getKey() + ".message: json=" + jsonMessage
                        + " | java=" + ec.messageTemplate());
            }
            String jsonHelp = e.getValue().get("help").asText();
            if (!jsonHelp.equals(ec.help())) {
                mismatches.add(e.getKey() + ".help: json=" + jsonHelp
                        + " | java=" + ec.help());
            }
        }

        assertTrue(mismatches.isEmpty(),
                "message/help 必须与 shared/error_codes.json 逐字节一致（单一真源）：\n"
                        + String.join("\n", mismatches));
    }

    /**
     * ★没有任何模板会渲染出字面 {@code %s}。
     *
     * <p>与上一条互补：上一条守「Java == json」，本条守「模板本身不含 %s」。
     * 两条都在，才既防生成器改写、也防有人往真源里写 %s。
     */
    @Test
    void noTemplateContainsPositionalPlaceholder() {
        List<String> offenders = new ArrayList<>();
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec.messageTemplate() != null && ec.messageTemplate().contains("%s")) {
                offenders.add(ec.name() + ": " + ec.messageTemplate());
            }
        }
        assertTrue(offenders.isEmpty(),
                "消息模板不得含 %s —— DiagnosticBuilder 只替换 {name}，%s 会原样呈现给用户：\n"
                        + String.join("\n", offenders));
    }

    @Test
    void codeCategorySeverityMatchJson() throws Exception {
        JsonNode table = loadTable();

        Map<String, ErrorCode> byName = new HashMap<>();
        for (ErrorCode ec : ErrorCode.values()) {
            byName.put(ec.name(), ec);
        }

        List<String> mismatches = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = table.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            JsonNode spec = e.getValue();
            ErrorCode ec = byName.get(name);
            if (ec == null) {
                mismatches.add(name + ": Java 枚举缺失");
                continue;
            }
            String jsonCode = spec.get("code").asText();
            if (!jsonCode.equals(ec.code())) {
                mismatches.add(name + ".code: json=" + jsonCode + " java=" + ec.code());
            }
            if (toCategory(spec.get("category").asText()) != ec.category()) {
                mismatches.add(name + ".category: json=" + spec.get("category").asText()
                        + " java=" + ec.category());
            }
            if (toSeverity(spec.get("severity").asText()) != ec.severity()) {
                mismatches.add(name + ".severity: json=" + spec.get("severity").asText()
                        + " java=" + ec.severity());
            }
        }

        assertTrue(mismatches.isEmpty(),
                "code/category/severity 与 json 不一致:\n" + String.join("\n", mismatches));
    }

    @Test
    void codesAreUnique() throws Exception {
        JsonNode table = loadTable();
        Set<String> seen = new TreeSet<>();
        List<String> dup = new ArrayList<>();
        for (ErrorCode ec : ErrorCode.values()) {
            if (!seen.add(ec.code())) {
                dup.add(ec.code() + " (" + ec.name() + ")");
            }
        }
        assertTrue(dup.isEmpty(), "枚举 code 应唯一，重复: " + String.join(", ", dup));
        // json 侧 code 唯一性
        Set<String> jsonSeen = new TreeSet<>();
        List<String> jsonDup = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = table.fields(); it.hasNext(); ) {
            String code = it.next().getValue().get("code").asText();
            if (!jsonSeen.add(code)) {
                jsonDup.add(code);
            }
        }
        assertTrue(jsonDup.isEmpty(), "json code 应唯一，重复: " + String.join(", ", jsonDup));
    }

    @Test
    void userRulingIsApplied() throws Exception {
        JsonNode table = loadTable();
        assertEquals("E102", table.get("MULTIPLE_ENTRY_RULES").get("code").asText());
        assertEquals("E103", table.get("IMPORT_SYMBOL_CONFLICT").get("code").asText());
        assertEquals("E104", table.get("DUPLICATE_SYMBOL").get("code").asText());
        assertEquals("E210", table.get("EFFECT_VAR_UNDECLARED").get("code").asText());
        assertEquals("E211", table.get("EFFECT_VAR_UNRESOLVED").get("code").asText());
        // Java 枚举侧同样落地
        assertEquals("E102", ErrorCode.MULTIPLE_ENTRY_RULES.code());
        assertEquals("E104", ErrorCode.DUPLICATE_SYMBOL.code());
    }
}
