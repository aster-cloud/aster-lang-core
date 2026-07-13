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
 * <p>为何不逐字节比对 message/help：Java 侧历史生成物的 message 模板用
 * {@code %s} 位置占位符（{@code String.format} 路径），而 json 用 {@code {name}}
 * 命名占位符（{@link DiagnosticBuilder#error} 的 Map 命名参数路径），两种表示无法逐字节
 * 相等。ts 侧 error-codes-parity 测试对同一份 json 做「含 message/help」的强校验，
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
