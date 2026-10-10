package aster.core.typecheck.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 控制注册表（ADR 0045 §2）：@control 的受控词表。
 *
 * <p>只关心「版本 + 已登记键 + 键所属框架 + 档案（ADR 0046 §3）」，条款与证据来源由证据导出侧消费。
 * 默认副本随 core 打包在 classpath（与 aster-lang-locales 真相源深度相等，见 ControlRegistrySiblingParityTest）。
 */
public record ControlRegistry(String version, Set<String> keys, Map<String, String> frameworkOf,
                              Map<String, Profile> profiles) {
  private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z0-9_]+:[A-Z0-9_().]+$");
  private static final Pattern PROFILE_ID = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
  private static final int MAX_KEY_LENGTH = 64;
  private static final String RESOURCE = "/governance/controls-registry.json";

  public ControlRegistry {
    keys = Set.copyOf(keys);
    frameworkOf = Map.copyOf(frameworkOf);
    profiles = Map.copyOf(profiles);
  }

  /** 只含控制点的注册表：无框架归属、无档案。 */
  public ControlRegistry(String version, Set<String> keys) {
    this(version, keys, Map.of(), Map.of());
  }

  /** 档案定义（ADR 0046 §3）：规则须有 @id、控制点须已登记、须至少引用所列框架之一的已登记控制点。 */
  public record Profile(String id, boolean ruleId, boolean registeredControls, List<String> frameworks) {
    public Profile {
      frameworks = List.copyOf(frameworks);
    }
  }

  public Optional<Profile> profile(String id) {
    return Optional.ofNullable(profiles.get(id));
  }

  /** 与审计写入的 64 字符截断上限一致：超长或形态不符的键不可能被登记。 */
  public static boolean isWellFormed(String key) {
    return key != null && key.length() <= MAX_KEY_LENGTH && KEY_PATTERN.matcher(key).matches();
  }

  public boolean has(String key) {
    return isWellFormed(key) && keys.contains(key);
  }

  /** classpath 默认注册表；缺失或不合法直接抛错（词表是治理检查的输入，不能静默为空）。 */
  public static ControlRegistry loadDefault() {
    return Holder.DEFAULT;
  }

  public static ControlRegistry fromJson(InputStream in) {
    try {
      JsonNode root = new ObjectMapper().readTree(in);
      String version = root.path("version").asText("");
      JsonNode controls = root.path("controls");
      if (version.isBlank() || !controls.isArray()) {
        throw new IllegalStateException("控制注册表缺 version 或 controls");
      }
      Set<String> keys = new LinkedHashSet<>();
      Map<String, String> frameworkOf = new LinkedHashMap<>();
      for (JsonNode c : controls) {
        String key = c.path("key").asText();
        keys.add(key);
        if (c.hasNonNull("framework")) frameworkOf.put(key, c.get("framework").asText());
      }
      Set<String> frameworkIds = new HashSet<>();
      root.path("frameworks").forEach(f -> frameworkIds.add(f.path("id").asText("")));
      return new ControlRegistry(version, keys, frameworkOf, readProfiles(root.path("profiles"), frameworkIds));
    } catch (IOException e) {
      throw new UncheckedIOException("控制注册表解析失败", e);
    }
  }

  /**
   * 缺 profiles 视为无档案，以兼容只含控制点的输入；profiles 存在但不是数组与 controls 同属结构错误，抛出。
   * 单条档案按 locales 校验口径判定，不合法即不登记（与形态非法的控制键不算登记同理），声明它的模块因此得到 E705；
   * 重复 id 的各条一律不登记，避免静默择一。
   */
  private static Map<String, Profile> readProfiles(JsonNode profiles, Set<String> frameworkIds) {
    Map<String, Profile> out = new LinkedHashMap<>();
    if (profiles.isMissingNode() || profiles.isNull()) return out;
    if (!profiles.isArray()) throw new IllegalStateException("控制注册表 profiles 须为数组");
    Set<String> seen = new HashSet<>();
    Set<String> duplicated = new HashSet<>();
    for (JsonNode p : profiles) {
      String id = p.path("id").asText("");
      if (!seen.add(id)) duplicated.add(id);
      readProfile(p, frameworkIds).ifPresent(profile -> out.put(profile.id(), profile));
    }
    out.keySet().removeAll(duplicated);
    return out;
  }

  /** id 合乎形态、两个开关为布尔、frameworks 为只含已登记框架 id 的数组，否则为空。 */
  private static Optional<Profile> readProfile(JsonNode p, Set<String> frameworkIds) {
    JsonNode id = p.path("id");
    JsonNode req = p.path("requires");
    JsonNode ruleId = req.path("ruleId");
    JsonNode registered = req.path("registeredControls");
    JsonNode fws = req.path("frameworks");
    boolean shapeOk = id.isTextual() && PROFILE_ID.matcher(id.asText()).matches()
        && ruleId.isBoolean() && registered.isBoolean() && fws.isArray();
    if (!shapeOk) return Optional.empty();
    List<String> frameworks = new ArrayList<>();
    for (JsonNode f : fws) {
      if (!f.isTextual() || !frameworkIds.contains(f.asText())) return Optional.empty();
      frameworks.add(f.asText());
    }
    return Optional.of(new Profile(id.asText(), ruleId.asBoolean(), registered.asBoolean(), frameworks));
  }

  private static final class Holder {
    static final ControlRegistry DEFAULT = load();

    private static ControlRegistry load() {
      try (InputStream in = ControlRegistry.class.getResourceAsStream(RESOURCE)) {
        if (in == null) throw new IllegalStateException("classpath 缺少 " + RESOURCE);
        return fromJson(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
