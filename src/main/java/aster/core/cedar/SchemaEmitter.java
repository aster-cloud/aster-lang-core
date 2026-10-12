package aster.core.cedar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Cedar schema（JSON 格式）生成：命名空间 Aster，实体 Principal / Resource，
 * 每个 action 共用同一 context 记录，所有 context 键均为可选。输出紧凑 JSON，键按插入顺序。
 */
public final class SchemaEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SchemaEmitter() {}

  /** {@code actions} 为空时以 {@code fallbackAction} 作为唯一 action。 */
  public static String emit(List<String> actions, Map<String, ContextType> contextKeys,
                            boolean agentModelUsed, String fallbackAction) {
    ObjectNode ns = MAPPER.createObjectNode();
    ns.set("entityTypes", entityTypes());
    ObjectNode actionsNode = ns.putObject("actions");
    List<String> names = actions.isEmpty() ? List.of(fallbackAction) : actions;
    for (String name : names) {
      actionsNode.set(name, appliesTo(contextKeys, agentModelUsed));
    }
    ObjectNode root = MAPPER.createObjectNode();
    root.set("Aster", ns);
    return root.toString();
  }

  private static ObjectNode entityTypes() {
    ObjectNode types = MAPPER.createObjectNode();
    ObjectNode principalAttrs = recordShape(types.putObject("Principal"));
    principalAttrs.set("type", prim("String"));
    ObjectNode roles = principalAttrs.putObject("roles");
    roles.put("type", "Set");
    roles.set("element", prim("String"));
    recordShape(types.putObject("Resource")).set("type", prim("String"));
    return types;
  }

  // 在实体下建 shape 记录并返回其 attributes 节点
  private static ObjectNode recordShape(ObjectNode entity) {
    ObjectNode shape = entity.putObject("shape");
    shape.put("type", "Record");
    return shape.putObject("attributes");
  }

  private static ObjectNode appliesTo(Map<String, ContextType> contextKeys, boolean agentModelUsed) {
    ObjectNode action = MAPPER.createObjectNode();
    ObjectNode applies = action.putObject("appliesTo");
    applies.putArray("principalTypes").add("Principal");
    applies.putArray("resourceTypes").add("Resource");
    ObjectNode context = applies.putObject("context");
    context.put("type", "Record");
    ObjectNode attrs = context.putObject("attributes");
    if (agentModelUsed) attrs.set("agent_model", optional("String"));
    contextKeys.forEach((key, type) -> attrs.set(key, optional(cedarType(type))));
    return action;
  }

  private static String cedarType(ContextType type) {
    return switch (type) {
      case LONG -> "Long";
      case STRING -> "String";
      case BOOL -> "Boolean";
    };
  }

  private static ObjectNode prim(String type) {
    return MAPPER.createObjectNode().put("type", type);
  }

  private static ObjectNode optional(String type) {
    return prim(type).put("required", false);
  }
}
