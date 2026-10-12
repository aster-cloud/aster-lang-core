package aster.core.cedar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条等价性探针请求（ADR 0047 §5）：同一请求分别交给 Aster 引擎与 Cedar 授权器，要求二者结论一致。
 * {@code context} 中缺失的键即「不存在该键」；{@code agentModel} 为 null 表示缺失。
 * {@code outOfDomain} 为 true 表示某个 context 数值超出 ±(2^53−1)：此类探针只要求 Cedar 不比 Aster 更松。
 */
public record Probe(String principalId, String principalType, List<String> roles, String action,
                    String resourceType, String resourceId, Map<String, Object> context, String agentModel,
                    boolean outOfDomain) {

  static final long SAFE_MAX = 9007199254740991L;

  public Probe {
    roles = List.copyOf(roles);
    context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
  }

  /** 由 context 取值自动判定是否越出数值域。 */
  public Probe(String principalId, String principalType, List<String> roles, String action,
               String resourceType, String resourceId, Map<String, Object> context, String agentModel) {
    this(principalId, principalType, roles, action, resourceType, resourceId, context, agentModel,
        anyOutsideDomain(context));
  }

  /** 与 GuardActionMapper.flatten 同键序的规则入参；不含 outOfDomain。 */
  public Map<String, Object> toGuardArgs() {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("principal_id", principalId);
    args.put("principal_type", principalType);
    args.put("principal_roles", roles);
    args.put("action", action);
    args.put("resource_type", resourceType);
    args.put("resource_id", resourceId);
    args.put("context", context);
    args.put("agent_model", agentModel);
    return args;
  }

  private static boolean anyOutsideDomain(Map<String, Object> context) {
    return context.values().stream().anyMatch(v -> v instanceof Long n && (n > SAFE_MAX || n < -SAFE_MAX));
  }
}
