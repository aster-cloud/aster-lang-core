package aster.core.ir;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lowering.CoreLowering;
import aster.core.nodeid.NodeIdMap;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Long} 字面量必须序列化为 <b>JSON string</b>，不能是 JSON number。
 *
 * <h2>缺陷</h2>
 *
 * JSON number 在 JS 侧只能安全表示 |n| ≤ 2^53−1：
 *
 * <pre>
 *   Long 字面量 9007199254740993L
 *   → 写成 number 时，JS 侧 JSON.parse 得到 9007199254740992  ← 静默差 1，不报错
 * </pre>
 *
 * <h2>两个实打实的后果</h2>
 *
 * <ul>
 *   <li><b>跨引擎分叉</b>：TS 引擎的 {@code value} 本就是 string
 *       （{@code core_ir.ts:123}），于是所有含 Long 的样本在 IR parity 上分歧
 *       （实测 {@code interop_sum} / {@code interop_overload} 两个样本）。</li>
 *   <li><b>Stable Node ID 直接不可用</b>：{@code CanonicalJson} 规定「非 Decimal
 *       number 只允许 safe integer」，超范围的 Long 会让 canonicalHash 抛
 *       {@code NON_INTEGER_NUMBER} —— 即 ADR 0037 的 change impact 在这类程序上
 *       <b>完全无法运行</b>，而不是算错。</li>
 * </ul>
 */
class LongLiteralSerializationTest {

    private static String irJsonOf(String longLiteral) {
        String src = "Module p.\n\nRule r, produce:\n  Return " + longLiteral + ".\n";
        String canonical = new Canonicalizer().canonicalize(src);
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
        try {
            return new ObjectMapper().writeValueAsString(core);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    @Test
    @DisplayName("Long 字面量序列化为 JSON string（与 TS 引擎同口径）")
    void longIsSerializedAsJsonString() {
        // ★断言字符串形态而非具体数值：`"2"` 带引号 = string，`2` 不带 = number。
        assertTrue(irJsonOf("2L").contains("\"kind\":\"Long\",\"value\":\"2\""),
            "Long 应序列化为 JSON string，实际：" + irJsonOf("2L"));
    }

    @Test
    @DisplayName("★超 2^53−1 的 Long 精度无损（写成 number 会静默差 1）")
    void largeLongKeepsFullPrecision() {
        // 9007199254740993 = 2^53 + 1，是 JS number 无法表示的最小正整数之一。
        String json = irJsonOf("9007199254740993L");
        assertTrue(json.contains("\"value\":\"9007199254740993\""),
            "超范围 Long 的精度丢失了，实际：" + json
                + "\n★若这里是 number 形态，JS 侧 JSON.parse 会得到 9007199254740992。");
    }

    @Test
    @DisplayName("★超范围 Long 不再让 Stable Node ID 抛错（change impact 可用）")
    void largeLongDoesNotBreakNodeId() {
        // 修复前：CanonicalJson 抛 NON_INTEGER_NUMBER，NodeIdMap.compute 整个失败，
        // 即含大 Long 的程序**根本无法做 change impact**。
        String src = "Module p.\n\nRule r, produce:\n  Return 9007199254740993L.\n";
        String canonical = new Canonicalizer().canonicalize(src);
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        lexer.removeErrorListeners();
        AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        CoreModel.Module core = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));

        var ids = NodeIdMap.compute(new ObjectMapper().valueToTree(core));

        assertTrue(ids.size() > 0, "含大 Long 的程序应能计算出节点标识。");
        // 反向守卫：确认 Long 节点**本身**在结果里，而不是被跳过后「看起来成功」。
        assertTrue(ids.values().stream().anyMatch(v -> "Long".equals(v.kind())),
            "结果里没有 Long 节点 —— 可能是被静默跳过而非真正支持。实际 kinds："
                + ids.values().stream().map(NodeIdMap.NodeIdentity::kind).toList());
    }

    @Test
    @DisplayName("内存表示仍是 long：反序列化往返精度无损")
    void roundTripKeepsPrecision() throws Exception {
        // 消费侧（truffle Loader）把 value 读进 long 字段。Jackson 接受 string→long，
        // 本用例钉住该往返行为——它是本修复对运行时安全的前提。
        ObjectMapper om = new ObjectMapper();
        CoreModel.LongE parsed = om.readValue(
            "{\"kind\":\"Long\",\"value\":\"9007199254740993\"}", CoreModel.LongE.class);

        assertEquals(9007199254740993L, parsed.value, "string→long 往返精度丢失。");
    }
}
