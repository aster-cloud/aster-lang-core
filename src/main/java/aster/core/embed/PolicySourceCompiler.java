package aster.core.embed;

import aster.core.canonical.CanonicalJson;
import aster.core.identifier.VocabularyLoader;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreIrFormatException;
import aster.core.ir.CoreIrJson;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 生产编译路径的公共入口（ADR 0059 P1 T6）：与 aster-api {@code PolicyCompiler} 的编译序列相同——
 * aliasSet 为空走 {@link InProcessCnlParser#parse(String, String, VocabularyScopes)}，非空走
 * {@link InProcessCnlParser#parseWithUserAliases(String, String, VocabularyScopes, Map, boolean)}（先强制校验别名），
 * 再以 {@link CoreIrJson#serialize} 得到规范 IR 字节。
 *
 * <p>本包（{@code aster.core.embed}）里的解析器闭包是 aster-api {@code replay} 模块 {@code io.aster.policy.parser}
 * 的逐字副本（只改包名与日志实现）。aster-api 改为依赖本包并删除自身副本是 ADR 0059 P2 第一项，在此之前两份实现并存，
 * 改动任一侧都必须同步另一侧。
 *
 * <p>本类不做类型检查：B 包的 IR 由平台在导出前编译并类型检查过，CLI 只需重现其字节并按
 * {@link #positionFreeSha256(String)} 比对（修订 1 A2）。
 */
public final class PolicySourceCompiler {

    /** 与 aster-api {@code PolicyVersion.scopesOf} 相同：vanilla Jackson。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PolicySourceCompiler() {}

    /**
     * 编译 CNL 源码为规范 Core IR v2 文本。
     *
     * @param source          CNL 源码
     * @param locale          语言代码（如 {@code en-US}），null 为默认英语
     * @param scopes          领域词汇作用域，null 为无词汇
     * @param aliasSet        用户别名（kind → 别名），null / 空为无别名
     * @param allowStructural 是否放行结构词别名（冻结的已审批版本为 true）
     * @return {@link CoreIrJson#serialize} 的输出
     * @throws InProcessCnlParser.CnlParseException 语法、名称解析或别名校验失败（含子类 {@link InProcessCnlParser.NameResolutionFailure}）
     */
    public static String compileCanonical(String source, String locale, VocabularyScopes scopes,
                                          Map<SemanticTokenKind, List<String>> aliasSet, boolean allowStructural) {
        InProcessCnlParser.ParseResult result = (aliasSet == null || aliasSet.isEmpty())
            ? InProcessCnlParser.parse(source, locale, scopes)
            : InProcessCnlParser.parseWithUserAliases(source, locale, scopes, aliasSet, allowStructural);
        return CoreIrJson.serialize(result.core());
    }

    /**
     * {@code coreIr.positionFreeSha256}（ADR 0059 修订 1 A2）：严格读入 Core IR 后只删除每个 {@code origin} 的
     * {@code start.col} 与 {@code end.col}，按规范 JSON（{@link CanonicalJson#canonicalJson(JsonNode)}）序列化，
     * 取其 UTF-8 字节的 SHA-256（小写 hex）。定义与 aster-lang-test {@code scripts/lib/package-v1/position-free.mjs} 相同。
     *
     * @throws CoreIrFormatException IR 不能严格读入（版本、schema 或不变式不符）
     */
    public static String positionFreeSha256(String coreJson) throws CoreIrFormatException {
        JsonNode tree = CoreIrJson.readTree(coreJson).deepCopy();
        dropOriginColumns(tree);
        return sha256Hex(CanonicalJson.canonicalJson(tree).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析冻结的 aliasSet JSON（{@code {"TIMES":["multiplied by"],...}}），规则同 aster-api
     * {@code PolicyCompiler.parseAliasSetJson}：null / 空白为无别名；另把 JSON 字面量 {@code null} 视为无别名
     * （包内 {@code policy/compile/alias-set.json} 无别名时写作 {@code null}，ADR 0059 §2）；非法 JSON 或未知 kind 一律抛
     * {@link IllegalStateException}（fail-closed，绝不回落为无别名）。
     */
    public static Map<SemanticTokenKind, List<String>> parseAliasSetJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> raw;
        try {
            raw = MAPPER.readValue(json, new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("alias_set JSON 损坏，拒绝编译（fail-closed）: " + e.getMessage(), e);
        }
        if (raw == null) {
            return Map.of();
        }
        Map<SemanticTokenKind, List<String>> out = new EnumMap<>(SemanticTokenKind.class);
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            SemanticTokenKind kind;
            try {
                kind = SemanticTokenKind.valueOf(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("alias_set 含未知 kind '" + e.getKey() + "'，拒绝编译（fail-closed）", ex);
            }
            out.put(kind, List.copyOf(e.getValue()));
        }
        return out;
    }

    /**
     * 由冻结词汇 JSON 构建编译用作用域，规则同 aster-api {@code PolicyVersion.scopesOf}：null / 空白 / 无任何条目为 null；
     * 非法 JSON 或无法解析抛 {@link IllegalStateException}（fail-closed）。
     */
    public static VocabularyScopes vocabularyScopesOf(String vocabularyJson) {
        if (vocabularyJson == null || vocabularyJson.isBlank()) {
            return null;
        }
        Map<String, Object> map;
        try {
            map = MAPPER.readValue(vocabularyJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("冻结词汇不是合法 JSON: " + e.getMessage(), e);
        }
        if (map == null || map.isEmpty()) {
            return null;
        }
        VocabularyScopes scopes;
        try {
            scopes = VocabularyScopes.build(VocabularyLoader.loadFromMap(map));
        } catch (RuntimeException e) {
            throw new IllegalStateException("冻结词汇无法解析: " + e.getMessage(), e);
        }
        return hasEntries(scopes) ? scopes : null;
    }

    /** 词汇是否至少有一个条目（与 aster-api {@code CompileIdentity.hasEntries} 相同）。 */
    private static boolean hasEntries(VocabularyScopes s) {
        return s != null && !(s.types().isEmpty() && s.fields().isEmpty() && s.enumMembers().isEmpty()
            && s.functions().isEmpty() && s.literals().isEmpty());
    }

    /** 递归删除 origin 的两个列号；严格读入已保证每个 origin 的 start / end 都带整数 col。 */
    private static void dropOriginColumns(JsonNode node) {
        if (node.isArray()) {
            node.forEach(PolicySourceCompiler::dropOriginColumns);
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode origin = node.get("origin");
        if (origin != null && origin.isObject()) {
            for (String side : new String[]{"start", "end"}) {
                if (origin.get(side) instanceof ObjectNode pos) {
                    pos.remove("col");
                }
            }
        }
        node.forEach(PolicySourceCompiler::dropOriginColumns);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
