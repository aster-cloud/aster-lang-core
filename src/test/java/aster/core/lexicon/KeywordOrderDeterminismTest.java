package aster.core.lexicon;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.embed.PolicySourceCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词表的遍历顺序必须与 JVM 进程无关。
 *
 * <p>历史 bug：DynamicLexicon 用 {@code Map.copyOf} 存关键词与别名，其遍历顺序按 JVM 启动时的随机 SALT 变化；
 * FallbackLexicon 以它为底合并，zh-CN / de-DE / hi-IN 于是都继承了随机顺序。zh-CN 的「包含」同时是 TYPE_WITH 与
 * TYPE_HAS 的拼写，Canonicalizer 建翻译表时后写者胜，于是同一份 zh 源在不同进程（不同 pod、重启前后）里一会儿
 * 规范化成 {@code with}、一会儿成 {@code has}。两者语法等价、IR 结构相同，但 Core IR 的 origin 列记的是规范化文本的
 * 列，「包含」之后同一行的列整体差 1——生产门禁里 4 个 zh 策略只在列上「时有时无」地变化即源于此。
 */
class KeywordOrderDeterminismTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "zh-CN", "de-DE", "hi-IN"})
    void keywordsAndAliasesIterateInDeclarationOrder(String id) {
        Lexicon lexicon = LexiconRegistry.getInstance().getOrThrow(id);
        assertInDeclarationOrder(lexicon.getKeywords(), id + " keywords");
        assertInDeclarationOrder(lexicon.getAliases(), id + " aliases");
    }

    @Test
    void zhSharedSpellingForTypeWithAndTypeHasCanonicalizesToWith() {
        Lexicon zh = LexiconRegistry.getInstance().getOrThrow("zh-CN");
        assertEquals("包含", zh.getKeywords().get(SemanticTokenKind.TYPE_WITH), "前提：zh 的 TYPE_WITH 拼写");
        assertEquals("包含", zh.getKeywords().get(SemanticTokenKind.TYPE_HAS), "前提：zh 的 TYPE_HAS 拼写");

        String canonical = new Canonicalizer(zh).canonicalize("定义 等级 包含 积分 作为 整数。");

        // 同一拼写对应多个 kind 时取声明顺序在前者：WITH 在语法里覆盖 HAS 的全部位置（Define / 构造 / 调用后缀），
        // 另外还能引出 lambda 形参、充当限定名段，取 with 不会让任何原先可解析的源失败
        assertTrue(canonical.contains(" with "), "应规范化成 with：" + canonical);
        assertFalse(canonical.contains(" has "), "不应规范化成 has：" + canonical);
    }

    @Test
    void zhFieldTypeColumnFollowsWithSpelling() throws Exception {
        String source = String.join("\n",
            "模块 保险.等级。",
            "",
            "定义 等级 包含 积分 作为 整数。",
            "");
        String coreJson = PolicySourceCompiler.compileCanonical(source, "zh-CN", null, null, false);
        JsonNode origin = MAPPER.readTree(coreJson).path("decls").get(0).path("fields").get(0).path("type").path("origin");

        // 规范化文本第 3 行为 `Define 等级 with 积分 as Int.`，Int 从第 22 列（1 起）开始；取 has 时会是 21
        assertEquals(22, origin.path("start").path("col").asInt(), coreJson);
        assertEquals(25, origin.path("end").path("col").asInt(), coreJson);
    }

    private static void assertInDeclarationOrder(Map<SemanticTokenKind, ?> map, String what) {
        List<SemanticTokenKind> actual = new ArrayList<>(map.keySet());
        List<SemanticTokenKind> expected = Arrays.stream(SemanticTokenKind.values()).filter(map::containsKey).toList();
        assertEquals(expected, actual, what + " 的遍历顺序应为 SemanticTokenKind 声明顺序（与 JVM 进程无关）");
    }
}
