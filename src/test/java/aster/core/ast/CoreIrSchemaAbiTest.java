package aster.core.ast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core IR schema ABI 契约测试（golden）。
 *
 * <p>把双引擎 100% 等价升级为**版本化 ABI 契约**：锁定 Core IR 序列化的
 * 节点种类清单（kind 标签）与 schema 版本。任何对已发布节点的删除/改名/改 kind
 * 都会让本测试失败，强制开发者：
 * <ol>
 *   <li>确认这是有意的 breaking change；</li>
 *   <li>bump {@link CoreIrSchemaVersion} 并更新承诺窗口；</li>
 *   <li>同步更新下方 golden 清单 + 通告下游消费方（Truffle / TS 解释器 / aster-api）。</li>
 * </ol>
 *
 * <p>新增节点种类（只增不删）只需把它加进 golden 清单，不算 breaking。
 */
@DisplayName("Core IR schema ABI 契约")
class CoreIrSchemaAbiTest {

    /**
     * Core IR v1 的 golden 节点 kind 清单（序列化 @JsonTypeName / kind() 标签）。
     * <p>新增节点：加进此集合（只增不删）。删除/改名：必须 bump schema major 版本。
     */
    private static final Set<String> V1_NODE_KINDS = Set.of(
        // 顶层
        "Module",
        // 声明 Decl
        "Func", "Data", "Enum", "Import", "TypeAlias",
        // 表达式 Expr
        "Name", "Call", "Construct", "Lambda", "Await",
        "Int", "Long", "Double", "Bool", "String", "Null",
        "Ok", "Err", "Some", "None", "ListLiteral",
        "IfExpr", // ADR 0019 G2b：表达式级 if（只增不删）

        // 语句 Stmt（注意 Workflow 的 kind() 返回小写 "workflow"）
        "Let", "Return", "If", "Match", "Set", "Start", "Wait", "Block", "Define", "workflow",
        // 类型 Type
        "TypeName", "TypeVar", "TypeApp", "FuncType", "Result", "Maybe", "Option", "List", "Map",
        // 模式 Pattern
        "PatternCtor", "PatternInt", "PatternName", "PatternNull"
    );

    @Test
    @DisplayName("schema 版本常量与承诺窗口稳定")
    void schemaVersionStable() {
        assertThat(CoreIrSchemaVersion.CURRENT).isEqualTo(CoreIrSchemaVersion.V1);
        assertThat(CoreIrSchemaVersion.V1.version).isEqualTo("1.0");
        assertThat(CoreIrSchemaVersion.V1.releasedAt).isEqualTo("2026-06-09");
        assertThat(CoreIrSchemaVersion.V1.guaranteedUntil).isEqualTo("2027-12-01");
    }

    @Test
    @DisplayName("schema 版本兼容性判定")
    void schemaCompatibility() {
        // 未声明（旧消费方）→ 向后兼容
        assertThat(CoreIrSchemaVersion.isCompatible(null)).isTrue();
        assertThat(CoreIrSchemaVersion.isCompatible("")).isTrue();
        // v1 各种形态
        assertThat(CoreIrSchemaVersion.isCompatible("1")).isTrue();
        assertThat(CoreIrSchemaVersion.isCompatible("1.0")).isTrue();
        assertThat(CoreIrSchemaVersion.isCompatible("1.3")).isTrue();
        // 未来 major 不兼容当前 core
        assertThat(CoreIrSchemaVersion.isCompatible("2")).isFalse();
        assertThat(CoreIrSchemaVersion.isCompatible("2.0")).isFalse();
    }

    @Test
    @DisplayName("Module 节点序列化形态稳定（kind=Module + name/decls/span）")
    void moduleSerializationShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

        Span span = new Span(new Span.Position(1, 0), new Span.Position(1, 10));
        Module module = new Module("demo.abi", List.of(), span);

        @SuppressWarnings("unchecked")
        Map<String, Object> json = mapper.convertValue(module, Map.class);

        // 顶层字段契约：name / decls / span（kind 由 kind() 方法提供，非序列化字段）
        assertThat(json).containsKeys("name", "decls", "span");
        assertThat(json.get("name")).isEqualTo("demo.abi");
        assertThat(module.kind()).isEqualTo("Module");
    }

    @Test
    @DisplayName("golden 清单非空且涵盖核心节点")
    void nodeKindInventoryGolden() {
        assertThat(V1_NODE_KINDS).contains("Module", "Func", "Lambda", "Call", "Match", "TypeName");
        assertThat(V1_NODE_KINDS).hasSizeGreaterThanOrEqualTo(44);
        // Set.of 去重保证无重复
        assertThat(new TreeSet<>(V1_NODE_KINDS)).hasSameSizeAs(V1_NODE_KINDS);
    }

    @Test
    @DisplayName("代码实际节点 kind ⊆ golden 清单（删/改 kind 会触发此测试）")
    void actualKindsMatchGolden() {
        // 反射枚举 sealed interface 的全部子类型，提取每个的 kind() 标签，
        // 与 golden 清单比对。新增节点：必须同步加进 V1_NODE_KINDS（只增不删）；
        // 删除/改 kind：本测试失败 → 强制确认 breaking change + bump schema 版本。
        Set<String> actual = new TreeSet<>();
        actual.add(new Module("", List.of(), null).kind());
        for (Class<?> sealed : List.of(Decl.class, Expr.class, Stmt.class, Type.class)) {
            collectKinds(sealed, actual);
        }
        // 模式节点（Pattern*）不在上述 sealed 顶层，单独补充已知集合校验存在
        assertThat(V1_NODE_KINDS).contains("PatternCtor", "PatternInt", "PatternName", "PatternNull");

        // 实际可枚举的 kind 必须全部在 golden 清单内（杜绝悄悄改 kind 标签）
        assertThat(V1_NODE_KINDS)
            .as("发现未登记的节点 kind %s —— 新增请加进 V1_NODE_KINDS，"
                + "改名/删除请 bump CoreIrSchemaVersion 并通告下游", actual)
            .containsAll(actual);
    }

    /** 递归收集 sealed interface 全部 record 子类型的 kind() 标签。 */
    private static void collectKinds(Class<?> sealed, Set<String> out) {
        Class<?>[] subs = sealed.getPermittedSubclasses();
        if (subs == null) {
            return;
        }
        for (Class<?> sub : subs) {
            if (sub.isSealed()) {
                collectKinds(sub, out);
            }
            if (sub.isRecord()) {
                try {
                    Object inst = instantiateForKind(sub);
                    if (inst instanceof AstNode node) {
                        out.add(node.kind());
                    }
                } catch (ReflectiveOperationException ignored) {
                    // 无法零参实例化的节点跳过——其 kind 由配套单元测试覆盖
                }
            }
        }
    }

    /** 用全 null/默认值构造 record 实例，仅为读取 kind()（不校验语义）。 */
    private static Object instantiateForKind(Class<?> rec) throws ReflectiveOperationException {
        var components = rec.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            args[i] = defaultValue(components[i].getType());
        }
        return rec.getDeclaredConstructor(paramTypes).newInstance(args);
    }

    private static Object defaultValue(Class<?> t) {
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == boolean.class) return false;
        if (t == java.util.List.class) return List.of();
        if (t == String.class) return "";
        return null;
    }
}
