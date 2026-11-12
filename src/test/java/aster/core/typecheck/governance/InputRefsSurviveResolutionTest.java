package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0046 §11.6 第 1 条：inputRefs 在 R2 之后计算并随 IR JSON 输出（完整编译通道 resolveAndLowerChecked）；
 * 序列化后再读回的 IR 保留该字段（NON_NULL：只在有值时出现）。
 */
class InputRefsSurviveResolutionTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SRC = "Module guard.survive.\nProfile \"governed\".\n\n"
      + "Define Ctx has amount as Decimal.\nDefine Req has action as Text, context as Ctx.\n\n"
      + "Rule helper given c as Ctx, produce Bool:\n  Return c.amount at least 1.\n\n"
      + "@id(\"R-1\")\nRule decide given req as Req, produce Verdict:\n"
      + "  If helper(req.context):\n    Return Verdict.deny(\"d\").\n  Return Verdict.allow().\n";

  @Test void 完整编译通道的IR_JSON含inputRefs() throws Exception {
    CoreModel.Module core = GovernanceTestSupport.resolveSource(SRC, null, null);
    JsonNode json = MAPPER.valueToTree(core);
    JsonNode decide = null;
    JsonNode helper = null;
    for (JsonNode d : json.path("decls")) {
      if ("decide".equals(d.path("name").asText())) decide = d;
      if ("helper".equals(d.path("name").asText())) helper = d;
    }
    assertThat(decide).isNotNull();
    assertThat(decide.path("inputRefs").toString()).isEqualTo("[\"context.amount\"]");
    assertThat(helper.has("inputRefs")).as("非 Verdict 函数不输出 inputRefs").isFalse();

    CoreModel.Module back = MAPPER.readValue(MAPPER.writeValueAsString(core), CoreModel.Module.class);
    CoreModel.Func f = (CoreModel.Func) back.decls.stream()
        .filter(d -> d instanceof CoreModel.Func fn && "decide".equals(fn.name)).findFirst().orElseThrow();
    assertThat(f.inputRefs).containsExactly("context.amount");
  }
}
