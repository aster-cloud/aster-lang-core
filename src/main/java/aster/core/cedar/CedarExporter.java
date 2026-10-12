package aster.core.cedar;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.GovernanceChecker;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 单条规则的 Cedar 导出入口（ADR 0047 §4）：查找函数 → 形状检查 → 类型推断遍 → 翻译与路径遍历 → 生成策略与 schema。
 * 整体导出：任一诊断都使结果不可导出，只返回全部诊断。
 */
public final class CedarExporter {

  private CedarExporter() {}

  public static CedarExport export(CoreModel.Module m, String functionName, String sourceHash) {
    List<Diagnostic> diags = new ArrayList<>();
    Optional<CoreModel.Func> fn = find(m, functionName);
    if (fn.isPresent()) return export(m, fn.get(), sourceHash, diags);
    diags.add(GuardShape.notExportable(functionName, "function " + functionName + " not found", m.origin));
    return rejected(m, functionName, null, List.of(), new RuleFacts(), diags);
  }

  private static CedarExport export(CoreModel.Module m, CoreModel.Func fn, String sourceHash, List<Diagnostic> diags) {
    String ruleId = GovernanceChecker.ruleId(fn).orElse(null);
    List<String> controls = GovernanceChecker.controls(fn);
    RuleFacts facts = new RuleFacts();
    Optional<String> param = GuardShape.check(m, fn, diags);
    if (param.isEmpty()) return rejected(m, fn.name, ruleId, controls, facts, diags);
    List<EmittedPolicy> emitted = translate(fn, param.get(), ruleId, facts, diags);
    if (!diags.isEmpty()) return rejected(m, fn.name, ruleId, controls, facts, diags);
    String policies = PolicyEmitter.emit(emitted, ruleId, m.name, sourceHash, controls, m.profile);
    String schema = SchemaEmitter.emit(facts.actions(), facts.contextTypes(), facts.agentModelUsed(), fn.name);
    List<String> ids = emitted.stream().map(EmittedPolicy::id).toList();
    return new CedarExport(true, ruleId, fn.name, policies, ids, emitted, schema, m.profile, controls, facts, List.of());
  }

  // 推断遍只为收集 context 键类型（E722 在此遍报出，其余诊断丢弃）；翻译遍据此判定键间比较并产出分支
  private static List<EmittedPolicy> translate(CoreModel.Func fn, String param, String ruleId, RuleFacts facts,
                                               List<Diagnostic> diags) {
    var infer = new ConditionTranslator(param, fn.name, facts, new ArrayList<>(), diags, true, fn.origin);
    new PathWalker(infer, ruleId).walk(fn.body, fn.origin);
    var tr = new ConditionTranslator(param, fn.name, facts, diags, diags, false, fn.origin);
    List<EmittedPolicy> emitted = new PathWalker(tr, ruleId).walk(fn.body, fn.origin);
    tr.at(fn.origin);
    facts.untypedKeys().forEach(k -> tr.unsupported("context key '" + k + "' of unknown type"));
    return emitted;
  }

  private static Optional<CoreModel.Func> find(CoreModel.Module m, String name) {
    if (m.decls == null) return Optional.empty();
    return m.decls.stream()
        .filter(d -> d instanceof CoreModel.Func f && name.equals(f.name))
        .map(d -> (CoreModel.Func) d)
        .findFirst();
  }

  private static CedarExport rejected(CoreModel.Module m, String functionName, String ruleId, List<String> controls,
                                      RuleFacts facts, List<Diagnostic> diags) {
    return new CedarExport(false, ruleId, functionName, null, List.of(), List.of(), null, m.profile,
        controls, facts, List.copyOf(diags));
  }
}
