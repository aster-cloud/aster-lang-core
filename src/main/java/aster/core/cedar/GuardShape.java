package aster.core.cedar;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.governance.GovernanceChecker;
import aster.core.typecheck.model.Diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cedar 导出形状检查（ADR 0047 §3，E720）：规则须带 @id、恰一个 guard 请求形状的参数、并返回 Verdict。
 * guard 请求形状只按字段名集合判定，不看 Data 名称与字段类型。
 */
public final class GuardShape {

  /** guard 请求的八个字段名。 */
  public static final Set<String> GUARD_FIELDS = Set.of(
      "principal_id", "principal_type", "principal_roles", "action",
      "resource_type", "resource_id", "context", "agent_model");

  private GuardShape() {}

  /** 合格时返回参数名；否则向 {@code out} 写一条 E720 并返回 empty。 */
  public static Optional<String> check(CoreModel.Module m, CoreModel.Func fn, List<Diagnostic> out) {
    Optional<String> reason = rejectReason(m, fn);
    if (reason.isEmpty()) return Optional.of(fn.params.get(0).name);
    out.add(notExportable(fn.name, reason.get(), fn.origin));
    return Optional.empty();
  }

  /** 构造 E720 诊断；导出器在函数缺失等场景复用。 */
  public static Diagnostic notExportable(String rule, String reason, CoreModel.Origin origin) {
    ErrorCode code = ErrorCode.CEDAR_NOT_EXPORTABLE_SHAPE;
    Map<String, Object> data = Map.of("rule", rule, "reason", reason);
    return new Diagnostic(Diagnostic.Severity.ERROR, code, code.render(data), Optional.ofNullable(origin),
        Optional.ofNullable(code.help()), Map.copyOf(data));
  }

  // 按固定顺序给出第一条不合格原因：@id → 参数个数 → 参数类型 → 返回 Verdict
  private static Optional<String> rejectReason(CoreModel.Module m, CoreModel.Func fn) {
    if (GovernanceChecker.ruleId(fn).isEmpty()) return Optional.of("missing @id");
    if (fn.params == null || fn.params.size() != 1) return Optional.of("expects exactly one parameter");
    CoreModel.Type type = fn.params.get(0).type;
    if (!isGuardRequest(m, type)) {
      return Optional.of("parameter type " + typeLabel(type) + " is not the guard request shape");
    }
    if (!GovernanceChecker.returnsVerdict(fn)) return Optional.of("result is not Verdict");
    return Optional.empty();
  }

  // 参数类型须为模块内同名 Data，且字段名集合恰为 guard 请求八字段
  private static boolean isGuardRequest(CoreModel.Module m, CoreModel.Type type) {
    if (!(type instanceof CoreModel.TypeName tn) || m.decls == null) return false;
    return m.decls.stream()
        .filter(d -> d instanceof CoreModel.Data data && tn.name.equals(data.name))
        .map(d -> fieldNames((CoreModel.Data) d))
        .anyMatch(GUARD_FIELDS::equals);
  }

  private static Set<String> fieldNames(CoreModel.Data data) {
    if (data.fields == null) return Set.of();
    return data.fields.stream().map(f -> f.name).collect(Collectors.toSet());
  }

  private static String typeLabel(CoreModel.Type type) {
    if (type instanceof CoreModel.TypeName tn) return tn.name;
    return type == null ? "<none>" : type.getClass().getSimpleName();
  }
}
