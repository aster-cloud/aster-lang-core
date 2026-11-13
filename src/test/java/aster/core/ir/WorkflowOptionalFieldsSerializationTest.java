package aster.core.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * workflow 可选字段的 IR JSON 协议：{@code Workflow.retry}、{@code Workflow.timeout}、{@code Step.compensate}
 * 无值时恒输出 null（ADR 0055 #19，取代 issue #219 的「null 不输出」），有值时照常输出。
 */
class WorkflowOptionalFieldsSerializationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  /** 同一源码的 v2 workflow 节点（去 origin）：两引擎都须产出此形状（ADR 0055 #19）。 */
  private static final String TS_SINGLE_STEP =
      "{\"kind\":\"workflow\",\"steps\":[{\"kind\":\"step\",\"name\":\"a\",\"body\":{\"kind\":\"Block\","
          + "\"statements\":[{\"kind\":\"Return\",\"expr\":{\"kind\":\"Ok\",\"expr\":{\"kind\":\"Int\",\"value\":1}}}]},"
          + "\"compensate\":null,\"dependencies\":[],\"effectCaps\":[]}],\"effectCaps\":[],\"retry\":null,\"timeout\":null}";

  private static final String TS_TWO_STEPS_RETRY_TIMEOUT =
      "{\"kind\":\"workflow\",\"steps\":[{\"kind\":\"step\",\"name\":\"a\",\"body\":{\"kind\":\"Block\","
          + "\"statements\":[{\"kind\":\"Return\",\"expr\":{\"kind\":\"Ok\",\"expr\":{\"kind\":\"Int\",\"value\":1}}}]},"
          + "\"compensate\":null,\"dependencies\":[],\"effectCaps\":[]},{\"kind\":\"step\",\"name\":\"b\",\"body\":{\"kind\":\"Block\","
          + "\"statements\":[{\"kind\":\"Return\",\"expr\":{\"kind\":\"Ok\",\"expr\":{\"kind\":\"Int\",\"value\":2}}}]},"
          + "\"compensate\":null,\"dependencies\":[\"a\"],\"effectCaps\":[]}],\"effectCaps\":[],"
          + "\"retry\":{\"maxAttempts\":3,\"backoff\":\"linear\"},\"timeout\":{\"milliseconds\":30000}}";

  private static CoreModel.Block emptyBlock() {
    var b = new CoreModel.Block();
    b.statements = List.of();
    return b;
  }

  private static CoreModel.Step step(String name, CoreModel.Block compensate) {
    var s = new CoreModel.Step();
    s.name = name;
    s.body = emptyBlock();
    s.compensate = compensate;
    return s;
  }

  private static CoreModel.Workflow workflow(CoreModel.RetryPolicy retry, CoreModel.Timeout timeout, CoreModel.Step... steps) {
    var wf = new CoreModel.Workflow();
    wf.steps = List.of(steps);
    wf.retry = retry;
    wf.timeout = timeout;
    return wf;
  }

  @Test
  void nullOptionalFieldsAreSerializedAsNull() throws Exception {
    JsonNode json = mapper.valueToTree(workflow(null, null, step("a", null)));
    assertTrue(json.get("retry").isNull(), json.toString());
    assertTrue(json.get("timeout").isNull(), json.toString());
    assertTrue(json.get("steps").get(0).get("compensate").isNull(), json.toString());
  }

  @Test
  void presentOptionalFieldsAreSerializedWithTsShape() throws Exception {
    var retry = new CoreModel.RetryPolicy();
    retry.maxAttempts = 3;
    retry.backoff = "exponential";
    var timeout = new CoreModel.Timeout();
    timeout.milliseconds = 5000;
    JsonNode json = mapper.readTree(mapper.writeValueAsString(workflow(retry, timeout, step("a", emptyBlock()))));
    assertEquals(mapper.readTree("{\"maxAttempts\":3,\"backoff\":\"exponential\"}"), json.get("retry"));
    assertEquals(mapper.readTree("{\"milliseconds\":5000}"), json.get("timeout"));
    assertEquals("Block", json.get("steps").get(0).get("compensate").get("kind").asText());
  }

  @Test
  void stepCompensateIsNullOnlyWhereAbsent() throws Exception {
    JsonNode steps = mapper.valueToTree(workflow(null, null, step("a", emptyBlock()), step("b", null))).get("steps");
    assertEquals("Block", steps.get(0).get("compensate").get("kind").asText());
    assertTrue(steps.get(1).get("compensate").isNull());
  }

  @Test
  void loweredSingleStepWorkflowMatchesTsIr() throws Exception {
    JsonNode wf = loweredWorkflow("Module p.\nRule r, produce. It performs io:\n"
        + "  workflow:\n    step a:\n      Return ok of 1.\n  .\n");
    assertEquals(mapper.readTree(TS_SINGLE_STEP), wf);
  }

  @Test
  void loweredTwoStepWorkflowWithRetryAndTimeoutMatchesTsIr() throws Exception {
    JsonNode wf = loweredWorkflow("Module p.\nRule r, produce. It performs io:\n"
        + "  workflow:\n    step a:\n      Return ok of 1.\n\n    step b:\n      Return ok of 2.\n\n"
        + "    retry:\n      max attempts: 3.\n      backoff: linear.\n\n    timeout: 30 seconds.\n  .\n");
    assertEquals(mapper.readTree(TS_TWO_STEPS_RETRY_TIMEOUT), wf);
  }

  /**
   * 源码 → Core IR JSON，取唯一函数体的 workflow 节点并去掉 origin。
   * 经字符串重新解析，使数值节点类型与读入 TS JSON 时一致（JsonNode 相等与键顺序无关）。
   */
  private JsonNode loweredWorkflow(String src) throws Exception {
    var lexer = new AsterCustomLexer(CharStreams.fromString(new Canonicalizer().canonicalize(src)));
    var parser = new AsterParser(new CommonTokenStream(lexer));
    var ast = new AstBuilder().visitModule(parser.module());
    assertNotNull(ast);
    JsonNode ir = mapper.readTree(mapper.writeValueAsString(new CoreLowering().lowerModule(ast)));
    JsonNode wf = ir.get("decls").get(0).get("body").get("statements").get(0);
    stripOrigin(wf);
    return wf;
  }

  private static void stripOrigin(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      obj.remove("origin");
      obj.forEach(WorkflowOptionalFieldsSerializationTest::stripOrigin);
    } else if (node.isArray()) {
      node.forEach(WorkflowOptionalFieldsSerializationTest::stripOrigin);
    }
  }
}
