package aster.core.capability;

import aster.core.ir.CoreModel;
import aster.core.ir.visitor.DefaultCoreVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Capability 推断工具（中立模块）。
 * <p>
 * 该类不依赖任何具体编译阶段（typecheck / lowering），仅基于 Core IR 推断
 * 调用名称对应的资源能力，供 lowering（phase 4）与 typecheck（phase 3）共同复用，
 * 避免 lowering -&gt; typecheck 的层级耦合。
 */
public final class CapabilityInference {

  private static final Map<CapabilityKind, List<String>> CAPABILITY_PREFIXES = Map.ofEntries(
    Map.entry(CapabilityKind.HTTP, List.of("Http.")),
    Map.entry(CapabilityKind.SQL, List.of("Db.", "Sql.")),
    Map.entry(CapabilityKind.TIME, List.of("Time.", "Clock.")),
    Map.entry(CapabilityKind.FILES, List.of("Files.", "Fs.")),
    Map.entry(CapabilityKind.SECRETS, List.of("Secrets.")),
    Map.entry(CapabilityKind.AI_MODEL, List.of("Ai.")),
    Map.entry(CapabilityKind.PAYMENT, List.of("Payment.")),
    Map.entry(CapabilityKind.INVENTORY, List.of("Inventory."))
  );

  private CapabilityInference() {
  }

  /**
   * 推断调用名称对应的 capability。
   */
  public static Optional<CapabilityKind> inferCapabilityFromName(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    for (var entry : CAPABILITY_PREFIXES.entrySet()) {
      for (var prefix : entry.getValue()) {
        if (name.startsWith(prefix)) {
          return Optional.of(entry.getKey());
        }
      }
    }
    return Optional.empty();
  }

  /**
   * 收集代码块中的 capability 使用情况。
   *
   * @return capability -&gt; 调用列表
   */
  public static Map<CapabilityKind, List<String>> collectCapabilities(CoreModel.Block body) {
    if (body == null) {
      return Collections.emptyMap();
    }
    var capabilities = new EnumMap<CapabilityKind, List<String>>(CapabilityKind.class);
    new CapabilityCollector(capabilities).visitBlock(body, null);
    return capabilities;
  }

  private static final class CapabilityCollector extends DefaultCoreVisitor<Void> {

    private final Map<CapabilityKind, List<String>> capabilities;

    CapabilityCollector(Map<CapabilityKind, List<String>> capabilities) {
      this.capabilities = capabilities;
    }

    @Override
    public Void visitExpression(CoreModel.Expr expr, Void ctx) {
      if (expr instanceof CoreModel.Call call && call.target instanceof CoreModel.Name name) {
        inferCapabilityFromName(name.name).ifPresent(cap -> {
          var entries = capabilities.computeIfAbsent(cap, ignored -> new ArrayList<>());
          entries.add(name.name);
        });
      }
      return super.visitExpression(expr, ctx);
    }
  }
}
