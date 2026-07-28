package aster.core.typecheck.checkers;

import aster.core.capability.CapabilityInference;
import aster.core.capability.CapabilityKind;
import aster.core.ir.CoreModel;
import aster.core.ir.visitor.DefaultCoreVisitor;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.capability.ManifestConfig;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.Collection;
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

  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private ManifestConfig manifest;
  /** 当前模块名，仅用于 CAPABILITY_NOT_ALLOWED 的消息文案（与 TS 的 {module} 参数对齐）。 */
  private String moduleName;

  public void setManifest(ManifestConfig manifest) {
    this.manifest = manifest;
  }

  /** 设置当前模块名（可选；未设置时消息中的模块字段为空串）。 */
  public void setModuleName(String moduleName) {
    this.moduleName = moduleName;
  }

  /**
   * 推断调用名称对应的 capability。
   *
   * <p>实际逻辑已下沉至中立模块 {@link CapabilityInference}，此处保留委托方法以维持现有调用点。
   */
  public static Optional<CapabilityKind> inferCapabilityFromName(String name) {
    return CapabilityInference.inferCapabilityFromName(name);
  }

  /**
   * 收集代码块中的 capability 使用情况。
   *
   * <p>实际逻辑已下沉至中立模块 {@link CapabilityInference}，此处保留委托方法以维持现有调用点。
   *
   * @return capability -> 调用列表
   */
  public Map<CapabilityKind, List<String>> collectCapabilities(CoreModel.Block body) {
    return CapabilityInference.collectCapabilities(body);
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
      // capability 的 effect 分类走 CapabilityKind.effectClass 单源（shared/capabilities.json）：
      // io-class 需 @io；cpu-class（CPU/CRYPTO，本地计算密集）需 @cpu 或 @io。与 TS 对齐。
      var ioCaps = new LinkedHashSet<CapabilityKind>();
      var cpuCaps = new LinkedHashSet<CapabilityKind>();
      for (var cap : capabilities.keySet()) {
        if (cap.isCpuClass()) {
          cpuCaps.add(cap);
        } else {
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

      if (!cpuCaps.isEmpty() && !(declaredEffects.contains("cpu") || declaredEffects.contains("io"))) {
        var sampleCalls = summarizeCalls(capabilities, new java.util.ArrayList<>(cpuCaps));
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
    // 复用上面已算好的 capabilities（body 实际用到的能力），避免二次遍历函数体。
    checkManifestConstraints(func, capabilities);
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

  /**
   * Manifest 能力约束校验。
   * <p>
   * ★校验对象是「**声明的** ∪ **body 实际用到的**」能力（issue #84）。此前只查声明集合，
   * 且在 {@code !effectCapsExplicit} 时整体早退，于是裸 {@code @io}（未显式列 effectCaps）
   * 的函数即便真的调用了被 manifest deny 的能力也毫无诊断——沙箱被完全绕过。
   * TS 侧 {@code typecheck/module.ts} 本就把 {@code collectCapabilities(body)} 并入待查集合，
   * 本方法与之对齐。
   *
   * @param usedCaps {@code checkFunction} 已算好的 body 实际能力，避免重复遍历函数体
   */
  private void checkManifestConstraints(CoreModel.Func func, Map<CapabilityKind, List<String>> usedCaps) {
    if (manifest == null || func == null) {
      return;
    }
    var caps = new LinkedHashSet<CapabilityKind>();
    // 声明的能力（仅在显式声明时才有意义）
    if (func.effectCapsExplicit) {
      for (var capLabel : normalizeEffectCaps(func.effectCaps)) {
        CapabilityKind.fromLabel(capLabel).ifPresent(caps::add);
      }
    }
    // body 中实际用到的能力——绕过的关键补丁
    if (usedCaps != null) {
      caps.addAll(usedCaps.keySet());
    }
    for (var cap : caps) {
      if (!manifest.isAllowed(cap)) {
        emit(
          // 与 TS 一致用 CAPABILITY_NOT_ALLOWED(E300)：manifest 拒绝 ≠ 未声明能力。
          // 此前误用 WORKFLOW_UNDECLARED_CAPABILITY(E027)，而 E300 在 Java 侧从未被发出过。
          ErrorCode.CAPABILITY_NOT_ALLOWED,
          func.origin,
          // data 键与 TS builder.error(..., {func, module, cap}) 对齐
          Map.of(
            "func", func.name,
            "module", moduleName == null ? "" : moduleName,
            "cap", cap.displayName()
          ),
          // 格式串顺序：Function '%s' requires %s capability but manifest for module '%s' denies it.
          func.name,
          cap.displayName(),
          moduleName == null ? "" : moduleName
        );
      }
    }
  }
}
