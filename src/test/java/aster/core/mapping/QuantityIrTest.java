package aster.core.mapping;

import aster.core.mapping.MappingIr.CandidateMapping;
import aster.core.mapping.MappingIr.VerifiableNode;
import aster.core.mapping.MappingIr.Verdict;
import aster.core.mapping.QuantityIr.Quantity;
import aster.core.mapping.QuantityIr.QuantityKind;
import aster.core.mapping.SourceIr.SectionKind;
import aster.core.mapping.SourceIr.SourceNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuantityIR（ADR 0037 §2/§11），与 TS 侧
 * {@code test/unit/mapping/quantity-ir.test.ts} <b>逐条对等</b>。
 *
 * <p>核心主张：<b>Quantity 有形态特征、可机械抽取；Entity 没有、需要 LLM</b>。
 */
class QuantityIrTest {

    private static final String POLICY = String.join("\n",
        "# 付款审批政策", "",
        "## 阈值", "",
        "单笔付款超过 $10,000 时需要财务经理审批。",
        "低于 $10,000 的付款由部门主管批准即可。", "",
        "紧急付款上限为 $50,000，须在 24 小时内补批。", "",
        "## 手续费", "",
        "跨境付款收取 1.5% 手续费，最低 $25。", "",
        "## 生效日期", "",
        "本政策自 2026-01-01 起生效，2026-12-31 失效。", "");

    private static List<String> valuesOf(QuantityKind kind) {
        return QuantityIr.extract(POLICY).stream()
            .filter(q -> q.kind() == kind).map(Quantity::value).toList();
    }

    @Test
    @DisplayName("★抽出全部四类数量，且 value 已规范化")
    void extractsAllFourKinds() {
        assertEquals(List.of("10000", "10000", "50000", "25"), valuesOf(QuantityKind.MONEY));
        assertEquals(List.of("1.5"), valuesOf(QuantityKind.PERCENT));
        assertEquals(List.of("24"), valuesOf(QuantityKind.DURATION));
        assertEquals(List.of("2026-01-01", "2026-12-31"), valuesOf(QuantityKind.DATE));
    }

    @Test
    @DisplayName("★text 必须逐字节等于文档切片（与 SourceIR 同规则：不改内容）")
    void textEqualsDocumentSlice() {
        for (Quantity q : QuantityIr.extract(POLICY)) {
            assertEquals(POLICY.substring(q.span().start(), q.span().end()), q.text(),
                q.kind() + " 的 text 与切片不符 —— 说明做了文本改写。");
        }
    }

    @Test
    @DisplayName("★输出按位置升序且互不重叠")
    void outputIsSortedAndNonOverlapping() {
        // ★样本必须选**真会重叠**的输入。POLICY 里四类模式互斥，重叠永不发生，
        //   拿它做样本时把重叠检查整个删掉测试照样绿（TS 侧实测过）——假门禁。
        //   `$1.5%` 会同时匹配 MONEY(`$1.5`) 与 PERCENT(`1.5%`)，区间相交。
        for (String doc : new String[]{"$1.5% 混合", POLICY}) {
            List<Quantity> qs = QuantityIr.extract(doc);
            for (int i = 1; i < qs.size(); i++) {
                assertTrue(qs.get(i).span().start() >= qs.get(i - 1).span().end(),
                    qs.get(i - 1).text() + " 与 " + qs.get(i).text() + " 重叠或乱序"
                        + "\n★重叠会让同一段文本被抽成两个候选，映射时互相矛盾。");
            }
        }
    }

    @Test
    @DisplayName("★模式顺序决定重叠时谁胜出：MONEY 先于 PERCENT")
    void patternOrderDecidesOverlapWinner() {
        // ★这条才真正钉住 RULES 的顺序。`$1.5%` 是唯一会让两个模式相交的形态。
        //   把 MONEY 挪到 PERCENT 之后，本条立刻变红。
        List<Quantity> qs = QuantityIr.extract("$1.5% 混合");

        assertEquals(1, qs.size(), "重叠时应只保留一个，实际 " + qs);
        assertEquals(QuantityKind.MONEY, qs.get(0).kind(), "MONEY 先声明，应胜出。");
        assertEquals("$1.5", qs.get(0).text());
    }

    @Test
    @DisplayName("`$10,000` 整体抽取，千分位逗号去掉")
    void thousandSeparatorIsStripped() {
        List<Quantity> qs = QuantityIr.extract("付款 $10,000 元");
        assertEquals(1, qs.size(), "实际 " + qs);
        assertEquals("$10,000", qs.get(0).text());
        assertEquals("10000", qs.get(0).value());
    }

