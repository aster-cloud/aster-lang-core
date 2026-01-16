package aster.core.test;

import aster.core.ast.Expr;
import aster.core.ast.Span;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * jqwik 属性测试示例
 * <p>
 * 展示如何使用 jqwik 进行属性测试（Property-Based Testing），
 * 类似于 TypeScript 的 fast-check 库。
 * <p>
 * 属性测试通过生成大量随机输入来验证代码的通用性质，
 * 比传统单元测试更能发现边界情况和异常行为。
 */
public class PropertyTestExample {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 属性：AST 节点序列化后反序列化应保持一致
     * <p>
     * 测试任意整数表达式节点的 JSON 往返转换是否无损。
     */
    @Property
    void intExprRoundTripSerialization(@ForAll int value) throws Exception {
        // 创建整数表达式
        Expr.Int original = new Expr.Int(value, createTestSpan());

        // 序列化
        String json = mapper.writeValueAsString(original);

        // 反序列化
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证类型和值
        assertTrue(deserialized instanceof Expr.Int);
        assertEquals(value, ((Expr.Int) deserialized).value());
    }

    /**
     * 属性：字符串表达式应正确处理任意 UTF-8 字符串
     */
    @Property
    void stringExprHandlesAnyUtf8String(@ForAll String value) throws Exception {
        // 创建字符串表达式
        Expr.String original = new Expr.String(value, createTestSpan());

        // 序列化
        String json = mapper.writeValueAsString(original);

        // 反序列化
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证
        assertTrue(deserialized instanceof Expr.String);
        assertEquals(value, ((Expr.String) deserialized).value());
    }

    /**
     * 属性：布尔表达式只应有两个可能值
     */
    @Property
    void boolExprHasOnlyTwoStates(@ForAll boolean value) throws Exception {
        // 创建布尔表达式
        Expr.Bool original = new Expr.Bool(value, createTestSpan());

        // 序列化
        String json = mapper.writeValueAsString(original);

        // 验证 JSON 包含 kind 字段
        assertTrue(json.contains("\"kind\":\"Bool\""));

        // 反序列化
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证类型和值
        assertTrue(deserialized instanceof Expr.Bool);
        assertEquals(value, ((Expr.Bool) deserialized).value());
    }

    /**
     * 属性：Long 表达式应处理完整的 long 范围
     */
    @Property
    void longExprHandlesFullRange(@ForAll long value) throws Exception {
        // 创建长整数表达式
        Expr.Long original = new Expr.Long(value, createTestSpan());

        // 序列化并反序列化
        String json = mapper.writeValueAsString(original);
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证
        assertTrue(deserialized instanceof Expr.Long);
        assertEquals(value, ((Expr.Long) deserialized).value());
    }

    /**
     * 属性：Double 表达式应处理特殊浮点值
     */
    @Property
    void doubleExprHandlesSpecialValues(@ForAll double value) throws Exception {
        // 创建浮点数表达式
        Expr.Double original = new Expr.Double(value, createTestSpan());

        // 序列化并反序列化
        String json = mapper.writeValueAsString(original);
        Expr deserialized = mapper.readValue(json, Expr.class);

        // 验证（注意 NaN 需要特殊处理）
        assertTrue(deserialized instanceof Expr.Double);
        double deserializedValue = ((Expr.Double) deserialized).value();

        if (Double.isNaN(value)) {
            assertTrue(Double.isNaN(deserializedValue));
        } else {
            assertEquals(value, deserializedValue, 0.0001);
        }
    }

    /**
     * 辅助方法：创建测试用 Span
     */
    private Span createTestSpan() {
        return new Span(
            new Span.Position(1, 1),
            new Span.Position(1, 10)
        );
    }
}
