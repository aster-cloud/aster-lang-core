package aster.core.embed;

import aster.core.identifier.VocabularyLoader;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 《静夜思》原诗即策略（ADR 0060 §8 证明第 3 条的 core 一侧）：zh-CN + 四个汉字单词结构词别名 + 两条内嵌词条，
 * 经生产编译入口 {@link PolicySourceCompiler#compileCanonical} 编出的 Core IR，其投影哈希与 aster-lang-ts
 * {@code jingyesi-policy.test.ts} 的 {@code JINGYESI_POSITION_FREE_SHA256} 相同——两引擎 Core IR 一致。
 */
class JingyesiPolicyTest {

    /** 与 aster-lang-ts JINGYESI_POSITION_FREE_SHA256 相同。 */
    static final String JINGYESI_POSITION_FREE_SHA256 = "55512170231ae56d7f01b8130ce368d650c5b390ad6f0ac7f34d5350c8960ec6";

    private static final String SOURCE = "床前 明月光。\n疑是 地上霜，举头 望明月：\n  低头 思故乡。\n";

    private static final Map<SemanticTokenKind, List<String>> ALIASES = Map.of(
        SemanticTokenKind.FUNC_PRODUCE, List.of("举头"),
        SemanticTokenKind.FUNC_TO, List.of("疑是"),
        SemanticTokenKind.MODULE_DECL, List.of("床前"),
        SemanticTokenKind.RETURN, List.of("低头"));

    private static final String VOCABULARY = """
        {"id":"policy.embedded","name":"policy","locale":"zh-CN","version":"1",
         "structs":[{"localized":"望明月","canonical":"Text","kind":"struct"}],
         "fields":[],"functions":[],"enumValues":[],
         "literals":[{"localized":"思故乡","canonical":"静夜思","kind":"literal"}]}""";

    private static VocabularyScopes scopes() throws Exception {
        return VocabularyScopes.build(VocabularyLoader.loadFromString(VOCABULARY));
    }

    @Test
    void compilesToTheSameCoreIrAsTs() throws Exception {
        String coreJson = PolicySourceCompiler.compileCanonical(SOURCE, "zh-CN", scopes(), ALIASES, true);
        JsonNode core = new ObjectMapper().readTree(coreJson);
        assertEquals("明月光", core.get("name").asText());
        assertEquals("地上霜", core.get("decls").get(0).get("name").asText());
        assertTrue(coreJson.contains("静夜思"), coreJson);
        assertEquals(JINGYESI_POSITION_FREE_SHA256, PolicySourceCompiler.positionFreeSha256(coreJson));
    }

    @Test
    void isRejectedWithoutTheStructuralGrant() {
        var e = assertThrows(InProcessCnlParser.CnlParseException.class, () ->
            PolicySourceCompiler.compileCanonical(SOURCE, "zh-CN", scopes(), ALIASES, false));
        assertTrue(e.getMessage().contains("FUNC_PRODUCE"), e.getMessage());
    }
}
