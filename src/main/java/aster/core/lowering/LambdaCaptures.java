package aster.core.lowering;

import aster.core.ir.CoreModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lambda 的闭包捕获（ADR 0056 §4.2）。名字 n 进入 lambda L 的 captures，当且仅当：
 * <ol>
 *   <li>n 作为 {@code Name} 节点（值位置或调用目标）出现在 L 体内，含 L 内嵌套 lambda 的体内；</li>
 *   <li>该出现处 n 没有被 L 内部的绑定遮蔽（L 的形参、此前的 Let / Start、Match 模式绑定、嵌套 lambda 的形参）；</li>
 *   <li>n 是 L 外层的局部绑定（外层函数形参、L 之前的 Let / Start、外层 Match 模式绑定、外层 lambda 形参）。</li>
 * </ol>
 * 判定在已降级的 Core IR 上进行，方法调用已改写为「方法名作调用目标、接收者作首参」，与 ts 一致；
 * 方法名、效应关键词、运算符、模块声明、导入与内建函数都不是外层局部，不进 captures。
 * 顺序按首次引用的源码位置（行、列）升序，去重。Truffle Loader 按此顺序建帧槽，未捕获的名字按全局查找。
 */
final class LambdaCaptures {

  private LambdaCaptures() {}

  /**
   * @param params      L 的形参名
   * @param body        已降级的 L 体
   * @param outerLocals L 所在位置可见的外层局部名
   */
  static List<String> collect(List<String> params, CoreModel.Block body, Set<String> outerLocals) {
    var walker = new Walker(outerLocals);
    walker.bound.push(new HashSet<>(params));
    walker.block(body == null ? null : body.statements);
    return walker.ordered();
  }

  private static final class Walker {
    private final Set<String> outerLocals;
    private final Deque<Set<String>> bound = new ArrayDeque<>();
    /** 名字 → 首次引用位置（行、列、遍历序），保持插入序以便同位置时按遍历序。 */
    private final Map<String, long[]> first = new LinkedHashMap<>();
    private long seq = 0;

    Walker(Set<String> outerLocals) {
      this.outerLocals = outerLocals;
    }

    List<String> ordered() {
      List<Map.Entry<String, long[]>> entries = new ArrayList<>(first.entrySet());
      entries.sort(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[0])
          .thenComparingLong(e -> e.getValue()[1])
          .thenComparingLong(e -> e.getValue()[2]));
      return entries.stream().map(Map.Entry::getKey).toList();
    }

    private boolean isBound(String name) {
      for (Set<String> scope : bound) {
        if (scope.contains(name)) return true;
      }
      return false;
    }

    private void block(List<CoreModel.Stmt> statements) {
      if (statements == null) return;
      bound.push(new HashSet<>());
      try {
        statements.forEach(this::stmt);
      } finally {
        bound.pop();
      }
    }

    private void stmt(CoreModel.Stmt s) {
      switch (s) {
        case null -> { }
        case CoreModel.Let let -> {
          // 先看右侧再绑定：`Let x be x plus 1` 右侧的 x 指外层
          expr(let.expr);
          bound.peek().add(let.name);
        }
        case CoreModel.Set set -> expr(set.expr);
        case CoreModel.Return ret -> expr(ret.expr);
        case CoreModel.If ifs -> {
          expr(ifs.cond);
          block(ifs.thenBlock == null ? null : ifs.thenBlock.statements);
          block(ifs.elseBlock == null ? null : ifs.elseBlock.statements);
        }
        case CoreModel.Match match -> {
          expr(match.expr);
          if (match.cases == null) return;
          for (CoreModel.Case kase : match.cases) {
            Set<String> names = new HashSet<>();
            patternBindings(kase.pattern, names);
            bound.push(names);
            try {
              stmt(kase.body);
            } finally {
              bound.pop();
            }
          }
        }
        case CoreModel.Scope scope -> block(scope.statements);
        case CoreModel.Block b -> block(b.statements);
        case CoreModel.Start start -> {
          // 与 R1 一致：任务名先绑定
          bound.peek().add(start.name);
          expr(start.expr);
        }
        case CoreModel.Wait ignored -> { }
        case CoreModel.Workflow wf -> {
          for (CoreModel.Step step : wf.steps) {
            block(step.body == null ? null : step.body.statements);
            block(step.compensate == null ? null : step.compensate.statements);
          }
        }
      }
    }

    private void expr(CoreModel.Expr e) {
      switch (e) {
        case null -> { }
        case CoreModel.Name n -> name(n);
        case CoreModel.Call call -> {
          expr(call.target);
          if (call.args != null) call.args.forEach(this::expr);
        }
        case CoreModel.Construct c -> {
          if (c.fields != null) c.fields.forEach(f -> expr(f.expr));
        }
        case CoreModel.Ok ok -> expr(ok.expr);
        case CoreModel.Err err -> expr(err.expr);
        case CoreModel.Some some -> expr(some.expr);
        case CoreModel.Await await -> expr(await.expr);
        case CoreModel.IfE ife -> {
          expr(ife.cond);
          expr(ife.thenE);
          expr(ife.elseE);
        }
        case CoreModel.ListE list -> {
          if (list.elements != null) list.elements.forEach(this::expr);
        }
        case CoreModel.Lambda inner -> {
          // 嵌套 lambda：内层形参只在内层有效；内层引用的外层局部穿透记到本 lambda
          Set<String> params = new HashSet<>();
          if (inner.params != null) inner.params.forEach(p -> params.add(p.name));
          bound.push(params);
          try {
            block(inner.body == null ? null : inner.body.statements);
          } finally {
            bound.pop();
          }
        }
        default -> { }
      }
    }

    private void name(CoreModel.Name n) {
      String name = n.name;
      if (name == null || name.contains(".") || isBound(name) || !outerLocals.contains(name)) return;
      long line = n.origin != null && n.origin.start != null ? n.origin.start.line : Long.MAX_VALUE;
      long col = n.origin != null && n.origin.start != null ? n.origin.start.col : Long.MAX_VALUE;
      long order = seq++;
      first.merge(name, new long[] {line, col, order},
          (prev, cur) -> compare(cur, prev) < 0 ? cur : prev);
    }

    private static int compare(long[] a, long[] b) {
      for (int i = 0; i < a.length; i++) {
        int c = Long.compare(a[i], b[i]);
        if (c != 0) return c;
      }
      return 0;
    }

    private static void patternBindings(CoreModel.Pattern p, Set<String> out) {
      if (p instanceof CoreModel.PatName pn && pn.name != null) {
        out.add(pn.name);
      } else if (p instanceof CoreModel.PatCtor ctor && ctor.args != null) {
        ctor.args.forEach(arg -> patternBindings(arg, out));
      }
    }
  }
}
