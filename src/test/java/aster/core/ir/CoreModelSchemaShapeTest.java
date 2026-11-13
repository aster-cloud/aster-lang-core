package aster.core.ir;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 每个 {@link CoreModel} 节点类的 Jackson 属性集与 schema 中对应 {@code $defs} 的 {@code properties} 完全相等
 * （ADR 0055 §3「合规检查」）：多一个、少一个都失败，schema 中没有对应类的对象定义同样失败。
 *
 * <p>类与 {@code $defs} 的对应：带 {@code @JsonTypeName} 的类取其值（{@code workflow} → {@code Workflow}），
 * 其余取类名；子类型的 {@code kind} 由 {@code @JsonTypeInfo} 写出，计入属性集。
 */
class CoreModelSchemaShapeTest {

    /** 不进 IR JSON 的旁路类型（ADR 0051 R2 解析标记）。 */
    private static final Set<Class<?>> NOT_IR = Set.of(CoreModel.ResolutionMark.class);

    @Test
    void every_class_matches_schema_defs() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Set<String>> actual = new TreeMap<>();
        for (Class<?> c : nodeClasses()) {
            Set<String> props = new TreeSet<>();
            for (BeanPropertyDefinition p : mapper.getSerializationConfig()
                    .introspect(mapper.constructType(c)).findProperties()) {
                if (p.couldSerialize()) {
                    props.add(p.getName());
                }
            }
            if (hasTypeIdProperty(c)) {
                props.add("kind");
            }
            actual.put(defName(c), props);
        }

        Map<String, Set<String>> expected = new TreeMap<>();
        JsonNode defs = CoreIrJson.schemaNode().path("$defs");
        defs.properties().forEach(e -> {
            JsonNode props = e.getValue().get("properties");
            if (props != null && "object".equals(e.getValue().path("type").asText()) && !e.getValue().has("allOf")) {
                Set<String> names = new TreeSet<>();
                props.fieldNames().forEachRemaining(names::add);
                expected.put(e.getKey(), names);
            }
        });

        assertEquals(expected, actual);
    }

    private static List<Class<?>> nodeClasses() {
        List<Class<?>> out = new ArrayList<>();
        collect(CoreModel.class, out);
        return out;
    }

    private static void collect(Class<?> owner, List<Class<?>> out) {
        for (Class<?> c : owner.getDeclaredClasses()) {
            collect(c, out);
            if (!c.isInterface() && !NOT_IR.contains(c)) {
                out.add(c);
            }
        }
    }

    private static boolean hasTypeIdProperty(Class<?> c) {
        for (Class<?> i : c.getInterfaces()) {
            JsonTypeInfo info = i.getAnnotation(JsonTypeInfo.class);
            if (info != null && "kind".equals(info.property())) {
                return true;
            }
        }
        return false;
    }

    private static String defName(Class<?> c) {
        JsonTypeName typeName = c.getAnnotation(JsonTypeName.class);
        String name = typeName == null ? c.getSimpleName() : typeName.value();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
