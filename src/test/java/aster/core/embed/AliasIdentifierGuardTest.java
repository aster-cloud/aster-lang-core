package aster.core.embed;

import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR 0060 §5.3 第 2 条 IR 名字守卫（E808）：夹具 {@code guardCases} 的按位置 / 按 kind 矩阵（core 侧），
 * 外加守卫自身的单元断言。aster-lang-ts {@code alias-validation-v2-parity.test.ts} 对同一夹具断言同样的结果。
 */
class AliasIdentifierGuardTest {

    private static final String E808 = "E808";

    @TestFactory
    List<DynamicTest> guardCases() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : AliasValidationV2Fixture.root().get("guardCases")) {
            tests.add(DynamicTest.dynamicTest(c.get("id").asText(), () -> runGuardCase(c)));
        }
        return tests;
    }

    private static void runGuardCase(JsonNode c) throws Exception {
        String expect = c.get("expect").asText();
        InProcessCnlParser.CnlParseException failure = null;
        try {
            InProcessCnlParser.parseWithUserAliases(c.get("source").asText(), c.get("locale").asText(),
                AliasValidationV2Fixture.scopes(c), AliasValidationV2Fixture.aliasSet(c),
                c.get("allowStructural").asBoolean());
        } catch (InProcessCnlParser.CnlParseException e) {
            failure = e;
        }
        switch (expect) {
            case "ok" -> {
                if (failure != null) {
                    fail("期望编译通过，实际失败: " + failure.getMessage());
                }
            }
            case "alias_identifier_collision" -> {
                if (failure == null) {
                    fail("期望 E808，实际编译通过");
                }
                Set<String> codes = new TreeSet<>();
                failure.getDiagnostics().forEach(d -> codes.add(String.valueOf(d.code())));
                assertEquals(Set.of(E808), codes, failure.getMessage());
                assertTrue(failure instanceof AliasIdentifierGuard.AliasIdentifierCollision, failure.getClass().getName());
            }
            case "rejected" -> {
                if (failure == null) {
                    fail("期望编译失败（" + c.path("note").asText() + "），实际编译通过");
                }
            }
            default -> fail("未知 expect: " + expect);
        }
    }

    @Test
    void forbiddenKeysHoldEnglishSpellingsAndHanAliasesOnlyForHanAliasedKinds() {
        var keys = AliasIdentifierGuard.forbiddenKeys(Map.of(
            SemanticTokenKind.RETURN, List.of("低头"),
            SemanticTokenKind.PLUS, List.of("接 着")), "zh-CN");
        // RETURN 有汉字单词别名：英文规范拼写与别名本身都在 K；PLUS 只有多词别名，不进 K
        assertEquals(Set.of("return", "低头"), keys.keySet());
    }

    @Test
    void guardIsOffWithoutHanSingleTokenAliases() {
        assertTrue(AliasIdentifierGuard.forbiddenKeys(Map.of(SemanticTokenKind.RETURN, List.of("answer it")), "en-US").isEmpty());
        // 非 zh locale 下的汉字单词不算汉字单词别名（校验器本就拒绝），守卫也不启用
        assertTrue(AliasIdentifierGuard.forbiddenKeys(Map.of(SemanticTokenKind.RETURN, List.of("低头")), "en-US").isEmpty());
        assertTrue(AliasIdentifierGuard.forbiddenKeys(null, "zh-CN").isEmpty());
    }

    @Test
    void diagnosticCarriesNameAliasAndKind() {
        String source = "模块 测试。\n\n规则 甲 给定 低头 作为 整数，产出 整数：\n  返回 1。\n";
        var e = assertThrows(AliasIdentifierGuard.AliasIdentifierCollision.class, () ->
            InProcessCnlParser.parseWithUserAliases(source, "zh-CN", null,
                Map.of(SemanticTokenKind.RETURN, List.of("低头")), true));
        var d = e.getDiagnostics().get(0);
        assertEquals(E808, d.code());
        assertEquals("低头", d.params().get("alias"));
        assertEquals("RETURN", d.params().get("kind"));
        // core 在文本层把别名译成英文关键词，名字位置得到的是 Return
        assertEquals("Return", d.params().get("name"));
        assertEquals(3, d.line());
    }

    @Test
    void sameSourceWithoutAliasesCompiles() {
        String source = "模块 测试。\n\n规则 甲 给定 低头 作为 整数，产出 整数：\n  返回 低头。\n";
        var result = InProcessCnlParser.parseWithUserAliases(source, "zh-CN", null, null, true);
        assertEquals("甲", result.firstFunctionName());
    }

    @Test
    void guardRunsBeforeNameResolution() {
        // t.低头 在没有守卫时是 E801（字段 Return 无法解析）；守卫须先于名称解析报 E808
        String source = "模块 测试。\n\n定义 工单 包含 数量 作为 整数。\n\n规则 甲 给定 t 作为 工单，产出 整数：\n  返回 t.低头。\n";
        var e = assertThrows(InProcessCnlParser.CnlParseException.class, () ->
            InProcessCnlParser.parseWithUserAliases(source, "zh-CN", null,
                Map.of(SemanticTokenKind.RETURN, List.of("低头")), true));
        assertFalse(e instanceof InProcessCnlParser.NameResolutionFailure, e.getMessage());
        assertEquals(E808, e.getDiagnostics().get(0).code());
    }
}
