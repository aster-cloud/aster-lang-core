package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 治理检查器（ADR 0039 §3/§4）：规则元数据注解与 Verdict 规则身份。
 * 与 TS 侧 aster-lang-ts/src/typecheck/governance.ts 逐条对等。
 *
 * <p>判定「规则返回 Verdict」的口径，两引擎一致：
 * 声明返回类型为 TypeName Verdict，或函数体任一 Return 的表达式是 Verdict.* 的直接调用。
 *
 * <p>注解参数约定：位置参数在 {@code Annotation.params} 中以 "$0"、"$1"… 为键；
 * 治理注解只接受恰好一个位置字符串参数，命名参数或非字符串一律视为非法（E702）。
 */
public final class GovernanceChecker {
  private static final Set<String> STRING_ARG_ANNOTATIONS = Set.of("id", "reason", "control");

  /** 检查整个模块：E702 注解参数、W700 缺 @id、E701 模块内 @id 重复。 */
  public List<Diagnostic> checkModule(CoreModel.Module module) {
    List<Diagnostic> out = new ArrayList<>();
    if (module == null || module.decls == null) return out;
    // @id → 拥有该 id 的规则（按声明顺序），用于发现重复
    Map<String, List<CoreModel.Func>> idOwners = new LinkedHashMap<>();
    for (CoreModel.Decl decl : module.decls) {
      if (decl instanceof CoreModel.Func func) checkFunc(func, idOwners, out);
    }
    for (var e : idOwners.entrySet()) {
      if (e.getValue().size() < 2) continue;
      List<String> names = e.getValue().stream().map(f -> String.valueOf(f.name)).toList();
      out.add(diag(ErrorCode.GOV_DUPLICATE_RULE_ID, e.getValue().get(0).origin,
          Map.of("id", e.getKey(), "rules", String.join(", ", names))));
    }
    return out;
  }

  private static void checkFunc(CoreModel.Func func, Map<String, List<CoreModel.Func>> idOwners, List<Diagnostic> out) {
    String rule = String.valueOf(func.name);
    for (CoreModel.Annotation a : annotationsOf(func)) {
      if (STRING_ARG_ANNOTATIONS.contains(a.name) && !isSingleStringArg(a)) {
        out.add(diag(ErrorCode.GOV_ANNOTATION_ARG_INVALID, func.origin, Map.of("annotation", a.name, "rule", rule)));
      }
    }
    Optional<String> id = ruleId(func);
    if (id.isPresent()) {
      idOwners.computeIfAbsent(id.get(), k -> new ArrayList<>()).add(func);
    } else if (returnsVerdict(func)) {
      out.add(diag(ErrorCode.GOV_VERDICT_RULE_MISSING_ID, func.origin, Map.of("rule", rule)));
    }
  }

  /** 规则 @id：恰好一个位置字符串参数时返回其值；否则 empty（E702 另行报告）。 */
  public static Optional<String> ruleId(CoreModel.Func func) {
    for (CoreModel.Annotation a : annotationsOf(func)) {
      if ("id".equals(a.name) && isSingleStringArg(a)) return Optional.of((String) a.params.get("$0"));
    }
    return Optional.empty();
  }

  /** 规则 @control 列表，按源码顺序；参数非法的条目跳过。 */
  public static List<String> controls(CoreModel.Func func) {
    List<String> out = new ArrayList<>();
    for (CoreModel.Annotation a : annotationsOf(func)) {
      if ("control".equals(a.name) && isSingleStringArg(a)) out.add((String) a.params.get("$0"));
    }
    return out;
  }

  /** 规则是否返回 Verdict：声明返回类型为 Verdict，或任一 Return 直接调用 Verdict.*。 */
  public static boolean returnsVerdict(CoreModel.Func func) {
    if (func.ret instanceof CoreModel.TypeName tn && VerdictSignatures.TYPE_NAME.equals(tn.name)) return true;
    return func.body != null && anyReturnIsVerdictCall(func.body);
  }

  private static boolean anyReturnIsVerdictCall(CoreModel.Stmt stmt) {
    return switch (stmt) {
      case CoreModel.Return r -> isVerdictCall(r.expr);
      case CoreModel.Block b -> anyReturnIsVerdictCall(b.statements);
      case CoreModel.Scope s -> anyReturnIsVerdictCall(s.statements);
      case CoreModel.If i -> (i.thenBlock != null && anyReturnIsVerdictCall(i.thenBlock))
          || (i.elseBlock != null && anyReturnIsVerdictCall(i.elseBlock));
      case CoreModel.Match m -> m.cases != null
          && m.cases.stream().anyMatch(c -> c.body != null && anyReturnIsVerdictCall(c.body));
      case null, default -> false;
    };
  }

  private static boolean anyReturnIsVerdictCall(List<CoreModel.Stmt> stmts) {
    return stmts != null && stmts.stream().anyMatch(GovernanceChecker::anyReturnIsVerdictCall);
  }

  private static boolean isVerdictCall(CoreModel.Expr expr) {
    return expr instanceof CoreModel.Call c && c.target instanceof CoreModel.Name n
        && VerdictSignatures.isVerdictCall(n.name);
  }

  private static boolean isSingleStringArg(CoreModel.Annotation a) {
    return a.params != null && a.params.size() == 1 && a.params.get("$0") instanceof String;
  }

  private static List<CoreModel.Annotation> annotationsOf(CoreModel.Func f) {
    return f.annotations == null ? List.of() : f.annotations;
  }

  private static Diagnostic diag(ErrorCode code, CoreModel.Origin origin, Map<String, Object> data) {
    var severity = switch (code.severity()) {
      case ERROR -> Diagnostic.Severity.ERROR;
      case WARNING -> Diagnostic.Severity.WARNING;
      case INFO -> Diagnostic.Severity.INFO;
    };
    return new Diagnostic(severity, code, code.render(data), Optional.ofNullable(origin),
        Optional.ofNullable(code.help()), Map.copyOf(data));
  }
}
