package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 未标类型 context 的 Map 推断及点访问行为，与 TypeScript Core IR 保持一致。
 */
class ContextMapFieldPathParityTest {

  private static CoreModel.Module lower(String body) {
    String canonical = new Canonicalizer().canonicalize("Module probe.\n\n" + body + "\n");
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    return new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
  }

  private static List<Diagnostic> check(String body) {
    return new TypeChecker().typecheckModule(lower(body));
  }

  private static final String GUARD_DECL =
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, "
          + "resource_type, resource_id, context, agent_model.\n\n";

  @Test
  void guardRequestContextSupportsNumericAndBooleanDottedComparisons() {
    var diags = check(GUARD_DECL
        + "Rule amountGuard given req as GuardRequest, produce Verdict:\n"
        + "  If req.context.amount at least 10000\n"
        + "    Return Verdict.allow().\n"
        + "  Return Verdict.allow().\n\n"
        + "Rule flagGuard given req as GuardRequest, produce Verdict:\n"
        + "  If req.context.flag equals to true\n"
        + "    Return Verdict.allow().\n"
        + "  Return Verdict.allow().");
    assertTrue(diags.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
        "Map 值为 Unknown 时应允许继续检查 context 点访问，实际: " + diags);
  }

  @Test
  void listStillRejectsDottedAccess() {
    var diags = check("Define Bag has tags as List of Text.\n\n"
        + "Rule inspect given bag as Bag, produce Text:\n"
        + "  Return bag.tags.x.");
    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.UNKNOWN_FIELD),
        "List 点访问仍须报告 E011，实际: " + diags);
  }

  @Test
  void explicitContextTypeIsPreservedAndMapUnknownIsSerialized() throws Exception {
    var module = lower("Define ExplicitRequest has context as Text.\n"
        + "Define GuardRequest has context.\n");
    var explicit = (CoreModel.Data) module.decls.stream()
        .filter(d -> d instanceof CoreModel.Data data && "ExplicitRequest".equals(data.name))
        .findFirst().orElseThrow();
    assertEquals("Text", ((CoreModel.TypeName) explicit.fields.get(0).type).name);

    var mapper = new ObjectMapper();
    JsonNode json = mapper.readTree(mapper.writeValueAsString(module));
    JsonNode guard = findData(json, "GuardRequest");
    JsonNode map = findField(guard, "context").get("type");
    assertEquals("Map", map.get("kind").asText());
    assertEquals("TypeName", map.get("key").get("kind").asText());
    assertEquals("Text", map.get("key").get("name").asText());
    assertEquals("TypeName", map.get("val").get("kind").asText());
    assertEquals("Unknown", map.get("val").get("name").asText());
  }

  private static JsonNode findData(JsonNode module, String name) {
    for (JsonNode decl : module.get("decls")) {
      if ("Data".equals(decl.get("kind").asText()) && name.equals(decl.get("name").asText())) {
        return decl;
      }
    }
    throw new AssertionError("未找到数据声明: " + name);
  }

  private static JsonNode findField(JsonNode data, String name) {
    for (JsonNode field : data.get("fields")) {
      if (name.equals(field.get("name").asText())) {
        return field;
      }
    }
    throw new AssertionError("未找到字段: " + name);
  }
}
