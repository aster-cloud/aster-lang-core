package aster.core.canonicalizer;

import aster.core.parser.AstBuilder;
import aster.core.ir.CoreModel;
import aster.core.lexicon.DynamicLexicon;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.LexiconValidator;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词别名（ADR 0022）单元测试。
 *
 * <p>核心不变式：别名在 canonicalize 阶段被归一成规范拼写，故「别名版」与「规范版」源码
 * 的规范化输出**逐字节相同** —— 这等价于"下游 token 流 / Core IR 相同"，是 IR 零损的根。
 *
 * <p>用真实 en-US builtin JSON 注入 aliases 段构造测试 lexicon，覆盖：
 * 声明关键词别名（Policy→Rule）、控制流别名（Whenever→If）、运算符别名（multiplied by→times）。
 */
class KeywordAliasTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 取 en-US builtin 的 JSON，注入 aliases 段后构造 DynamicLexicon。 */
    private static Lexicon enWithAliases() throws Exception {
        // 直接复用 builtin 资源，保证与生产 en-US 完全一致，只追加 aliases。
        String json = new String(
            KeywordAliasTest.class.getClassLoader()
                .getResourceAsStream("builtin/en-US.json").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        ObjectNode aliases = root.putObject("aliases");
        aliases.set("FUNC_TO", arr("Policy"));
        aliases.set("IF", arr("Whenever"));
        aliases.set("TIMES", arr("multiplied by"));
        return DynamicLexicon.fromJsonString(MAPPER.writeValueAsString(root));
    }

    private static ArrayNode arr(String... vals) {
        ArrayNode a = MAPPER.createArrayNode();
        for (String v : vals) a.add(v);
        return a;
    }

    @Test
    void aliasSourceCanonicalizesIdenticallyToCanonical() throws Exception {
        Lexicon lex = enWithAliases();
        Canonicalizer canon = new Canonicalizer(lex);

        // 别名版：Policy（=Rule）、Whenever（=If）、multiplied by（=times）
        String aliasSrc = """
            Module Pricing.

            Policy discountedPrice given amount as Int, produce Int:
              Whenever amount greater than 100
                Return amount multiplied by 90 divided by 100.
              Return amount.""";

        // 规范版：同一逻辑用规范拼写
        String canonicalSrc = """
            Module Pricing.

            Rule discountedPrice given amount as Int, produce Int:
              If amount greater than 100
                Return amount times 90 divided by 100.
              Return amount.""";

        String outAlias = canon.canonicalize(aliasSrc);
        String outCanonical = canon.canonicalize(canonicalSrc);

        // 规范化输出逐字节相同 → 下游 token/IR 必然相同（ADR 0022 不变式）
        assertThat(outAlias).isEqualTo(outCanonical);
    }

    @Test
    void noAliasesIsBackwardCompatible() throws Exception {
        // 显式移除 aliases 段的 lexicon：getAliases() 为空，行为与历史一致。
        // （builtin en-US.json 现已自带 aliases，故这里删掉该段以测"无别名"路径。）
        String json = new String(
            KeywordAliasTest.class.getClassLoader()
                .getResourceAsStream("builtin/en-US.json").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        root.remove("aliases");
        Lexicon lex = DynamicLexicon.fromJsonString(MAPPER.writeValueAsString(root));
        assertThat(lex.getAliases()).isEmpty();
    }

    @Test
    void aliasesAppearInKeywordIndexAndFindKind() throws Exception {
        Lexicon lex = enWithAliases();
        Map<String, SemanticTokenKind> index = lex.buildKeywordIndex();
        assertThat(index.get("policy")).isEqualTo(SemanticTokenKind.FUNC_TO);
        assertThat(index.get("whenever")).isEqualTo(SemanticTokenKind.IF);
        // 规范拼写仍在
        assertThat(index.get("rule")).isEqualTo(SemanticTokenKind.FUNC_TO);
        // findSemanticTokenKind 也认别名
        assertThat(lex.findSemanticTokenKind("Policy")).contains(SemanticTokenKind.FUNC_TO);
        // 多词别名进最长匹配集
        assertThat(lex.getMultiWordKeywords()).contains("multiplied by");
    }

    @Test
    void validatorRejectsAliasShadowingCanonical() throws Exception {
        // 别名 "If" 给 FUNC_TO → 撞 IF 的规范拼写 → 必须 error
        String json = new String(
            KeywordAliasTest.class.getClassLoader()
                .getResourceAsStream("builtin/en-US.json").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        root.putObject("aliases").set("FUNC_TO", arr("If"));
        Lexicon bad = DynamicLexicon.fromJsonString(MAPPER.writeValueAsString(root));

        LexiconRegistry.ValidationResult result = LexiconValidator.validateLexicon(bad);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("shadows canonical keyword"));
    }

    @Test
    void validatorRejectsDuplicateAliasAcrossKinds() throws Exception {
        // 同一别名 "Foo" 给两个 kind → error
        String json = new String(
            KeywordAliasTest.class.getClassLoader()
                .getResourceAsStream("builtin/en-US.json").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        ObjectNode aliases = root.putObject("aliases");
        aliases.set("FUNC_TO", arr("Foo"));
        aliases.set("TYPE_DEF", arr("Foo"));
        Lexicon bad = DynamicLexicon.fromJsonString(MAPPER.writeValueAsString(root));

        LexiconRegistry.ValidationResult result = LexiconValidator.validateLexicon(bad);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("defined for both"));
    }

    /**
     * 端到端：把别名经**编译期注入**（方案 D 形态：aliasSet 注入 lexicon，而非内置）
     * 走完整 Java 管线 Canonicalize → ANTLR Parse → AstBuilder → CoreLowering，断言
     * 「别名版」与「规范版」降到**结构一致的 Core IR**（剥离 origin，与 ADR 0016 同口径）。
     * 这是 Java 侧的「别名→同 IR」权威证明，与 ts keyword-aliases.test 对称。
     *
     * <p>官方 builtin 不含别名（方案 A 已回滚）：别名版用注入了多词别名的 lexicon，
     * 规范版用默认 builtin lexicon。
     */
    @Test
    void aliasLowersToSameCoreIrViaFullPipeline() throws Exception {
        // 注入多词别名（FUNC_TO=Policy 单词不可，故用多词运算符别名验证端到端）。
        Lexicon aliasLex = enWithAliases(); // 注入 FUNC_TO=Policy / IF=Whenever / TIMES=multiplied by
        String aliasSrc = """
            Module Pricing.

            Policy discountedPrice given amount as Int, produce Int:
              Whenever amount greater than 100
                Return amount multiplied by 90 divided by 100.
              Return amount.""";
        String canonicalSrc = """
            Module Pricing.

            Rule discountedPrice given amount as Int, produce Int:
              If amount greater than 100
                Return amount times 90 divided by 100.
              Return amount.""";

        JsonNode aliasIr = stripOrigin(lowerToCoreIr(aliasSrc, aliasLex));
        JsonNode canonIr = stripOrigin(lowerToCoreIr(canonicalSrc, null));

        assertThat(aliasIr).isEqualTo(canonIr);
    }

    /**
     * 走完整管线返回 Core IR JSON。lexicon 为 null 时用默认 builtin（无别名）。
     * 提供 lexicon 时用注入别名的 Canonicalizer（方案 D 编译期注入形态）。
     */
    private static JsonNode lowerToCoreIr(String source, Lexicon lexicon) {
        Canonicalizer canonicalizer = lexicon != null ? new Canonicalizer(lexicon) : new Canonicalizer();
        String canonical = canonicalizer.canonicalize(source);
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        tokens.seek(0);
        AsterParser parser = new AsterParser(tokens);
        AsterParser.ModuleContext moduleCtx = parser.module();
        aster.core.ast.Module ast = new AstBuilder().visitModule(moduleCtx);
        CoreModel.Module core = new CoreLowering().lowerModule(ast);
        return MAPPER.valueToTree(core);
    }

    /** 递归剥离 origin/span 等源码位置字段（派生层），用于结构级 IR 比较。 */
    private static JsonNode stripOrigin(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                if (k.equals("origin") || k.equals("span") || k.equals("line") || k.equals("col")) {
                    continue;
                }
                out.set(k, stripOrigin(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(stripOrigin(child));
            }
            return out;
        }
        return node;
    }
}
