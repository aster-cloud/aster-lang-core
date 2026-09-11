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
  /** 拓扑遍历起点的确定性排序：先按模块名，再按版本号。 */
  private static final Comparator<ModuleKey> MODULE_KEY_ORDER =
    Comparator.comparing(ModuleKey::moduleName).thenComparingInt(ModuleKey::version);

  public List<ModuleKey> topologicalOrder() {
    var byFrom = new HashMap<ModuleKey, List<ModuleKey>>();
    for (var edge : imports) {
      byFrom.computeIfAbsent(edge.fromKey(), ignored -> new ArrayList<>()).add(edge.toKey());
    }

    var order = new ArrayList<ModuleKey>();
    var state = new HashMap<ModuleKey, VisitState>();
    var stack = new ArrayDeque<ModuleKey>();

    // ★遍历起点必须有确定顺序：modules 是 Map.copyOf（不可变 Map），其迭代序按
    //   启动期 SALT 随机化。拓扑排序在**等价解**之间的选择由起点顺序决定，于是
    //   同一组模块每次编译可能得到不同（但都合法）的拓扑序 → 合并后 decls 漂移
    //   → 同一份源码产出字节不同的 IR。先按 ModuleKey 排序再遍历即可消除该自由度。
    //   ★这不改变拓扑正确性：任何起点顺序产出的都是合法拓扑序，这里只是固定选哪一个。
    var startOrder = new ArrayList<>(modules.keySet());
    startOrder.sort(MODULE_KEY_ORDER);
    for (var key : startOrder) {
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
