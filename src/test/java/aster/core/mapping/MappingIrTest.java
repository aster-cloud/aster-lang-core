package aster.core.mapping;

import aster.core.mapping.MappingIr.CandidateMapping;
import aster.core.mapping.MappingIr.TextSpan;
import aster.core.mapping.MappingIr.VerifiableNode;
import aster.core.mapping.MappingIr.VerificationResult;
import aster.core.mapping.MappingIr.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MappingIR verifier（ADR 0037 §4/§7），与 TS 侧
 * {@code test/unit/mapping/mapping-ir.test.ts} <b>逐条对等</b>。
 *
 * <p>本测试钉的是<b>契约</b>：哪些映射机器能证明、哪些必须交人、哪些能证伪。
 */
class MappingIrTest {

    private static CandidateMapping mapping(String text, String nodeId) {
        return new CandidateMapping(new TextSpan(0, text.length()), text, nodeId);
    }

    /** 固定的节点表，key 即 nodeId。 */
    private static Function<String, VerifiableNode> nodes(Map<String, VerifiableNode> table) {
        return table::get;
    }

    private static final Function<String, VerifiableNode> FIXTURE = nodes(Map.of(
        "$.threshold", new VerifiableNode("Int", 10000, null),
        "$.label", new VerifiableNode("String", "REFER", null),
        "$.branch", new VerifiableNode("If", null, null),
        "$.rate", new VerifiableNode("Double", 1.0d, null),
        "$.price", new VerifiableNode("Decimal", "10000", null),
        "$.bigId", new VerifiableNode("Long", "9007199254740993", null),
        "$.flag", new VerifiableNode("Bool", Boolean.TRUE, null)
    ));

    @Test
    @DisplayName("★`$10,000` ↔ Int(10000)：跨越「人类书写 ↔ IR 值」的鸿沟")
    void humanWrittenMoneyMapsToIntLiteral() {
        // ADR §4 的原始场景。货币符号与千分位是人类惯例，IR 里只有 10000。
        VerificationResult r = MappingIr.verify(mapping("$10,000", "$.threshold"), FIXTURE);

        assertEquals(Verdict.VERIFIED, r.verdict(),
            "应判 VERIFIED（这正是 MappingIR 的价值所在）。实际：" + r.reason());
    }

    @Test
    @DisplayName("字符串字面量按原值比较")
    void stringLiteralComparesByValue() {
        assertEquals(Verdict.VERIFIED,
            MappingIr.verify(mapping("\"REFER\"", "$.label"), FIXTURE).verdict());
    }

    @Test
    @DisplayName("★值矛盾必须 REJECTED，不得放过")
    void contradictingValueIsRejected() {
        // 反向守卫：没有这条，verifier 可以退化成「一律 VERIFIED」，
        // 上面两条照样绿，而它就彻底没用了。
        VerificationResult r = MappingIr.verify(mapping("$20,000", "$.threshold"), FIXTURE);

        assertEquals(Verdict.REJECTED, r.verdict(),
            "文本 20000 与节点值 10000 矛盾，必须证伪。");
        assertTrue(r.reason().contains("矛盾"), "应说明是「矛盾」，实际：" + r.reason());
    }

    @Test
    @DisplayName("★带业务含义的映射一律交人，不猜（ADR §3）")
    void semanticMappingIsLeftToHumans() {
        // 判 REVIEW_REQUIRED 而**不是** REJECTED —— 那不是「错」，是「机器管不了」。
        VerificationResult r = MappingIr.verify(mapping("requires approval", "$.branch"), FIXTURE);

        assertEquals(Verdict.REVIEW_REQUIRED, r.verdict(), r.reason());
    }

    @Test
    @DisplayName("★Double 不参与机械验证（源码文本与 IR 值不可逆）")
    void doubleIsNotMechanicallyVerifiable() {
        // 实测：`1.0` → 1 → 回写 "1"；`1e3` → 1000。文本不可还原，机器无法证明
        // 「这段文本就是这个 Double」。故交人，而不是用近似规则假装能证。
        VerificationResult r = MappingIr.verify(mapping("1.0", "$.rate"), FIXTURE);

        assertEquals(Verdict.REVIEW_REQUIRED, r.verdict(), r.reason());
        // ★必须断言**判定依据**，不只是判定结果。
        //   TS 侧的变异验证发现：把 Double 加进可验证清单后结论仍是 REVIEW_REQUIRED
        //   （因为解析函数里也没有 Double 分支，两层防御恰好重叠）。只断言 verdict
        //   的话，「可验证清单」这道门**结构上无法变红**。
        assertTrue(r.reason().contains("不属于可机械证明的精确值字面量"),
            "应因「kind 不在可验证清单」而交人，而非因「解析不出值」。实际：" + r.reason());
    }

    @Test
    @DisplayName("★canonical 形态 ≠ 源码文本：`$10,000.00` 必须验得过 Decimal(\"10000\")")
    void trailingZerosDoNotBreakVerification() {
        // 100.00m 在 IR 里是 value:"100"（尾随零被规范化）。若做字符串相等比较，
        // 这条会误判 REJECTED —— 一个数值上完全正确的映射。
        VerificationResult r = MappingIr.verify(mapping("$10,000.00", "$.price"), FIXTURE);

        assertEquals(Verdict.VERIFIED, r.verdict(),
            "尾随零差异不应导致失败（必须按值比较而非按文本）。实际：" + r.reason());
    }

