package aster.core.embed;

import aster.core.identifier.VocabularyLoader;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR 0060 §5.4 共享夹具 {@code diagnostics/alias_validation_v2_parity.json} 的读取工具。
 *
 * <p>夹具与 aster-lang-ts {@code shared/alias_validation_v2_parity.json} 逐字节相同：两侧测试都断言其 sha256 等于
 * {@link #SHA256}，ts 侧另与本仓副本逐字节比较。改夹具须两仓同改并同步本常量与 ts 侧常量。
 */
final class AliasValidationV2Fixture {

    static final String RESOURCE = "/diagnostics/alias_validation_v2_parity.json";
    static final String SHA256 = "409aaafdd4c9c5a5fd4da137fd60b8956d4cf31335687a4ed025add3e2ef5e74";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AliasValidationV2Fixture() {}

    static byte[] bytes() throws Exception {
        try (InputStream in = AliasValidationV2Fixture.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " 不在 test classpath 中");
            }
            return in.readAllBytes();
        }
    }

    static String sha256() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes()));
    }

    static JsonNode root() throws Exception {
        return MAPPER.readTree(bytes());
    }

    static Map<SemanticTokenKind, List<String>> aliasSet(JsonNode c) {
        if (!c.hasNonNull("aliasSet")) {
            return null;
        }
        Map<SemanticTokenKind, List<String>> out = new LinkedHashMap<>();
        c.get("aliasSet").fields().forEachRemaining(e -> {
            List<String> list = new ArrayList<>();
            e.getValue().forEach(a -> list.add(a.asText()));
            out.put(SemanticTokenKind.valueOf(e.getKey()), list);
        });
        return out;
    }

    static VocabularyScopes scopes(JsonNode c) throws Exception {
        if (!c.hasNonNull("vocabulary")) {
            return null;
        }
        return VocabularyScopes.build(VocabularyLoader.loadFromString(c.get("vocabulary").toString()));
    }

    /**
     * 校验器错误消息 → cloud {@code AliasIssueCode} 名。顺序有意义：结构词消息也以「不允许为」开头，须先判。
     * 与 aster-lang-ts {@code alias-validation-v2-parity.test.ts} 的同名映射逐条相同。
     */
    static String issueCode(String message) {
        String[][] table = {
            {"别名集 kind 数量", "tooManyKinds"},
            {"不允许为结构词 ", "structuralNotGranted"},
            {"不允许为 ", "kindNotAllowed"},
            {" 的别名数量 ", "tooManyAliases"},
            {" 的别名不能为空", "empty"},
            {" 的别名长度 ", "tooLong"},
            {"含非规范空白", "nonCanonical"},
            {"含反引号", "containsBacktick"},
            {"必须是多词短语", "singleWord"},
            {"与某规范关键词或已有别名同形", "shadowsKeyword"},
            {"在多个 kind 间重复定义", "duplicate"},
            {"与领域词汇标识符同形", "collidesVocabulary"},
            {"与字面量宏触发词同形", "collidesVocabulary"},
        };
        for (String[] row : table) {
            if (message.contains(row[0])) {
                return row[1];
            }
        }
        throw new IllegalStateException("无法映射的校验器消息: " + message);
    }
}
