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

  private static ControlRegistry registryWithProfiles(String profilesJson) {
    String json = "{\"version\":\"9\",\"frameworks\":[{\"id\":\"EU_AI_ACT\"}],"
        + "\"controls\":[{\"key\":\"EU_AI_ACT:ART14\",\"framework\":\"EU_AI_ACT\"}],"
        + "\"profiles\":" + profilesJson + "}";
    return ControlRegistry.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static String profileJson(String id, String ruleId, String registered, String frameworks) {
    return "{" + (id == null ? "" : "\"id\":" + id + ",") + "\"requires\":{\"ruleId\":" + ruleId
        + ",\"registeredControls\":" + registered + ",\"frameworks\":" + frameworks + "}}";
  }

  @Test void 合法档案照常登记() {
    var r = registryWithProfiles("[" + profileJson("\"hr\"", "true", "false", "[\"EU_AI_ACT\"]") + "]");
    var hr = r.profile("hr").orElseThrow();
    assertTrue(hr.ruleId());
    assertFalse(hr.registeredControls());
    assertEquals(java.util.List.of("EU_AI_ACT"), hr.frameworks());
  }

  @Test void 畸形档案不登记且不影响其它档案() {
    String good = profileJson("\"ok\"", "true", "true", "[]");
    java.util.List<String> bad = java.util.List.of(
        profileJson(null, "true", "true", "[]"),
        profileJson("42", "true", "true", "[]"),
        profileJson("\"Bad_Id\"", "true", "true", "[]"),
        profileJson("\"x" + "a".repeat(64) + "\"", "true", "true", "[]"),
        profileJson("\"bad\"", "\"yes\"", "true", "[]"),
        profileJson("\"bad\"", "true", "1", "[]"),
        profileJson("\"bad\"", "true", "true", "\"EU_AI_ACT\""),
        profileJson("\"bad\"", "true", "true", "[\"NOPE\"]"),
        profileJson("\"bad\"", "true", "true", "[7]"),
        "{\"id\":\"bad\"}");
    for (String b : bad) {
      var r = registryWithProfiles("[" + good + "," + b + "]");
      assertEquals(java.util.Set.of("ok"), r.profiles().keySet(), b);
    }
  }

  @Test void 重复档案id全部不登记() {
    var r = registryWithProfiles("[" + profileJson("\"dup\"", "true", "true", "[]") + ","
        + profileJson("\"dup\"", "false", "false", "[]") + "," + profileJson("\"ok\"", "true", "true", "[]") + "]");
    assertTrue(r.profile("dup").isEmpty());
    assertTrue(r.profile("ok").isPresent());
  }

  @Test void profiles不是数组抛出() {
    assertThrows(IllegalStateException.class, () -> registryWithProfiles("{}"));
  }

  // 辅助：给定根 frameworks 原文，返回引用 framework 的档案是否登记
  private static boolean profileRegistered(String frameworksJson, String frameworkJson) {
    String json = "{\"version\":\"9\",\"frameworks\":" + frameworksJson + ",\"controls\":[],"
        + "\"profiles\":[" + profileJson("\"p\"", "true", "true", "[" + frameworkJson + "]") + "]}";
    var r = ControlRegistry.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    return r.profile("p").isPresent();
  }

  @Test void 根frameworks不是数组时已登记框架为空() {
    for (String root : java.util.List.of("{\"a\":{\"id\":\"EU_AI_ACT\"}}", "\"EU_AI_ACT\"", "null", "7")) {
      assertFalse(profileRegistered(root, "\"EU_AI_ACT\""), root);
    }
  }

  @Test void 根frameworks只计对象条目的字符串id() {
    String root = "[null, 7, \"EU_AI_ACT\", [\"GDPR\"], {\"id\":3}, {\"id\":null}, {\"id\":true}, {\"x\":1}]";
    for (String ref : java.util.List.of("\"EU_AI_ACT\"", "\"GDPR\"", "\"3\"", "\"null\"", "\"true\"", "\"\"")) {
      assertFalse(profileRegistered(root, ref), ref);
    }
    assertTrue(profileRegistered("[null, {\"id\":3}, {\"id\":\"SOX\"}]", "\"SOX\""));
  }

  @Test void 根frameworks中只含ASCII空白的id不计入() {
    for (String id : java.util.List.of("", " ", "\\t\\n\\u000b\\f\\r ")) {
      String quoted = "\"" + id + "\"";
      assertFalse(profileRegistered("[{\"id\":" + quoted + "}]", quoted), quoted);
    }
    // NBSP 不是 ASCII 空白，照常计入且不 trim
    assertTrue(profileRegistered("[{\"id\":\"\\u00a0\"}]", "\"\\u00a0\""));
    assertTrue(profileRegistered("[{\"id\":\" EU \"}]", "\" EU \""));
    assertFalse(profileRegistered("[{\"id\":\" EU \"}]", "\"EU\""));
  }
}
