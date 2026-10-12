package aster.core.cedar;

import aster.core.ir.CoreModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 沿规则体做深度优先的路径遍历（ADR 0047 §4.1）：维护路径条件列表与 Let 绑定表，每个 {@code Return Verdict.*}
 * 产生一条策略，条件为到达它的路径上全部条件的合取。then 先于 else，序号即 Aster 的首匹配顺序。
 * 子块复制路径与绑定表，块内新增的内容不外泄；遇到错误继续遍历以收集全部诊断。
 */
final class PathWalker {

  private static final Map<String, Integer> VERDICT_ARITY =
      Map.of("allow", 0, "deny", 1, "require_approval", 2, "escalate", 1);
  private static final String FALLS_THROUGH = "branch that can end without a Verdict";

  private final ConditionTranslator tr;
  private final String ruleId;
  private final List<EmittedPolicy> emitted = new ArrayList<>();

  PathWalker(ConditionTranslator tr, String ruleId) {
    this.tr = tr;
    this.ruleId = ruleId;
  }

  /** 遍历函数体，按首匹配顺序返回各分支的策略。 */
  List<EmittedPolicy> walk(CoreModel.Block body, CoreModel.Origin fnOrigin) {
    branch(body, new ArrayList<>(), new HashMap<>(), fnOrigin);
    return List.copyOf(emitted);
  }

  // 子块：在副本上遍历，未必然返回时报 E721
  private void branch(CoreModel.Block block, List<String> path, Map<String, ConditionTranslator.Term> env,
                      CoreModel.Origin origin) {
    if (walkStatements(statements(block), new ArrayList<>(path), new HashMap<>(env))) return;
    tr.at(origin);
    tr.unsupported(FALLS_THROUGH);
  }

  // 返回语句序列是否必然以 Return 结束；必然返回之后的语句不可达，不再遍历
  private boolean walkStatements(List<CoreModel.Stmt> stmts, List<String> path,
                                 Map<String, ConditionTranslator.Term> env) {
    for (CoreModel.Stmt s : stmts) {
      if (step(s, path, env)) return true;
    }
    return false;
  }

  private boolean step(CoreModel.Stmt s, List<String> path, Map<String, ConditionTranslator.Term> env) {
    tr.at(originOf(s));
    return switch (s) {
      case CoreModel.Let l -> let(l, path, env);
      case CoreModel.If i -> ifStmt(i, path, env);
      case CoreModel.Return r -> ret(r, path);
      case CoreModel.Block b -> walkStatements(statements(b), path, env);
      case CoreModel.Workflow w -> unsupported("workflow");
      default -> unsupported(s.getClass().getSimpleName());
    };
  }

  // Let：读 context 键时把「已定义」要求加入路径，然后绑定名称供内联
  private boolean let(CoreModel.Let l, List<String> path, Map<String, ConditionTranslator.Term> env) {
    ConditionTranslator.Term t = tr.term(l.expr, env);
    tr.definedness(t).ifPresent(path::add);
    env.put(l.name, t);
    return false;
  }

  // If：then 路径追加 T(c)；有 else 时 else 路径追加 F(c) 并结束本块；否则当前路径追加 F(c) 继续
  private boolean ifStmt(CoreModel.If i, List<String> path, Map<String, ConditionTranslator.Term> env) {
    Optional<Cond> c = tr.cond(i.cond, env);
    branch(i.thenBlock, extend(path, c.map(Cond::t)), env, i.origin);
    if (i.elseBlock != null) {
      branch(i.elseBlock, extend(path, c.map(Cond::f)), env, i.origin);
      return true;
    }
    c.ifPresent(x -> path.add(x.f()));
    return false;
  }

  // Return：须为 Verdict.* 且参数全是文本字面量
  private boolean ret(CoreModel.Return r, List<String> path) {
    Optional<String> outcome = verdictOutcome(r.expr);
    if (outcome.isEmpty()) return unsupportedReturn("return of a non-Verdict value");
    List<CoreModel.Expr> args = ((CoreModel.Call) r.expr).args == null ? List.of() : ((CoreModel.Call) r.expr).args;
    boolean literal = args.size() == VERDICT_ARITY.get(outcome.get())
        && args.stream().allMatch(a -> a instanceof CoreModel.StringE);
    if (!literal) return unsupportedReturn("non-literal verdict argument");
    List<String> values = args.stream().map(a -> ((CoreModel.StringE) a).value).toList();
    emitted.add(policy(outcome.get(), values, path));
    return true;
  }

  private static Optional<String> verdictOutcome(CoreModel.Expr e) {
    if (!(e instanceof CoreModel.Call c) || !(c.target instanceof CoreModel.Name n)) return Optional.empty();
    if (!n.name.startsWith("Verdict.")) return Optional.empty();
    String outcome = n.name.substring("Verdict.".length());
    return VERDICT_ARITY.containsKey(outcome) ? Optional.of(outcome) : Optional.empty();
  }

  private EmittedPolicy policy(String outcome, List<String> args, List<String> path) {
    String id = ruleId + "#" + (emitted.size() + 1);
    String effect = outcome.equals("allow") ? "permit" : "forbid";
    String approver = outcome.equals("require_approval") ? args.get(0) : null;
    String reason = args.isEmpty() ? null : args.get(args.size() - 1);
    String condition = path.isEmpty() ? "true" : String.join(" && ", path);
    return new EmittedPolicy(id, effect, outcome, reason, approver, condition);
  }

  private boolean unsupported(String construct) {
    tr.unsupported(construct);
    return false;
  }

  // 不合格的 Return 仍终止本路径：其后语句不可达，避免再报「无 Verdict 落空」
  private boolean unsupportedReturn(String construct) {
    tr.unsupported(construct);
    return true;
  }

  private static List<String> extend(List<String> path, Optional<String> item) {
    List<String> out = new ArrayList<>(path);
    item.ifPresent(out::add);
    return out;
  }

  private static List<CoreModel.Stmt> statements(CoreModel.Block b) {
    return b == null || b.statements == null ? List.of() : b.statements;
  }

  private static CoreModel.Origin originOf(CoreModel.Stmt s) {
    return switch (s) {
      case CoreModel.Let x -> x.origin;
      case CoreModel.Set x -> x.origin;
      case CoreModel.Return x -> x.origin;
      case CoreModel.If x -> x.origin;
      case CoreModel.Match x -> x.origin;
      case CoreModel.Scope x -> x.origin;
      case CoreModel.Block x -> x.origin;
      case CoreModel.Start x -> x.origin;
      case CoreModel.Wait x -> x.origin;
      case CoreModel.Workflow x -> x.origin;
    };
  }
}
