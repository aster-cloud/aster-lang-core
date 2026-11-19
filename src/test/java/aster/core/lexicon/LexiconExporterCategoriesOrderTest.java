package aster.core.lexicon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link LexiconExporter} 导出的 {@code categories} 字段顺序必须与 JVM 进程无关。
 *
 * <p>历史 bug：{@link SemanticTokenKind#CATEGORIES} 用 {@code Map.ofEntries} 构建，遍历顺序随 JVM 启动时的随机 SALT
 * 变化，同一 lexicon 在不同进程里导出的 categories 字段顺序不同，导出物无法逐字节复现。这里与固定的书写顺序比较，
 * 而不是在同一进程里导出两次（同一进程内 SALT 不变，两次导出必然相同，测不出问题）。
 */
class LexiconExporterCategoriesOrderTest {

    /** {@link SemanticTokenKind#CATEGORIES} 的书写顺序（与枚举分组顺序一致）。 */
    private static final List<String> CANONICAL_ORDER = List.of(
        "module", "type", "function", "control", "variable", "boolean", "arithmetic", "comparison",
        "typeConstruct", "literal", "primitiveType", "effect", "workflow", "async", "constraint");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void categoriesIterateInWrittenOrder() {
        assertEquals(CANONICAL_ORDER, new ArrayList<>(SemanticTokenKind.CATEGORIES.keySet()));
    }

    @Test
    void exportedCategoriesFollowWrittenOrder() throws Exception {
        StringWriter out = new StringWriter();
        new LexiconExporter().export("0.0.0-test", List.of(), out);
        JsonNode categories = MAPPER.readTree(out.toString()).path("categories");

        List<String> names = new ArrayList<>();
        categories.fieldNames().forEachRemaining(names::add);
        assertEquals(CANONICAL_ORDER, names, "导出 JSON 的 categories 字段顺序应为书写顺序（与 JVM 进程无关）");

        // 每个分类内的成员顺序同样固定
        for (String name : CANONICAL_ORDER) {
            List<String> members = new ArrayList<>();
            categories.path(name).forEach(n -> members.add(n.asText()));
            List<String> expected = SemanticTokenKind.CATEGORIES.get(name).stream().map(Enum::name).toList();
            assertEquals(expected, members, name);
        }
    }

    @Test
    void categoriesAreImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> SemanticTokenKind.CATEGORIES.remove("module"));
        assertThrows(UnsupportedOperationException.class,
            () -> SemanticTokenKind.CATEGORIES.get("module").set(0, SemanticTokenKind.IF));
    }
}
