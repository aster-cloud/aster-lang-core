package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.typecheck.CapabilityKind;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.capability.ManifestConfig;
import aster.core.typecheck.model.Diagnostic;
import aster.core.typecheck.visitor.DefaultCoreVisitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Capability 类型检查器。
 *
 * 负责：
 * - 基于函数体推断实际使用到的 capability，并与 @io/@cpu effect 声明核对
 * - 校验显式声明的 effectCaps 是否覆盖所有实际使用的能力，并提示多余声明
 */
public final class CapabilityChecker {

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

  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private ManifestConfig manifest;

  public void setManifest(ManifestConfig manifest) {
    this.manifest = manifest;
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
   * @return capability -> 调用列表
   */
  public Map<CapabilityKind, List<String>> collectCapabilities(CoreModel.Block body) {
    if (body == null) {
      return Collections.emptyMap();
    }
    var capabilities = new EnumMap<CapabilityKind, List<String>>(CapabilityKind.class);
    new CapabilityCollector(capabilities).visitBlock(body, null);
    return capabilities;
  }

  /**
   * 检查函数列表，返回能力推断产生的诊断。
   */
  public List<Diagnostic> checkModule(List<CoreModel.Func> funcs) {
    diagnostics.clear();
    if (funcs == null || funcs.isEmpty()) {
      return List.of();
    }
    for (var func : funcs) {
      checkFunction(func);
    }
    return List.copyOf(diagnostics);
  }

  private void checkFunction(CoreModel.Func func) {
    if (func == null || func.body == null) {
      return;
    }
    var declaredEffects = normalizeEffects(func.effects);
    var capabilities = collectCapabilities(func.body);
    checkDeclaredCapabilities(func, capabilities);
    if (!capabilities.isEmpty()) {
      var ioCaps = new LinkedHashSet<CapabilityKind>();
      for (var cap : capabilities.keySet()) {
        if (cap != CapabilityKind.CPU) {
          ioCaps.add(cap);
        }
      }
      if (!ioCaps.isEmpty() && !declaredEffects.contains("io")) {
        var capNames = joinCapNames(ioCaps);
        var sampleCalls = summarizeCalls(capabilities, ioCaps);
        emit(
          ErrorCode.CAPABILITY_INFER_MISSING_IO,
          func.origin,
          Map.of(
            "func", func.name,
            "capabilities", capNames,
            "calls", sampleCalls
          ),
          func.name,
          capNames,
          sampleCalls
        );
      }

      if (capabilities.containsKey(CapabilityKind.CPU) && !(declaredEffects.contains("cpu") || declaredEffects.contains("io"))) {
        var sampleCalls = summarizeCalls(capabilities, List.of(CapabilityKind.CPU));
        emit(
          ErrorCode.CAPABILITY_INFER_MISSING_CPU,
          func.origin,
          Map.of(
            "func", func.name,
            "calls", sampleCalls
          ),
          func.name,
          sampleCalls
        );
      }
    }

    checkWorkflowConstraints(func, declaredEffects);
    checkManifestConstraints(func);
  }

  private void checkDeclaredCapabilities(CoreModel.Func func, Map<CapabilityKind, List<String>> usedCaps) {
    if (func == null || !func.effectCapsExplicit) {
      return;
    }
    var declared = normalizeEffectCaps(func.effectCaps);
    if (declared.isEmpty()) {
      return;
    }
    var declaredJoined = String.join(", ", declared);
    for (var used : usedCaps.keySet()) {
      var name = used.displayName();
      if (!declared.contains(name)) {
        emit(
          ErrorCode.EFF_CAP_MISSING,
          func.origin,
          Map.of(
            "func", func.name,
            "cap", name,
            "declared", declaredJoined
          ),
          func.name,
          name,
          declaredJoined
        );
      }
    }
    for (var cap : declared) {
      var kind = CapabilityKind.fromLabel(cap);
      if (kind.isEmpty() || !usedCaps.containsKey(kind.get())) {
        emit(
          ErrorCode.EFF_CAP_SUPERFLUOUS,
          func.origin,
          Map.of(
            "func", func.name,
            "cap", cap
          ),
          func.name,
          cap
        );
      }
    }
  }

  private void checkWorkflowConstraints(CoreModel.Func func, Set<String> declaredEffects) {
    if (func == null || func.body == null) {
      return;
    }
    var workflows = collectWorkflows(func.body);
    if (workflows.isEmpty()) {
      return;
    }
    if (!declaredEffects.contains("io")) {
      emit(
        ErrorCode.WORKFLOW_MISSING_IO_EFFECT,
        func.origin,
        Map.of("func", func.name),
        func.name
      );
    }
    var declaredCapabilityKinds = toCapabilityKinds(normalizeEffectCaps(func.effectCaps));
    for (var workflow : workflows) {
      checkWorkflow(func, workflow, declaredCapabilityKinds);
    }
  }

  private List<CoreModel.Workflow> collectWorkflows(CoreModel.Block body) {
    if (body == null) {
      return List.of();
    }
    var workflows = new ArrayList<CoreModel.Workflow>();
    new DefaultCoreVisitor<Void>() {
      @Override
      public Void visitStatement(CoreModel.Stmt stmt, Void ctx) {
        if (stmt instanceof CoreModel.Workflow workflow) {
          workflows.add(workflow);
        }
        return super.visitStatement(stmt, ctx);
      }
    }.visitBlock(body, null);
    return workflows;
  }

  private LinkedHashSet<CapabilityKind> toCapabilityKinds(Set<String> labels) {
    var kinds = new LinkedHashSet<CapabilityKind>();
    if (labels == null || labels.isEmpty()) {
      return kinds;
    }
    for (var label : labels) {
      CapabilityKind.fromLabel(label).ifPresent(kinds::add);
    }
    return kinds;
  }

  private void checkWorkflow(
      CoreModel.Func func,
      CoreModel.Workflow workflow,
      Set<CapabilityKind> declaredCaps
  ) {
    if (workflow.steps == null) {
      return;
    }
    for (var step : workflow.steps) {
      var bodyCaps = collectCapabilities(step.body);
      CoreModel.Origin bodyOrigin = step.body != null ? step.body.origin : step.origin;
      reportWorkflowCapabilityViolation(func, step, declaredCaps, bodyCaps, bodyOrigin);
      if (step.compensate != null) {
        var compensateCaps = collectCapabilities(step.compensate);
        CoreModel.Origin compensateOrigin = step.compensate.origin != null ? step.compensate.origin : step.origin;
        reportWorkflowCapabilityViolation(func, step, declaredCaps, compensateCaps, compensateOrigin);
        reportCompensateParity(func, step, bodyCaps, compensateCaps);
      }
    }
  }

  private void reportWorkflowCapabilityViolation(
      CoreModel.Func func,
      CoreModel.Step step,
      Set<CapabilityKind> declaredCaps,
      Map<CapabilityKind, List<String>> observed,
      CoreModel.Origin origin
  ) {
    if (observed == null || observed.isEmpty()) {
      return;
    }
    for (var cap : observed.keySet()) {
      if (!declaredCaps.contains(cap)) {
        emit(
          ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY,
          origin,
          Map.of(
            "func", func.name,
            "step", step.name,
            "capability", cap.displayName()
          ),
          func.name,
          step.name,
          cap.displayName()
        );
      }
    }
  }

  private void reportCompensateParity(
      CoreModel.Func func,
      CoreModel.Step step,
      Map<CapabilityKind, List<String>> bodyCaps,
      Map<CapabilityKind, List<String>> compensateCaps
  ) {
    if (compensateCaps == null || compensateCaps.isEmpty()) {
      return;
    }
    var bodyKinds = bodyCaps == null ? Set.<CapabilityKind>of() : bodyCaps.keySet();
    for (var cap : compensateCaps.keySet()) {
      if (!bodyKinds.contains(cap)) {
        emit(
          ErrorCode.COMPENSATE_NEW_CAPABILITY,
          step.compensate != null ? step.compensate.origin : step.origin,
          Map.of(
            "func", func.name,
            "step", step.name,
            "capability", cap.displayName()
          ),
          step.name,
          func.name,
          cap.displayName()
        );
      }
    }
  }

  private Set<String> normalizeEffects(List<String> effects) {
    if (effects == null || effects.isEmpty()) {
      return Set.of();
    }
    var normalized = new LinkedHashSet<String>();
    for (var effect : effects) {
      if (effect == null) continue;
      var lower = effect.trim().toLowerCase(Locale.ROOT);
      if (!lower.isEmpty()) {
        normalized.add(lower);
      }
    }
    return normalized;
  }

  private String joinCapNames(Collection<CapabilityKind> caps) {
    return caps.stream()
      .map(CapabilityKind::displayName)
      .distinct()
      .reduce((a, b) -> a + ", " + b)
      .orElse("");
  }

  private String summarizeCalls(Map<CapabilityKind, List<String>> allCaps, Collection<CapabilityKind> targets) {
    var collected = new ArrayList<String>();
    for (var target : targets) {
      var calls = allCaps.getOrDefault(target, List.of());
      for (var call : calls) {
        collected.add(call);
        if (collected.size() == 3) {
          return String.join(", ", collected);
        }
      }
    }
    return collected.isEmpty() ? "-" : String.join(", ", collected);
  }

  private LinkedHashSet<String> normalizeEffectCaps(List<String> effectCaps) {
    var normalized = new LinkedHashSet<String>();
    if (effectCaps == null) {
      return normalized;
    }
    for (var cap : effectCaps) {
      if (cap == null) {
        continue;
      }
      var trimmed = cap.trim();
      if (!trimmed.isEmpty()) {
        normalized.add(trimmed);
      }
    }
    return normalized;
  }

  private void emit(ErrorCode code, CoreModel.Origin origin, Map<String, Object> data, Object... args) {
    var severity = switch (code.severity()) {
      case ERROR -> Diagnostic.Severity.ERROR;
      case WARNING -> Diagnostic.Severity.WARNING;
      case INFO -> Diagnostic.Severity.INFO;
    };
    var message = args == null || args.length == 0
      ? code.messageTemplate()
      : code.format(args);
    diagnostics.add(new Diagnostic(
      severity,
      code,
      message,
      Optional.ofNullable(origin),
      Optional.ofNullable(code.help()),
      data == null ? Map.of() : Map.copyOf(data)
    ));
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

  private void checkManifestConstraints(CoreModel.Func func) {
    if (manifest == null || func == null || !func.effectCapsExplicit) {
      return;
    }
    var declaredCaps = normalizeEffectCaps(func.effectCaps);
    if (declaredCaps.isEmpty()) {
      return;
    }
    for (var capLabel : declaredCaps) {
      var kind = CapabilityKind.fromLabel(capLabel);
      if (kind.isEmpty()) {
        continue;
      }
      var cap = kind.get();
      if (!manifest.isAllowed(cap)) {
        emit(
          ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY,
          func.origin,
          Map.of(
            "func", func.name,
            "capability", cap.displayName(),
            "manifest", "not allowed"
          ),
          func.name,
          "manifest",
          cap.displayName()
        );
      }
    }
  }
}
