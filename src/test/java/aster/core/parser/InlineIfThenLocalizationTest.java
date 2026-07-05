package aster.core.parser;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lexicon.CanonicalizationConfig;
import aster.core.lexicon.ErrorMessages;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.PunctuationConfig;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0029：inline-if 的 then 连接词进入 SemanticTokenKind.THEN，并由 lexicon 翻译。
 *
 * <p>Parser/grammar 不改：方言词经 Canonicalizer 归一为英文 {@code then}，再由既有
 * ANTLR {@code THEN} token 和软关键字逻辑处理。
 */
class InlineIfThenLocalizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Case(String name, Lexicon lexicon, String thenWord, String source) {}

    @Test
    void fourLanguageThenConnectivesLowerToSameCoreIr() {
        List<Case> cases = List.of(
            new Case("en-US", LexiconRegistry.getInstance().getOrThrow("en-US"), "then", """
                Module adr0029.

                Rule decide given cond as Bool, produce Text:
                  Return if cond then "A" else "B"."""),
            new Case("zh-CN", localizedLexicon("zh-CN", "简体中文",
                punctuation("。", "，", "、", "：", "「", "」"),
                Map.ofEntries(
                    Map.entry(SemanticTokenKind.MODULE_DECL, "模块"),
                    Map.entry(SemanticTokenKind.FUNC_TO, "规则"),
                    Map.entry(SemanticTokenKind.FUNC_GIVEN, "给定"),
                    Map.entry(SemanticTokenKind.IMPORT_ALIAS, "作为"),
                    Map.entry(SemanticTokenKind.FUNC_PRODUCE, "产出"),
                    Map.entry(SemanticTokenKind.IF, "如果"),
                    Map.entry(SemanticTokenKind.THEN, "那么"),
                    Map.entry(SemanticTokenKind.OTHERWISE, "否则"),
                    Map.entry(SemanticTokenKind.RETURN, "返回"),
                    Map.entry(SemanticTokenKind.BOOL_TYPE, "布尔"),
                    Map.entry(SemanticTokenKind.TEXT, "文本"),
                    Map.entry(SemanticTokenKind.INT_TYPE, "整数")
                )), "那么", """
                模块 adr0029。

                规则 decide 给定 cond 作为 布尔, 产出 文本:
                  返回 如果 cond 那么 「A」 否则 「B」。"""),
            new Case("de-DE", localizedLexicon("de-DE", "Deutsch",
                punctuation(".", ",", ",", ":", "\"", "\""),
                Map.ofEntries(
                    Map.entry(SemanticTokenKind.MODULE_DECL, "Modul"),
                    Map.entry(SemanticTokenKind.FUNC_TO, "Regel"),
                    Map.entry(SemanticTokenKind.FUNC_GIVEN, "gegeben"),
                    Map.entry(SemanticTokenKind.IMPORT_ALIAS, "als"),
                    Map.entry(SemanticTokenKind.FUNC_PRODUCE, "liefert"),
                    Map.entry(SemanticTokenKind.IF, "wenn"),
                    Map.entry(SemanticTokenKind.THEN, "dann"),
                    Map.entry(SemanticTokenKind.OTHERWISE, "sonst"),
                    Map.entry(SemanticTokenKind.RETURN, "gib zurueck"),
                    Map.entry(SemanticTokenKind.BOOL_TYPE, "Boolesch")
                )), "dann", """
                Modul adr0029.

                Regel decide gegeben cond als Boolesch, liefert Text:
                  gib zurueck wenn cond dann "A" sonst "B"."""),
            new Case("hi-IN", localizedLexicon("hi-IN", "हिन्दी",
                punctuation("।", ",", ",", ":", "\"", "\""),
                Map.ofEntries(
                    Map.entry(SemanticTokenKind.MODULE_DECL, "मॉड्यूल"),
                    Map.entry(SemanticTokenKind.FUNC_TO, "नियम"),
                    Map.entry(SemanticTokenKind.FUNC_GIVEN, "दिया गया"),
                    Map.entry(SemanticTokenKind.IMPORT_ALIAS, "रूप में"),
                    Map.entry(SemanticTokenKind.FUNC_PRODUCE, "उत्पन्न"),
                    Map.entry(SemanticTokenKind.IF, "यदि"),
                    Map.entry(SemanticTokenKind.THEN, "तो"),
                    Map.entry(SemanticTokenKind.OTHERWISE, "अन्यथा"),
                    Map.entry(SemanticTokenKind.RETURN, "लौटाएं"),
                    Map.entry(SemanticTokenKind.BOOL_TYPE, "बूलियन"),
                    Map.entry(SemanticTokenKind.TEXT, "पाठ"),
                    Map.entry(SemanticTokenKind.INT_TYPE, "पूर्णांक")
                )), "तो", """
                मॉड्यूल adr0029।

                नियम decide दिया गया cond रूप में बूलियन, उत्पन्न पाठ:
                  लौटाएं यदि cond तो "A" अन्यथा "B"।""")
        );

        JsonNode expected = stripDerived(lowerToCoreIr(cases.get(0).source(), cases.get(0).lexicon()));
        for (Case c : cases) {
            JsonNode actual = stripDerived(lowerToCoreIr(c.source(), c.lexicon()));
            assertThat(actual).as(c.name()).isEqualTo(expected);
        }
    }

    @Test
    void localizedThenConnectivesCanonicalizeToEnglishThen() {
        for (Case c : List.of(
            new Case("zh-CN", localizedLexicon("zh-CN", "简体中文",
                punctuation("。", "，", "、", "：", "「", "」"),
                Map.ofEntries(
                    Map.entry(SemanticTokenKind.MODULE_DECL, "模块"),
                    Map.entry(SemanticTokenKind.FUNC_TO, "规则"),
                    Map.entry(SemanticTokenKind.FUNC_GIVEN, "给定"),
                    Map.entry(SemanticTokenKind.IMPORT_ALIAS, "作为"),
                    Map.entry(SemanticTokenKind.FUNC_PRODUCE, "产出"),
                    Map.entry(SemanticTokenKind.IF, "如果"),
                    Map.entry(SemanticTokenKind.THEN, "那么"),
                    Map.entry(SemanticTokenKind.OTHERWISE, "否则"),
                    Map.entry(SemanticTokenKind.RETURN, "返回"),
                    Map.entry(SemanticTokenKind.BOOL_TYPE, "布尔"),
                    Map.entry(SemanticTokenKind.TEXT, "文本")
                )), "那么",
                "模块 m。 规则 r 给定 cond 作为 布尔, 产出 文本: 返回 如果 cond 那么 「A」 否则 「B」。"),
            new Case("de-DE", localizedLexicon("de-DE", "Deutsch",
                punctuation(".", ",", ",", ":", "\"", "\""),
                Map.ofEntries(
                    Map.entry(SemanticTokenKind.MODULE_DECL, "Modul"),
                    Map.entry(SemanticTokenKind.FUNC_TO, "Regel"),
                    Map.entry(SemanticTokenKind.FUNC_GIVEN, "gegeben"),
                    Map.entry(SemanticTokenKind.IMPORT_ALIAS, "als"),
                    Map.entry(SemanticTokenKind.FUNC_PRODUCE, "liefert"),
                    Map.entry(SemanticTokenKind.IF, "wenn"),
                    Map.entry(SemanticTokenKind.THEN, "dann"),
                    Map.entry(SemanticTokenKind.OTHERWISE, "sonst"),
                    Map.entry(SemanticTokenKind.RETURN, "gib zurueck"),
                    Map.entry(SemanticTokenKind.BOOL_TYPE, "Boolesch")
                )), "dann",
                "Modul m. Regel r gegeben cond als Boolesch, liefert Text: gib zurueck wenn cond dann \"A\" sonst \"B\"."),
            new Case("hi-IN", localizedLexicon("hi-IN", "हिन्दी",
                punctuation("।", ",", ",", ":", "\"", "\""),
                Map.ofEntries(
                    Map.entry(SemanticTokenKind.MODULE_DECL, "मॉड्यूल"),
                    Map.entry(SemanticTokenKind.FUNC_TO, "नियम"),
                    Map.entry(SemanticTokenKind.FUNC_GIVEN, "दिया गया"),
                    Map.entry(SemanticTokenKind.IMPORT_ALIAS, "रूप में"),
                    Map.entry(SemanticTokenKind.FUNC_PRODUCE, "उत्पन्न"),
                    Map.entry(SemanticTokenKind.IF, "यदि"),
                    Map.entry(SemanticTokenKind.THEN, "तो"),
                    Map.entry(SemanticTokenKind.OTHERWISE, "अन्यथा"),
                    Map.entry(SemanticTokenKind.RETURN, "लौटाएं"),
                    Map.entry(SemanticTokenKind.BOOL_TYPE, "बूलियन"),
                    Map.entry(SemanticTokenKind.TEXT, "पाठ")
                )), "तो",
                "मॉड्यूल m। नियम r दिया गया cond रूप में बूलियन, उत्पन्न पाठ: लौटाएं यदि cond तो \"A\" अन्यथा \"B\"।")
        )) {
            String canonical = new Canonicalizer(c.lexicon()).canonicalize(c.source());
            assertThat(canonical).as(c.name()).contains(" then ");
            assertThat(canonical).as(c.name()).doesNotContain(c.thenWord());
        }
    }

    @Test
    void thenConnectivesRemainSoftKeywordsInFieldNames() {
        assertParsesAfterCanonicalize("Module m.\n\nDefine Box has then as Int.",
            LexiconRegistry.getInstance().getOrThrow("en-US"));

        Lexicon zh = localizedLexicon("zh-CN", "简体中文",
            punctuation("。", "，", "、", "：", "「", "」"),
            Map.of(SemanticTokenKind.MODULE_DECL, "模块",
                SemanticTokenKind.TYPE_DEF, "定义",
                SemanticTokenKind.TYPE_HAS, "包含",
                SemanticTokenKind.IMPORT_ALIAS, "作为",
                SemanticTokenKind.THEN, "那么",
                SemanticTokenKind.INT_TYPE, "整数"));
        assertParsesAfterCanonicalize("模块 m。\n\n定义 Box 包含 那么 作为 整数。", zh);
        assertParsesAfterCanonicalize("模块 m。\n\n定义 Box 包含 那么值 作为 整数。", zh);

        Lexicon de = localizedLexicon("de-DE", "Deutsch",
            punctuation(".", ",", ",", ":", "\"", "\""),
            Map.of(SemanticTokenKind.MODULE_DECL, "Modul",
                SemanticTokenKind.TYPE_DEF, "Definiere",
                SemanticTokenKind.TYPE_HAS, "hat",
                SemanticTokenKind.IMPORT_ALIAS, "als",
                SemanticTokenKind.THEN, "dann",
                SemanticTokenKind.INT_TYPE, "Ganzzahl"));
        assertParsesAfterCanonicalize("Modul m.\n\nDefiniere Box hat dann als Ganzzahl.", de);

        Lexicon hi = localizedLexicon("hi-IN", "हिन्दी",
            punctuation("।", ",", ",", ":", "\"", "\""),
            Map.of(SemanticTokenKind.MODULE_DECL, "मॉड्यूल",
                SemanticTokenKind.TYPE_DEF, "परिभाषित",
                SemanticTokenKind.TYPE_HAS, "रखता है",
                SemanticTokenKind.IMPORT_ALIAS, "रूप में",
                SemanticTokenKind.THEN, "तो",
                SemanticTokenKind.INT_TYPE, "पूर्णांक"));
        assertParsesAfterCanonicalize("मॉड्यूल m।\n\nपरिभाषित Box रखता है तो रूप में पूर्णांक।", hi);
    }

    @Test
    void localizedThenConnectivesRemainSoftKeywordsInParameterNames() {
        Lexicon zh = localizedLexicon("zh-CN", "简体中文",
            punctuation("。", "，", "、", "：", "「", "」"),
            Map.of(SemanticTokenKind.MODULE_DECL, "模块",
                SemanticTokenKind.FUNC_TO, "规则",
                SemanticTokenKind.FUNC_GIVEN, "给定",
                SemanticTokenKind.IMPORT_ALIAS, "作为",
                SemanticTokenKind.FUNC_PRODUCE, "产出",
                SemanticTokenKind.RETURN, "返回",
                SemanticTokenKind.THEN, "那么",
                SemanticTokenKind.INT_TYPE, "整数"));
        assertParsesAfterCanonicalize("""
            模块 m。

            规则 echo 给定 那么 作为 整数, 产出 整数:
              返回 那么。""", zh);

        Lexicon de = localizedLexicon("de-DE", "Deutsch",
            punctuation(".", ",", ",", ":", "\"", "\""),
            Map.of(SemanticTokenKind.MODULE_DECL, "Modul",
                SemanticTokenKind.FUNC_TO, "Regel",
                SemanticTokenKind.FUNC_GIVEN, "gegeben",
                SemanticTokenKind.IMPORT_ALIAS, "als",
                SemanticTokenKind.FUNC_PRODUCE, "liefert",
                SemanticTokenKind.RETURN, "gib zurueck",
                SemanticTokenKind.THEN, "dann",
                SemanticTokenKind.INT_TYPE, "Ganzzahl"));
        assertParsesAfterCanonicalize("""
            Modul m.

            Regel echo gegeben dann als Ganzzahl, liefert Ganzzahl:
              gib zurueck dann.""", de);

        Lexicon hi = localizedLexicon("hi-IN", "हिन्दी",
            punctuation("।", ",", ",", ":", "\"", "\""),
            Map.of(SemanticTokenKind.MODULE_DECL, "मॉड्यूल",
                SemanticTokenKind.FUNC_TO, "नियम",
                SemanticTokenKind.FUNC_GIVEN, "दिया गया",
                SemanticTokenKind.IMPORT_ALIAS, "रूप में",
                SemanticTokenKind.FUNC_PRODUCE, "उत्पन्न",
                SemanticTokenKind.RETURN, "लौटाएं",
                SemanticTokenKind.THEN, "तो",
                SemanticTokenKind.INT_TYPE, "पूर्णांक"));
        assertParsesAfterCanonicalize("""
            मॉड्यूल m।

            नियम echo दिया गया तो रूप में पूर्णांक, उत्पन्न पूर्णांक:
              लौटाएं तो।""", hi);
    }

    private static Lexicon localizedLexicon(
            String id,
            String name,
            PunctuationConfig punctuation,
            Map<SemanticTokenKind, String> overrides
    ) {
        Lexicon en = LexiconRegistry.getInstance().getOrThrow("en-US");
        Map<SemanticTokenKind, String> keywords = new EnumMap<>(en.getKeywords());
        keywords.putAll(overrides);
        return new Lexicon() {
            @Override public String getId() { return id; }
            @Override public String getName() { return name; }
            @Override public Direction getDirection() { return Direction.LTR; }
            @Override public Map<SemanticTokenKind, String> getKeywords() { return keywords; }
            @Override public PunctuationConfig getPunctuation() { return punctuation; }
            @Override public CanonicalizationConfig getCanonicalization() { return CanonicalizationConfig.defaults(); }
            @Override public ErrorMessages getMessages() { return en.getMessages(); }
        };
    }

    private static PunctuationConfig punctuation(
            String statementEnd,
            String listSeparator,
            String enumSeparator,
            String blockStart,
            String quoteOpen,
            String quoteClose
    ) {
        return new PunctuationConfig(statementEnd, listSeparator, enumSeparator, blockStart, quoteOpen, quoteClose, null, null);
    }

    private static JsonNode lowerToCoreIr(String source, Lexicon lexicon) {
        String canonical = new Canonicalizer(lexicon).canonicalize(source);
        Module ast = parseCanonical(canonical);
        CoreModel.Module core = new CoreLowering().lowerModule(ast);
        return MAPPER.valueToTree(core);
    }

    private static void assertParsesAfterCanonicalize(String source, Lexicon lexicon) {
        String canonical = new Canonicalizer(lexicon).canonicalize(source);
        parseCanonical(canonical);
    }

    private static Module parseCanonical(String canonical) {
        AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        AsterParser parser = new AsterParser(tokens);
        List<String> errors = new java.util.ArrayList<>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                    String msg, RecognitionException e) {
                errors.add("L" + line + ":" + charPositionInLine + " " + msg);
            }
        });
        AsterParser.ModuleContext moduleCtx = parser.module();
        assertTrue(errors.isEmpty(), () -> "解析失败: " + errors + "\ncanonical:\n" + canonical);
        return new AstBuilder().visitModule(moduleCtx);
    }

    private static JsonNode stripDerived(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                if (key.equals("origin") || key.equals("span") || key.equals("line") || key.equals("col")) {
                    continue;
                }
                out.set(key, stripDerived(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(stripDerived(child));
            }
            return out;
        }
        return node;
    }
}
