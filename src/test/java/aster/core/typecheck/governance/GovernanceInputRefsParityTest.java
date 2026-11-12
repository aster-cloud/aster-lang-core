package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两引擎共享夹具 diagnostics/governance_input_refs_parity.json（与 aster-lang-ts shared/ 同名文件逐字节相同）：
 * 每例经完整编译通道得到 IR，逐函数断言 inputRefs；档案要求 verifiedInputs 时 E708 的 {rule, profile, construct}
 * 与夹具中 ["*"] 规则的 construct 一一对应（ADR 0046 §11 D2 / §11.6）。
 */
class GovernanceInputRefsParityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PROFILE = "eu-ai-act-high-risk";

  /** 该档案只要求 verifiedInputs，其余要求关闭，使断言只看 E708。 */
  private static final ControlRegistry REQUIRING = new ControlRegistry("test", Set.of("EU_AI_ACT:ART14"),
      Map.of("EU_AI_ACT:ART14", "EU_AI_ACT"),
      Map.of(PROFILE, new ControlRegistry.Profile(PROFILE, false, false, List.of(), true)));

  private static JsonNode fixture() throws Exception {
    try (InputStream in = GovernanceInputRefsParityTest.class
        .getResourceAsStream("/diagnostics/governance_input_refs_parity.json")) {
      assertThat(in).as("缺少共享夹具").isNotNull();
      return MAPPER.readTree(in);
    }
  }

  @Test
  void 夹具覆盖ADR表与计划指定用例() throws Exception {
    List<String> names = new ArrayList<>();
    fixture().path("cases").forEach(c -> names.add(c.path("name").asText()));
    assertThat(names).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(30)
        .contains("adr-13-inline-depth-8", "adr-14-inline-depth-9", "adr-15-mutual-recursion",
            "alias-same-name-as-local-function", "two-stars-callee-body-first",
            "non-verdict-functions-get-no-input-refs", "localized-zh-canonical-paths", "localized-de-canonical-paths");
  }

  @TestFactory
  Stream<DynamicTest> 每例inputRefs与E708同夹具() throws Exception {
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

    Map<String, JsonNode> actual = new LinkedHashMap<>();
    Map<String, String> expectedE708 = new LinkedHashMap<>();
    for (CoreModel.Decl d : core.decls) {
      if (!(d instanceof CoreModel.Func f)) continue;
      actual.put(f.name, f.inputRefs == null ? NullNode.getInstance() : MAPPER.valueToTree(f.inputRefs));
      JsonNode want = c.path("expect").path(f.name);
      if (want.hasNonNull("construct")) {
        expectedE708.put(f.name, want.get("construct").asText());
        assertThat(InputRefsAnalyzer.analyze(core, f).construct()).as(f.name + " construct").isEqualTo(want.get("construct").asText());
      }
    }
    Map<String, JsonNode> expected = new LinkedHashMap<>();
    c.path("expect").fields().forEachRemaining(e -> expected.put(e.getKey(), e.getValue().path("inputRefs")));
    assertThat(actual).as("inputRefs").isEqualTo(expected);

    Map<String, String> e708 = new LinkedHashMap<>();
    for (Diagnostic diag : new GovernanceChecker(REQUIRING).checkModule(core)) {
      if (diag.code() != ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE) continue;
      assertThat(diag.data()).containsEntry("profile", PROFILE);
      e708.put(String.valueOf(diag.data().get("rule")), String.valueOf(diag.data().get("construct")));
    }
    assertThat(e708).as("E708 {rule → construct}").isEqualTo(expectedE708);
  }
}
