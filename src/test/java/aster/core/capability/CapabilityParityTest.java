package aster.core.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capability 能力表单源一致性测试（防双引擎 drift 复发，Java 侧）。
 *
 * <p>{@code shared/capabilities.json}（byte-identical 副本置于 test resources
 * {@code capability/capabilities.json}）是 capability 授权契约的唯一真源。本测试
 * 锁定 Java 的两处 capability 副本与该 json 一致：
 * <ul>
 *   <li>{@link CapabilityKind} enum（enumName + displayName）</li>
 *   <li>{@link CapabilityInference} 的 CAPABILITY_PREFIXES（通过 inferCapabilityFromName 行为验证）</li>
 * </ul>
 *
 * <p>TS 侧由 capability-parity.test.ts 对同一份 json 守门，两份 json 的 byte-identical
 * 由 TS 侧断言 → 传递性保证双引擎 capability 授权契约一致。
 */
class CapabilityParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<JsonNode> loadCapabilities() throws Exception {
        try (InputStream in =
                     getClass().getClassLoader().getResourceAsStream("capability/capabilities.json")) {
            assertNotNull(in, "capability/capabilities.json 应在 test classpath 中");
            JsonNode root = MAPPER.readTree(in);
            List<JsonNode> out = new ArrayList<>();
            root.get("capabilities").forEach(out::add);
            return out;
        }
    }

    @Test
    void enumNameSetMatchesJson() throws Exception {
        Set<String> jsonNames = new TreeSet<>();
        for (JsonNode c : loadCapabilities()) {
            jsonNames.add(c.get("enumName").asText());
        }
        Set<String> enumNames = new TreeSet<>();
        for (CapabilityKind k : CapabilityKind.values()) {
            enumNames.add(k.name());
        }
        assertEquals(jsonNames, enumNames,
                "CapabilityKind 枚举名集合必须与 shared/capabilities.json 一致（防双引擎 drift）");
    }

    @Test
    void displayNameMatchesJson() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (JsonNode c : loadCapabilities()) {
            String enumName = c.get("enumName").asText();
            String displayName = c.get("displayName").asText();
            CapabilityKind kind;
            try {
                kind = CapabilityKind.valueOf(enumName);
            } catch (IllegalArgumentException e) {
                mismatches.add(enumName + ": Java 枚举缺失");
                continue;
            }
            if (!displayName.equals(kind.displayName())) {
                mismatches.add(enumName + ".displayName: json=" + displayName
                        + " java=" + kind.displayName());
            }
        }
        assertTrue(mismatches.isEmpty(),
                "displayName 与 json 不一致:\n" + String.join("\n", mismatches));
    }

    @Test
    void effectClassMatchesJson() throws Exception {
        // A3: capability 的 effect 分类（io/cpu）双引擎一致 —— Java effectClass() ↔ json class。
        List<String> mismatches = new ArrayList<>();
        var jsonCpuSet = new TreeSet<String>();
        for (JsonNode c : loadCapabilities()) {
            String enumName = c.get("enumName").asText();
            String jsonClass = c.get("class").asText();
            if ("cpu".equals(jsonClass)) {
                jsonCpuSet.add(enumName);
            }
            CapabilityKind kind = CapabilityKind.valueOf(enumName);
            String javaClass = kind.effectClass().name().toLowerCase();
            if (!jsonClass.equals(javaClass)) {
                mismatches.add(enumName + ".class: json=" + jsonClass + " java=" + javaClass);
            }
        }
        assertTrue(mismatches.isEmpty(),
                "effect class 与 json 不一致:\n" + String.join("\n", mismatches));

        // 钉死 cpu-class 集合 = {CPU, CRYPTO}（本地计算密集），防未来 drift。
        var expectedCpu = new TreeSet<>(java.util.Set.of("CPU", "CRYPTO"));
        assertEquals(expectedCpu, jsonCpuSet, "json cpu-class 集合应为 {CPU, CRYPTO}");
        var javaCpuSet = new TreeSet<String>();
        for (CapabilityKind k : CapabilityKind.values()) {
            if (k.isCpuClass()) {
                javaCpuSet.add(k.name());
            }
        }
        assertEquals(expectedCpu, javaCpuSet, "Java cpu-class 集合应为 {CPU, CRYPTO}");
    }

    @Test
    void fromLabelResolvesEveryDisplayName() throws Exception {
        List<String> failures = new ArrayList<>();
        for (JsonNode c : loadCapabilities()) {
            String displayName = c.get("displayName").asText();
            Optional<CapabilityKind> resolved = CapabilityKind.fromLabel(displayName);
            if (resolved.isEmpty() || !resolved.get().name().equals(c.get("enumName").asText())) {
                failures.add(displayName + " → " + resolved.map(Enum::name).orElse("empty"));
            }
        }
        assertTrue(failures.isEmpty(),
                "fromLabel 应能解析每个 displayName: " + String.join(", ", failures));
    }

    @Test
    void prefixInferenceMatchesJson() throws Exception {
        // 每个 json 前缀应被 CapabilityInference 推断回对应的 CapabilityKind。
        List<String> failures = new ArrayList<>();
        for (JsonNode c : loadCapabilities()) {
            String enumName = c.get("enumName").asText();
            JsonNode prefixes = c.get("prefixes");
            for (JsonNode p : prefixes) {
                String prefix = p.asText();
                // 构造一个以该前缀开头的调用名（前缀已含 '.'，补一个方法名）
                String sample = prefix + "sample";
                Optional<CapabilityKind> inferred =
                        CapabilityInference.inferCapabilityFromName(sample);
                if (inferred.isEmpty() || !inferred.get().name().equals(enumName)) {
                    failures.add(prefix + " → " + inferred.map(Enum::name).orElse("empty")
                            + "（期望 " + enumName + "）");
                }
            }
        }
        assertTrue(failures.isEmpty(),
                "前缀推断与 json 不一致:\n" + String.join("\n", failures));
    }

    @Test
    void codesAreUnique() throws Exception {
        Set<String> names = new TreeSet<>();
        Set<String> displays = new TreeSet<>();
        List<String> dup = new ArrayList<>();
        for (JsonNode c : loadCapabilities()) {
            if (!names.add(c.get("enumName").asText())) {
                dup.add("enumName " + c.get("enumName").asText());
            }
            if (!displays.add(c.get("displayName").asText())) {
                dup.add("displayName " + c.get("displayName").asText());
            }
        }
        assertTrue(dup.isEmpty(), "json enumName/displayName 应唯一，重复: " + String.join(", ", dup));
    }

    @Test
    void newTaxonomyIsApplied() throws Exception {
        // 钉死本次修复：Java 补齐了 NETWORK/CRYPTO/PROCESS。
        assertNotNull(CapabilityKind.valueOf("NETWORK"));
        assertNotNull(CapabilityKind.valueOf("CRYPTO"));
        assertNotNull(CapabilityKind.valueOf("PROCESS"));
        assertEquals(CapabilityKind.NETWORK, CapabilityInference.inferCapabilityFromName("Tcp.connect").orElseThrow());
        assertEquals(CapabilityKind.CRYPTO, CapabilityInference.inferCapabilityFromName("Kms.encrypt").orElseThrow());
        assertEquals(CapabilityKind.PROCESS, CapabilityInference.inferCapabilityFromName("Shell.exec").orElseThrow());
    }
}
