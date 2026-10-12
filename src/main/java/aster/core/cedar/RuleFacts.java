package aster.core.cedar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 翻译时收集的规则事实（供 schema 生成与等价性探针使用）。可变收集器，对外只读；
 * 所有列表按首次出现顺序去重，重复记录是幂等的（类型推断与翻译两遍遍历都会写入）。
 */
public final class RuleFacts {

  private final Set<String> actions = new LinkedHashSet<>();
  private final Set<String> roles = new LinkedHashSet<>();
  private final Set<String> principalTypes = new LinkedHashSet<>();
  private final Set<String> resourceTypes = new LinkedHashSet<>();
  private final Set<String> principalIds = new LinkedHashSet<>();
  private final Set<String> resourceIds = new LinkedHashSet<>();
  private final Set<String> agentModels = new LinkedHashSet<>();
  private final Map<String, ContextType> contextTypes = new LinkedHashMap<>();
  private final Map<String, Set<Object>> contextLiterals = new LinkedHashMap<>();
  private final Set<String> readKeys = new LinkedHashSet<>();
  private final Set<String> conflictedKeys = new LinkedHashSet<>();
  private boolean agentModelUsed;

  public List<String> actions() { return view(actions); }
  public List<String> roles() { return view(roles); }
  public List<String> principalTypes() { return view(principalTypes); }
  public List<String> resourceTypes() { return view(resourceTypes); }
  public List<String> principalIds() { return view(principalIds); }
  public List<String> resourceIds() { return view(resourceIds); }
  public List<String> agentModels() { return view(agentModels); }
  public Map<String, ContextType> contextTypes() { return Collections.unmodifiableMap(contextTypes); }
  public boolean agentModelUsed() { return agentModelUsed; }

  public Map<String, List<Object>> contextLiterals() {
    Map<String, List<Object>> out = new LinkedHashMap<>();
    contextLiterals.forEach((k, v) -> out.put(k, List.copyOf(v)));
    return Collections.unmodifiableMap(out);
  }

  /** 按 guard 字段名记录与之比较的文本字面量。 */
  void recordGuardLiteral(String field, String value) {
    switch (field) {
      case "action" -> actions.add(value);
      case "principal_id" -> principalIds.add(value);
      case "resource_id" -> resourceIds.add(value);
      case "principal_type" -> principalTypes.add(value);
      case "resource_type" -> resourceTypes.add(value);
      case "agent_model" -> agentModels.add(value);
      default -> throw new IllegalArgumentException("not a literal-compared guard field: " + field);
    }
    if (field.equals("agent_model")) agentModelUsed = true;
  }

  void recordRole(String role) { roles.add(role); }

  void recordRead(String key) { readKeys.add(key); }

  void recordLiteral(String key, Object literal) {
    contextLiterals.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(literal);
  }

  /** 记录 context 键类型；与已有类型冲突时返回已有类型（每个键只返回一次，避免重复 E722）。 */
  Optional<ContextType> recordType(String key, ContextType type) {
    ContextType existing = contextTypes.putIfAbsent(key, type);
    if (existing == null || existing == type || !conflictedKeys.add(key)) return Optional.empty();
    return Optional.of(existing);
  }

  Optional<ContextType> typeOf(String key) { return Optional.ofNullable(contextTypes.get(key)); }

  /** 被读取但始终未能推断出类型的 context 键。 */
  List<String> untypedKeys() {
    return readKeys.stream().filter(k -> !contextTypes.containsKey(k)).toList();
  }

  private static List<String> view(Set<String> set) { return List.copyOf(set); }
}
