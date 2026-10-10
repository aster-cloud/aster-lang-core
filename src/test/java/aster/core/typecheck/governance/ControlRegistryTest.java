package aster.core.typecheck.governance;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 控制注册表（ADR 0045 §2）：默认副本可加载，键形态与登记判定。 */
class ControlRegistryTest {
  @Test void 默认注册表含五个初始控制点() {
    var r = ControlRegistry.loadDefault();
    assertEquals("1.1.0", r.version());
    assertTrue(r.has("EU_AI_ACT:ART14"));
    assertTrue(r.has("SOX:404"));
    assertEquals(5, r.keys().size());
  }

  @Test void 形态非法即使在集合中也不算登记() {
    assertFalse(ControlRegistry.isWellFormed("eu_ai_act:art14"));
    assertFalse(ControlRegistry.isWellFormed("EU_AI_ACT ART14"));
    assertFalse(ControlRegistry.isWellFormed("A:" + "B".repeat(63)));
    assertTrue(ControlRegistry.isWellFormed("EU_AI_ACT:ART14"));
  }

  @Test void 不合法的json抛出() {
    var in = new ByteArrayInputStream("{\"controls\":[]}".getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalStateException.class, () -> ControlRegistry.fromJson(in));
  }

  @Test void 默认注册表含档案与框架归属() {
    var r = ControlRegistry.loadDefault();
    var governed = r.profile("governed").orElseThrow();
    assertTrue(governed.ruleId());
    assertTrue(governed.registeredControls());
    assertTrue(governed.frameworks().isEmpty());
    assertEquals(java.util.List.of("EU_AI_ACT"), r.profile("eu-ai-act-high-risk").orElseThrow().frameworks());
    assertTrue(r.profile("nope").isEmpty());
    assertEquals("EU_AI_ACT", r.frameworkOf().get("EU_AI_ACT:ART14"));
  }

  @Test void 缺profiles的输入视为空档案() {
    var in = new ByteArrayInputStream(
        "{\"version\":\"9\",\"controls\":[{\"key\":\"A:B\",\"framework\":\"A\"}]}".getBytes(StandardCharsets.UTF_8));
    var r = ControlRegistry.fromJson(in);
    assertTrue(r.profile("governed").isEmpty());
    assertTrue(r.has("A:B"));
  }

  @Test void 两参构造器仍可用() {
    var r = new ControlRegistry("x", java.util.Set.of("A:B"));
    assertTrue(r.has("A:B"));
    assertTrue(r.profile("governed").isEmpty());
    assertTrue(r.frameworkOf().isEmpty());
  }
}