    @Test
    @DisplayName("★超安全整数的 Long 不得丢精度")
    void largeLongKeepsFullPrecision() {
        // 9007199254740993 一旦过一次 double 就变 …992（本仓实测踩过）。
        assertEquals(Verdict.VERIFIED,
            MappingIr.verify(mapping("9007199254740993", "$.bigId"), FIXTURE).verdict());

        // 且**差 1** 必须能被识别——若中途过了浮点，这两个值会变得相等。
        assertEquals(Verdict.REJECTED,
            MappingIr.verify(mapping("9007199254740992", "$.bigId"), FIXTURE).verdict(),
            "相差 1 的大整数必须证伪 —— 若判 VERIFIED，说明中途经过了浮点并丢了精度。");
    }

    @Test
    @DisplayName("布尔字面量")
    void booleanLiteral() {
        assertEquals(Verdict.VERIFIED,
            MappingIr.verify(mapping("true", "$.flag"), FIXTURE).verdict());
        assertEquals(Verdict.REJECTED,
            MappingIr.verify(mapping("false", "$.flag"), FIXTURE).verdict());
    }

    @Test
    @DisplayName("节点不存在 → REJECTED（不能静默当成「待复核」）")
    void missingNodeIsRejected() {
        assertEquals(Verdict.REJECTED,
            MappingIr.verify(mapping("$10,000", "$.nonexistent"), FIXTURE).verdict());
    }

    @Test
    @DisplayName("★span 与 text 必须自洽，否则双向导航会跳错位置")
    void spanMustAgreeWithText() {
        // 区间宽度 3，但 text 长 7 —— span 指向的根本不是这段文本
        assertEquals(Verdict.REJECTED, MappingIr.verify(
            new CandidateMapping(new TextSpan(0, 3), "$10,000", "$.threshold"), FIXTURE).verdict());

        // 非法区间
        assertEquals(Verdict.REJECTED, MappingIr.verify(
            new CandidateMapping(new TextSpan(5, 5), "", "$.threshold"), FIXTURE).verdict());
    }

    @Test
    @DisplayName("每条结论都必须给出依据（含 VERIFIED，便于审计复核）")
    void everyVerdictCarriesItsReason() {
        VerificationResult r = MappingIr.verify(mapping("$10,000", "$.threshold"), FIXTURE);
        assertTrue(r.reason() != null && !r.reason().isBlank(),
            "VERIFIED 也必须说明依据，否则审计时无法复核。");
    }

    @Test
    @DisplayName("★跨引擎黄金向量：与 TS verifier 逐条同判（ADR §7 的 Verify_TS == Verify_Java）")
    void verdictsMatchTheTypeScriptEngine() {
        // ADR §7 要求两引擎对同一映射给出同一判定。下列期望值是从
        // **TS 侧 verifyMapping 实跑**取得的（aster-lang-ts，同一组输入），
        // 不是从 Java 自己的输出回填——后者会让本测试退化成「Java 和它自己一致」。
        //
        // 覆盖的分叉点：人类数字装饰、值矛盾、非精确值节点、Double 不可逆、
        // 尾随零规范化、超安全整数精度、布尔、负数、解析失败。
        record Case(String text, String kind, Object value, Verdict expected) {}
        Case[] golden = {
            new Case("$10,000", "Int", 10000, Verdict.VERIFIED),
            new Case("$20,000", "Int", 10000, Verdict.REJECTED),
            new Case("\"REFER\"", "String", "REFER", Verdict.VERIFIED),
            new Case("requires approval", "If", null, Verdict.REVIEW_REQUIRED),
            new Case("1.0", "Double", 1.0d, Verdict.REVIEW_REQUIRED),
            new Case("$10,000.00", "Decimal", "10000", Verdict.VERIFIED),
            new Case("9007199254740993", "Long", "9007199254740993", Verdict.VERIFIED),
            new Case("9007199254740992", "Long", "9007199254740993", Verdict.REJECTED),
            new Case("true", "Bool", Boolean.TRUE, Verdict.VERIFIED),
            new Case("false", "Bool", Boolean.TRUE, Verdict.REJECTED),
            new Case("10,000", "Int", 10000, Verdict.VERIFIED),
            new Case("  10000  ", "Int", 10000, Verdict.VERIFIED),
            new Case("-500", "Int", -500, Verdict.VERIFIED),
            new Case("0.10", "Decimal", "0.1", Verdict.VERIFIED),
            new Case("abc", "Int", 10000, Verdict.REVIEW_REQUIRED),
        };

        for (Case c : golden) {
            VerificationResult r = MappingIr.verify(
                new CandidateMapping(new TextSpan(0, c.text().length()), c.text(), "$.n"),
                id -> new VerifiableNode(c.kind(), c.value(), null));
            assertEquals(c.expected(), r.verdict(),
                "与 TS 引擎判定不一致，输入：「" + c.text() + "」 kind=" + c.kind()
                    + "\n★两侧对同一映射必须给出同一判定，否则 §7 的双引擎 verifier 不成立。"
                    + "\n本次判定依据：" + r.reason());
        }
    }
}
