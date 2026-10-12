package aster.core.cedar;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Cedar 策略文本生成（ADR 0047 §4.2）：注解顺序固定，无值的注解省略；策略之间空一行，文本以单个换行结尾。 */
public final class PolicyEmitter {

  private PolicyEmitter() {}

  /** 生成全部策略文本；{@code controls} 为空、{@code profile} 为 null 时省略对应注解。 */
  public static String emit(List<EmittedPolicy> policies, String ruleId, String module, String sourceHash,
                            List<String> controls, String profile) {
    String controlsValue = controls.isEmpty() ? null : String.join(",", controls);
    return policies.stream()
        .map(p -> policy(p, ruleId, module, sourceHash, controlsValue, profile))
        .collect(Collectors.joining("\n"));
  }

  private static String policy(EmittedPolicy p, String ruleId, String module, String sourceHash,
                               String controls, String profile) {
    List<String> lines = new ArrayList<>();
    annotate(lines, "id", p.id());
    annotate(lines, "aster_rule", ruleId);
    annotate(lines, "aster_module", module);
    annotate(lines, "aster_source_hash", sourceHash);
    annotate(lines, "aster_outcome", p.outcome());
    annotate(lines, "aster_approver", p.approver());
    annotate(lines, "aster_reason", p.reason());
    annotate(lines, "aster_controls", controls);
    annotate(lines, "aster_profile", profile);
    lines.add(p.effect() + " (principal, action, resource)");
    lines.add("when { " + p.condition() + " };");
    return String.join("\n", lines) + "\n";
  }

  private static void annotate(List<String> lines, String name, String value) {
    if (value != null) lines.add("@" + name + "(" + CedarText.quote(value) + ")");
  }
}