    @Test
    @DisplayName("★超安全整数的金额不得丢精度")
    void largeAmountKeepsPrecision() {
        // 一旦过一次浮点就变 …992（本仓实测踩过）。
        assertEquals("9007199254740993",
            QuantityIr.extract("上限 $9007199254740993 元").get(0).value(),
            "大额金额精度丢失 —— 说明中途经过了浮点。");
    }

    @Test
    @DisplayName("DATE 保持原串，不转时间戳")
    void dateKeepsIsoString() {
        // 转时间戳会引入时区，而时区不是原文里的信息。
        Quantity q = QuantityIr.extract("生效日 2026-01-01。").get(0);
        assertEquals("2026-01-01", q.value());
        assertEquals(null, q.unit());
    }

    @Test
    @DisplayName("★本模块不做语义校验（形态合法即抽出）")
    void noSemanticValidation() {
        // 13 月 45 日形态合法、语义非法 —— 照抽。语义合法性由下游 verifier
        // 或人判定，这与 MappingIR 的分工一致。
        List<String> dates = QuantityIr.extract("日期 2026-13-45 和 2026-01-01。").stream()
            .filter(q -> q.kind() == QuantityKind.DATE).map(Quantity::text).toList();
        assertEquals(List.of("2026-13-45", "2026-01-01"), dates);

        // 而**模式都匹配不上**的，自然不产出：
        assertEquals(List.of(), QuantityIr.extract("价格 $ 待定"));
    }

    @Test
    @DisplayName("★全链路：SourceIR 定位 → Quantity 抽取 → MappingIR 判定")
    void endToEndChain() {
        SourceNode ir = SourceIr.parse(POLICY);
        List<Quantity> money = QuantityIr.extract(POLICY).stream()
            .filter(q -> q.kind() == QuantityKind.MONEY).toList();
        assertTrue(!money.isEmpty(), "应抽出金额。");

        for (Quantity q : money) {
            SourceNode host = SourceIr.nodeAtOffset(ir, q.span().start());
            assertNotNull(host, q.text() + " 未落在任何结构节点内。");
            assertNotEquals(SectionKind.DOCUMENT, host.kind(),
                q.text() + " 应落在具体结构节点（段落）内，而非 DOCUMENT。");

            var r = MappingIr.verify(
                new CandidateMapping(q.span(), q.text(), "$.threshold"),
                id -> new VerifiableNode("Decimal", q.value(), null));
            assertEquals(Verdict.VERIFIED, r.verdict(),
                q.text() + " 应能验过 Decimal(" + q.value() + ")。实际：" + r.reason());
        }
    }

    @Test
    @DisplayName("★Entity 不由本模块抽取（边界写进类型系统）")
    void entitiesAreNotExtractedHere() {
        // 「财务经理」「部门主管」在文档里，但**不该**出现在 Quantity 输出里——
        // 它们没有形态特征，只能由 LLM/人提出 EntityCandidate。
        // 若哪天有人给本模块加了「角色识别」的正则，这条会变红。
        List<String> texts = QuantityIr.extract(POLICY).stream().map(Quantity::text).toList();
        for (String role : new String[]{"财务经理", "部门主管"}) {
            assertTrue(texts.stream().noneMatch(t -> t.contains(role)),
                role + " 出现在 Quantity 输出里 —— Entity 应由 LLM 提出，本模块不得猜测。");
        }
    }

    @Test
    @DisplayName("★跨引擎黄金向量：与 TS 逐字节同输出（ADR §7）")
    void outputMatchesTheTypeScriptEngine() {
        // 期望值取自 **TS 侧 extractQuantities 实跑**，不是从 Java 输出回填——
        // 后者会让本测试退化成「Java 和它自己一致」。
        String[] golden = {
            "MONEY|$10,000|10000|$|24,31",
            "MONEY|$10,000|10000|$|46,53",
            "MONEY|$50,000|50000|$|77,84",
            "DURATION|24 小时|24|小时|88,93",
            "PERCENT|1.5%|1.5|-|114,118",
            "MONEY|$25|25|$|126,129",
            "DATE|2026-01-01|2026-01-01|-|146,156",
            "DATE|2026-12-31|2026-12-31|-|161,171",
        };

        List<String> actual = QuantityIr.extract(POLICY).stream()
            .map(q -> q.kind() + "|" + q.text() + "|" + q.value() + "|"
                + (q.unit() == null ? "-" : q.unit()) + "|"
                + q.span().start() + "," + q.span().end())
            .toList();

        assertEquals(List.of(golden), actual,
            "与 TS 引擎输出不一致 —— §7 的双引擎一致性不成立。");
    }
}
