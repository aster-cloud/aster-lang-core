package aster.core.module;

import aster.core.ir.CoreModel;
import java.util.*;

/**
 * aster-api 解析完成后传给 core linker 的纯数据模块图。
 *
 * @param root    入口模块 key
 * @param modules 已解析模块，必须包含 root
 * @param imports import 边：from 模块通过 alias 指向 to 模块
 */
public record ModuleGraph(
  ModuleKey root,
  Map<ModuleKey, CoreModel.Module> modules,
  List<ImportEdge> imports
) {

  public ModuleGraph {
    Objects.requireNonNull(root, "root");
    modules = Map.copyOf(Objects.requireNonNull(modules, "modules"));
    imports = List.copyOf(imports == null ? List.of() : imports);
    if (!modules.containsKey(root)) {
      throw new IllegalArgumentException("modules must contain root: " + root);
    }
    for (var edge : imports) {
      if (!modules.containsKey(edge.fromKey())) {
        throw new IllegalArgumentException("import edge fromKey is not in modules: " + edge.fromKey());
      }
      if (!modules.containsKey(edge.toKey())) {
        throw new IllegalArgumentException("import edge toKey is not in modules: " + edge.toKey());
      }
    }
  }

  /**
   * import 边。importAlias 是源码中的 alias；无 alias 时由 resolver 填入默认可见名。
   */
  public record ImportEdge(ModuleKey fromKey, String importAlias, ModuleKey toKey) {
    public ImportEdge {
      Objects.requireNonNull(fromKey, "fromKey");
      Objects.requireNonNull(toKey, "toKey");
      if (importAlias == null || importAlias.isBlank()) {
        throw new IllegalArgumentException("importAlias must not be blank");
      }
    }
  }

  /**
   * 返回依赖优先的拓扑序；检测到环时抛 LinkException。
   */
  public List<ModuleKey> topologicalOrder() {
    var byFrom = new HashMap<ModuleKey, List<ModuleKey>>();
    for (var edge : imports) {
      byFrom.computeIfAbsent(edge.fromKey(), ignored -> new ArrayList<>()).add(edge.toKey());
    }

    var order = new ArrayList<ModuleKey>();
    var state = new HashMap<ModuleKey, VisitState>();
    var stack = new ArrayDeque<ModuleKey>();

    for (var key : modules.keySet()) {
      visit(key, byFrom, state, stack, order);
    }
    return List.copyOf(order);
  }

  public List<ImportEdge> importsFrom(ModuleKey key) {
    return imports.stream()
      .filter(edge -> edge.fromKey().equals(key))
      .toList();
  }

  private void visit(
    ModuleKey key,
    Map<ModuleKey, List<ModuleKey>> byFrom,
    Map<ModuleKey, VisitState> state,
    Deque<ModuleKey> stack,
    List<ModuleKey> order
  ) {
    var current = state.get(key);
    if (current == VisitState.DONE) {
      return;
    }
    if (current == VisitState.VISITING) {
      var cycle = new ArrayList<String>();
      for (var item : stack) {
        cycle.add(item.moduleName() + "@" + item.version());
        if (item.equals(key)) {
          break;
        }
      }
      Collections.reverse(cycle);
      cycle.add(key.moduleName() + "@" + key.version());
      throw new LinkException("Module import cycle detected: " + String.join(" -> ", cycle));
    }

    state.put(key, VisitState.VISITING);
    stack.push(key);
    for (var dep : byFrom.getOrDefault(key, List.of())) {
      visit(dep, byFrom, state, stack, order);
    }
    stack.pop();
    state.put(key, VisitState.DONE);
    order.add(key);
  }

  private enum VisitState {
    VISITING,
    DONE
  }
}
