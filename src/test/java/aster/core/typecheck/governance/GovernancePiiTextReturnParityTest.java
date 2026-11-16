package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两引擎共享夹具 diagnostics/governance_pii_text_return_parity.json（与 aster-lang-ts shared/ 同名文件逐字节相同）：
 * 每例经完整编译通道得到 Core，断言 GovernanceChecker 给出的 W709 序列 {rule, field, line} 与夹具一致，
 * 且每条都是不阻断的 warning（aster-cloud#772 方案 3）。
 */
class GovernancePiiTextReturnParityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode fixture() throws Exception {
    try (InputStream in = GovernancePiiTextReturnParityTest.class
        .getResourceAsStream("/diagnostics/governance_pii_text_return_parity.json")) {
      assertThat(in).as("缺少共享夹具").isNotNull();
      return MAPPER.readTree(in);
    }
  }

  @Test
  void 夹具覆盖正反例与已记录的限制() throws Exception {
    List<String> names = new ArrayList<>();
    fixture().path("cases").forEach(c -> names.add(c.path("name").asText()));
    assertThat(names).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(20)
        .contains("concat-literal-and-pii-field", "non-pii-field-not-reported", "return-type-int-not-reported",
            "cross-function-not-propagated", "set-overwrites-taint", "localized-zh-canonical-path");
  }

  @TestFactory
  Stream<DynamicTest> 每例W709同夹具() throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : fixture().path("cases")) {
      tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> check(c)));
    }
    return tests.stream();
  }

  private static void check(JsonNode c) throws Exception {
    String lexicon = c.hasNonNull("lexicon") ? c.get("lexicon").asText() : null;
    String vocabulary = c.hasNonNull("vocabulary") ? MAPPER.writeValueAsString(c.get("vocabulary")) : null;
    CoreModel.Module core = GovernanceTestSupport.resolveSource(c.path("source").asText(), lexicon, vocabulary);

    List<Map<String, Object>> actual = new ArrayList<>();
    for (Diagnostic d : new GovernanceChecker().checkModule(core)) {
      if (d.code() != ErrorCode.GOV_PII_RETURNED_AS_TEXT) continue;
      assertThat(d.severity()).isEqualTo(Diagnostic.Severity.WARNING);
      actual.add(Map.of("rule", String.valueOf(d.data().get("rule")), "field", String.valueOf(d.data().get("field")),
          "line", d.span().map(o -> o.start.line).orElse(-1)));
    }
    List<Map<String, Object>> expected = new ArrayList<>();
    c.path("expect").forEach(e -> expected.add(Map.of("rule", e.path("rule").asText(), "field", e.path("field").asText(),
        "line", e.path("line").asInt())));
    assertThat(actual).isEqualTo(expected);
  }
}
