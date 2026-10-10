package aster.core.parser;

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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR 0046：Profile 声明与 When/Otherwise 语法糖在 core 前端的解析与降糖。 */
class GovernanceSugarParseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test void 声明档案写入模块并序列化() {
    var core = lower("Module m.\nProfile \"governed\".\n\nRule r produce Int:\n  Return 1.\n");
    assertEquals("governed", core.profile);
    assertEquals("governed", json(core).path("profile").asText());
  }

  @Test void 带连字符的档案id可声明() {
    var core = lower("Module m.\nProfile \"eu-ai-act-high-risk\".\n\nRule r produce Int:\n  Return 1.\n");
    assertEquals("eu-ai-act-high-risk", core.profile);
  }

  @Test void 未声明档案时不输出profile字段() {
    var core = lower("Module m.\n\nRule r produce Int:\n  Return 1.\n");
    assertNull(core.profile);
    assertFalse(json(core).has("profile"));
    assertFalse(MAPPER.valueToTree(core).has("profile"));
  }

  @Test void 小写profile参数名不受影响() {
    var core = lower("Module m.\n\nRule r given profile, produce Int:\n  Return 1.\n");
    assertNull(core.profile);
  }

  @Test void 档案id形态非法是解析错误() {
    var ex = assertThrows(RuntimeException.class, () -> lower("Module m.\nProfile \"Bad_Id\".\n"));
    assertTrue(ex.getMessage().contains("Profile id must match ^[a-z][a-z0-9-]{0,63}$: Bad_Id"), ex.getMessage());
  }

  @Test void 非Profile词的声明是解析错误() {
    var ex = assertThrows(RuntimeException.class, () -> lower("Module m.\nProfil \"governed\".\n"));
    assertTrue(ex.getMessage().contains("Expected 'Profile' declaration after Module header"), ex.getMessage());
  }

  @Test void 语法糖与长写法IR相等() {
    String sugar = String.join("\n", "Module m.", "",
      "Rule decide given amount, produce Verdict:",
      "  When amount at least 100, require approval by \"Officer\" because \"large\".",
      "  When amount at most 0, deny \"negative\".",
      "  When amount at least 50, escalate \"review\".",
      "  Otherwise allow.", "");
    String longForm = String.join("\n", "Module m.", "",
      "Rule decide given amount, produce Verdict:",
      "  If amount at least 100:",
      "    Return Verdict.require_approval(\"Officer\", \"large\").",
      "  If amount at most 0:",
      "    Return Verdict.deny(\"negative\").",
      "  If amount at least 50:",
      "    Return Verdict.escalate(\"review\").",
      "  Return Verdict.allow().", "");
    assertEquals(json(lower(longForm)), json(lower(sugar)));
  }

  @Test void Otherwise之后还有语句是解析错误() {
    var ex = assertThrows(RuntimeException.class, () -> lower(String.join("\n", "Module m.", "",
      "Rule r produce Verdict:", "  Otherwise allow.", "  Return Verdict.deny(\"x\").", "")));
    assertTrue(ex.getMessage().contains("Otherwise must be the last statement of its block"), ex.getMessage());
  }

  @Test void 结论词组合非法是解析错误() {
    for (String outcome : List.of("allow \"x\"", "deny", "approve \"x\"",
        "require approval by \"a\"", "require approval by \"a\" since \"b\"")) {
      var ex = assertThrows(RuntimeException.class, () -> lower(String.join("\n", "Module m.", "",
        "Rule r given n, produce Verdict:", "  When n at least 1, " + outcome + ".", "  Otherwise allow.", "")),
        outcome);
      assertTrue(ex.getMessage().contains(
        "Expected allow, deny, escalate or require approval by … because … after When/Otherwise"), ex.getMessage());
    }
  }

  @Test void 结论含非单词记号同样报结论错误而非语法错误() {
    for (String outcome : List.of("deny 42", "deny \"a\" + \"b\"", "escalate (\"x\")", "allow, deny \"x\"",
        "deny \"x\" because 1", "require approval by \"a\" because \"b\" now", "allow 1.5")) {
      for (String stmt : List.of("  When n at least 1, " + outcome + ".", "  Otherwise " + outcome + ".")) {
        var ex = assertThrows(RuntimeException.class, () -> lower(String.join("\n", "Module m.", "",
          "Rule r given n, produce Verdict:", stmt, "")), stmt);
        assertTrue(ex.getMessage().contains(
          "Expected allow, deny, escalate or require approval by … because … after When/Otherwise"), ex.getMessage());
      }
    }
  }

  @Test void If块后Otherwise紧跟逗号或冒号是普通语法错误() {
    for (String tail : List.of("  Otherwise, Return Verdict.allow().", "  Otherwise: Return Verdict.allow().")) {
      var ex = assertThrows(RuntimeException.class, () -> lower(String.join("\n", "Module m.", "",
        "Rule r given n, produce Verdict:", "  If n at least 1:", "    Return Verdict.deny(\"x\").", tail, "")), tail);
      assertTrue(ex.getMessage().startsWith("syntax errors"), ex.getMessage());
      assertFalse(ex.getMessage().contains("after When/Otherwise"), ex.getMessage());
      assertFalse(ex.getMessage().contains("Otherwise must be the last statement"), ex.getMessage());
    }
  }

  @Test void If块后的Otherwise换行块仍是else分支() {
    var core = lower(String.join("\n", "Module m.", "", "Rule r given n, produce Int:",
      "  If n at least 1:", "    Return 1.", "  Otherwise:", "    Return 2.", ""));
    var first = firstStmt(core);
    var ifStmt = assertInstanceOf(CoreModel.If.class, first);
    assertNotNull(ifStmt.elseBlock);
    assertEquals(1, ifStmt.elseBlock.statements.size());
    assertEquals(1, ((CoreModel.Func) core.decls.get(0)).body.statements.size());
  }

  @Test void If块后紧随Otherwise语法糖是独立语句() {
    var core = lower(String.join("\n", "Module m.", "", "Rule r given n, produce Verdict:",
      "  If n at least 1:", "    Return Verdict.deny(\"x\").", "  Otherwise allow.", ""));
    var body = ((CoreModel.Func) core.decls.get(0)).body.statements;
    assertEquals(2, body.size());
    assertNull(assertInstanceOf(CoreModel.If.class, body.get(0)).elseBlock);
    assertInstanceOf(CoreModel.Return.class, body.get(1));
  }

  @Test void Match分支的When不受影响() {
    var core = lower(String.join("\n", "Module m.", "", "Rule r given n, produce Int:",
      "  Match n:", "    When 1, Return 10.", "    When x, Return 20.", ""));
    assertInstanceOf(CoreModel.Match.class, firstStmt(core));
  }

  @Test void Verdict成员调用不受新词影响() {
    var core = lower("Module m.\n\nRule r produce Verdict:\n  Return Verdict.allow().\n");
    var ret = assertInstanceOf(CoreModel.Return.class, firstStmt(core));
    var call = assertInstanceOf(CoreModel.Call.class, ret.expr);
    assertEquals("Verdict.allow", ((CoreModel.Name) call.target).name);
  }

  @Test void 中文与德文档案与语法糖同样降糖() {
    var registry = LexiconRegistry.getInstance();
    // 英文长写法用不删冠词的规范化配置，保留与中德源文同名的参数 a
    Lexicon en = derived(registry.getOrThrow("en-US"), Map.of(), CanonicalizationConfig.defaults());
    JsonNode expected = json(lower(String.join("\n", "Module m.", "Profile \"governed\".", "",
      "Rule r given a produce Verdict:",
      "  If a at least 100:", "    Return Verdict.require_approval(\"Officer\", \"large\").",
      "  If a at least 50:", "    Return Verdict.escalate(\"review\").",
      "  If a at least 1:", "    Return Verdict.deny(\"x\").",
      "  Return Verdict.allow().", ""), en));
    String zhSrc = String.join("\n", "模块 m。", "档案 \"governed\"。", "",
      "规则 r 给定 a 产出 Verdict：",
      "  当 a 至少 100，需审批人 \"Officer\" 因为 \"large\"。",
      "  当 a 至少 50，升级 \"review\"。",
      "  当 a 至少 1，拒绝 \"x\"。",
      "  否则 允许。", "");
    String deSrc = String.join("\n", "Modul m.", "Profil \"governed\".", "",
      "Regel r gegeben a liefert Verdict:",
      "  bei a mindestens 100, Genehmigung durch \"Officer\" weil \"large\".",
      "  bei a mindestens 50, eskalieren \"review\".",
      "  bei a mindestens 1, ablehnen \"x\".",
      "  sonst erlauben.", "");
    var zh = lower(zhSrc, registry.getOrThrow("zh-CN"));
    var de = lower(deSrc, registry.getOrThrow("de-DE"));
    assertEquals("governed", zh.profile);
    assertEquals("governed", de.profile);
    assertEquals(expected, json(zh), "zh-CN");
    assertEquals(expected, json(de), "de-DE");
  }

  @Test void 印地语档案与语法糖同样降糖() {
    var registry = LexiconRegistry.getInstance();
    // 用词取自真实 hi-IN 词法包（暂定，待母语审校）；与中德用例同一策略与期望
    Lexicon en = derived(registry.getOrThrow("en-US"), Map.of(), CanonicalizationConfig.defaults());
    JsonNode expected = json(lower(String.join("\n", "Module m.", "Profile \"governed\".", "",
      "Rule r given a produce Verdict:",
      "  If a at least 100:", "    Return Verdict.require_approval(\"Officer\", \"large\").",
      "  If a at least 50:", "    Return Verdict.escalate(\"review\").",
      "  If a at least 1:", "    Return Verdict.deny(\"x\").",
      "  Return Verdict.allow().", ""), en));
    String hiSrc = String.join("\n", "मॉड्यूल m।", "प्रोफ़ाइल \"governed\"।", "",
      "नियम r दिया गया a उत्पन्न Verdict:",
      "  जब a कम से कम 100, अनुमोदक \"Officer\" क्योंकि \"large\"।",
      "  जब a कम से कम 50, आगे बढ़ाएँ \"review\"।",
      "  जब a कम से कम 1, अस्वीकार \"x\"।",
      "  अन्यथा अनुमति।", "");
    var hi = lower(hiSrc, registry.getOrThrow("hi-IN"));
    assertEquals("governed", hi.profile);
    assertEquals(expected, json(hi), "hi-IN");
  }

  // 辅助：英文默认词法包的完整管线
  private static CoreModel.Module lower(String src) {
    return lower(src, LexiconRegistry.getInstance().getOrThrow("en-US"));
  }

  // 辅助：规范化 → 词法 → 解析（语法错误即失败）→ AST → Core IR
  private static CoreModel.Module lower(String src, Lexicon lexicon) {
    String canonical = new Canonicalizer(lexicon).canonicalize(src);
    var tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical)));
    var parser = new AsterParser(tokens);
    List<String> errors = new ArrayList<>();
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        errors.add("L" + line + ":" + col + " " + msg);
      }
    });
    var moduleCtx = parser.module();
    if (!errors.isEmpty()) {
      throw new IllegalStateException("syntax errors " + errors + "\ncanonical:\n" + canonical);
    }
    return new CoreLowering().lowerModule(new AstBuilder().visitModule(moduleCtx));
  }

  private static CoreModel.Stmt firstStmt(CoreModel.Module core) {
    return ((CoreModel.Func) core.decls.get(0)).body.statements.get(0);
  }

  // 辅助：序列化并去掉所有 origin 键（语法糖与长写法行号不同）
  private static JsonNode json(CoreModel.Module core) {
    return stripOrigin(MAPPER.valueToTree(core));
  }

  private static JsonNode stripOrigin(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      ObjectNode out = MAPPER.createObjectNode();
      obj.fields().forEachRemaining(e -> {
        if (!e.getKey().equals("origin")) out.set(e.getKey(), stripOrigin(e.getValue()));
      });
      return out;
    }
    if (node instanceof ArrayNode arr) {
      ArrayNode out = MAPPER.createArrayNode();
      arr.forEach(child -> out.add(stripOrigin(child)));
      return out;
    }
    return node;
  }

  private static Lexicon derived(Lexicon base, Map<SemanticTokenKind, String> overrides,
                                 CanonicalizationConfig canonicalization) {
    Map<SemanticTokenKind, String> keywords = new EnumMap<>(base.getKeywords());
    keywords.putAll(overrides);
    return new Lexicon() {
      @Override public String getId() { return base.getId(); }
      @Override public String getName() { return base.getName(); }
      @Override public Direction getDirection() { return base.getDirection(); }
      @Override public Map<SemanticTokenKind, String> getKeywords() { return keywords; }
      @Override public PunctuationConfig getPunctuation() { return base.getPunctuation(); }
      @Override public CanonicalizationConfig getCanonicalization() { return canonicalization; }
      @Override public ErrorMessages getMessages() { return base.getMessages(); }
    };
  }
}
