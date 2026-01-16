package aster.core.typecheck.visitor;

import aster.core.ir.CoreModel;

/**
 * 默认 Core IR 访问者实现
 * <p>
 * 提供递归遍历所有 Core IR 节点的默认实现。使用 Java 21 pattern matching switch
 * 简化节点分发逻辑。子类可覆写特定方法插入自定义逻辑。
 * <p>
 * 遍历策略：
 * - 深度优先递归遍历
 * - 自动处理嵌套结构（如 Block、Lambda、Match）
 * - 对 sealed 类型使用 pattern matching 确保完整性
 *
 * @param <Ctx> 访问上下文类型
 */
public class DefaultCoreVisitor<Ctx> implements CoreVisitor<Ctx, Void> {

  // ========== 顶层节点 ==========

  @Override
  public Void visitModule(CoreModel.Module m, Ctx ctx) {
    if (m.decls != null) {
      for (var decl : m.decls) {
        visitDeclaration(decl, ctx);
      }
    }
    return null;
  }

  @Override
  public Void visitDeclaration(CoreModel.Decl d, Ctx ctx) {
    return switch (d) {
      case CoreModel.Import i -> visitImportImpl(i, ctx);
      case CoreModel.Data data -> visitDataImpl(data, ctx);
      case CoreModel.Enum e -> visitEnumImpl(e, ctx);
      case CoreModel.Func f -> visitFuncImpl(f, ctx);
    };
  }

  // ========== 声明类型实现 ==========

  private Void visitImportImpl(CoreModel.Import i, Ctx ctx) {
    // Import 无子节点需要遍历
    return null;
  }

  private Void visitDataImpl(CoreModel.Data d, Ctx ctx) {
    // 遍历字段类型
    if (d.fields != null) {
      for (var field : d.fields) {
        if (field.type != null) {
          visitType(field.type, ctx);
        }
      }
    }
    return null;
  }

  private Void visitEnumImpl(CoreModel.Enum e, Ctx ctx) {
    // Enum 无子节点需要遍历
    return null;
  }

  private Void visitFuncImpl(CoreModel.Func f, Ctx ctx) {
    // 遍历参数类型
    if (f.params != null) {
      for (var param : f.params) {
        if (param.type != null) {
          visitType(param.type, ctx);
        }
      }
    }
    // 遍历返回类型
    if (f.ret != null) {
      visitType(f.ret, ctx);
    }
    // 遍历函数体
    if (f.body != null) {
      visitBlock(f.body, ctx);
    }
    return null;
  }

  // ========== 语句级节点 ==========

  @Override
  public Void visitBlock(CoreModel.Block b, Ctx ctx) {
    if (b.statements != null) {
      for (var stmt : b.statements) {
        visitStatement(stmt, ctx);
      }
    }
    return null;
  }

  @Override
  public Void visitStatement(CoreModel.Stmt s, Ctx ctx) {
    return switch (s) {
      case CoreModel.Let l -> visitLetImpl(l, ctx);
      case CoreModel.Set set -> visitSetImpl(set, ctx);
      case CoreModel.Return r -> visitReturnImpl(r, ctx);
      case CoreModel.If i -> visitIfImpl(i, ctx);
      case CoreModel.Match m -> visitMatchImpl(m, ctx);
      case CoreModel.Scope scope -> visitScopeImpl(scope, ctx);
      case CoreModel.Block block -> visitBlock(block, ctx);
      case CoreModel.Start start -> visitStartImpl(start, ctx);
      case CoreModel.Wait w -> visitWaitImpl(w, ctx);
      case CoreModel.Workflow workflow -> visitWorkflowImpl(workflow, ctx);
    };
  }

  // ========== 语句类型实现 ==========

  private Void visitLetImpl(CoreModel.Let l, Ctx ctx) {
    if (l.expr != null) {
      visitExpression(l.expr, ctx);
    }
    return null;
  }

  private Void visitSetImpl(CoreModel.Set s, Ctx ctx) {
    if (s.expr != null) {
      visitExpression(s.expr, ctx);
    }
    return null;
  }

  private Void visitWorkflowImpl(CoreModel.Workflow workflow, Ctx ctx) {
    if (workflow.steps != null) {
      for (var step : workflow.steps) {
        visitWorkflowStep(step, ctx);
      }
    }
    return null;
  }

  private void visitWorkflowStep(CoreModel.Step step, Ctx ctx) {
    if (step == null) {
      return;
    }
    if (step.body != null) {
      visitBlock(step.body, ctx);
    }
    if (step.compensate != null) {
      visitBlock(step.compensate, ctx);
    }
  }

  private Void visitReturnImpl(CoreModel.Return r, Ctx ctx) {
    if (r.expr != null) {
      visitExpression(r.expr, ctx);
    }
    return null;
  }

  private Void visitIfImpl(CoreModel.If i, Ctx ctx) {
    // 遍历条件
    if (i.cond != null) {
      visitExpression(i.cond, ctx);
    }
    // 遍历 then 分支
    if (i.thenBlock != null) {
      visitBlock(i.thenBlock, ctx);
    }
    // 遍历 else 分支
    if (i.elseBlock != null) {
      visitBlock(i.elseBlock, ctx);
    }
    return null;
  }

  private Void visitMatchImpl(CoreModel.Match m, Ctx ctx) {
    // 遍历被匹配的表达式
    if (m.expr != null) {
      visitExpression(m.expr, ctx);
    }
    // 遍历所有分支
    if (m.cases != null) {
      for (var kase : m.cases) {
        // 遍历模式
        if (kase.pattern != null) {
          visitPattern(kase.pattern, ctx);
        }
        // 遍历分支体
        if (kase.body != null) {
          visitStatement(kase.body, ctx);
        }
      }
    }
    return null;
  }

  private Void visitScopeImpl(CoreModel.Scope s, Ctx ctx) {
    // Scope 语义为引入新作用域的 Block
    if (s.statements != null) {
      for (var stmt : s.statements) {
        visitStatement(stmt, ctx);
      }
    }
    return null;
  }

  private Void visitStartImpl(CoreModel.Start s, Ctx ctx) {
    if (s.expr != null) {
      visitExpression(s.expr, ctx);
    }
    return null;
  }

  private Void visitWaitImpl(CoreModel.Wait w, Ctx ctx) {
    // Wait 无子节点需要遍历（只有任务名列表）
    return null;
  }

  // ========== 表达式级节点 ==========

  @Override
  public Void visitExpression(CoreModel.Expr e, Ctx ctx) {
    return switch (e) {
      // 字面量和名称（无子节点）
      case CoreModel.Name n -> null;
      case CoreModel.Bool b -> null;
      case CoreModel.IntE i -> null;
      case CoreModel.LongE l -> null;
      case CoreModel.DoubleE d -> null;
      case CoreModel.StringE s -> null;
      case CoreModel.NullE n -> null;
      case CoreModel.NoneE n -> null;

      // 包装类型（单个子表达式）
      case CoreModel.Ok ok -> visitExpressionChild(ok.expr, ctx);
      case CoreModel.Err err -> visitExpressionChild(err.expr, ctx);
      case CoreModel.Some some -> visitExpressionChild(some.expr, ctx);
      case CoreModel.Await await -> visitExpressionChild(await.expr, ctx);

      // 构造器
      case CoreModel.Construct c -> visitConstructImpl(c, ctx);

      // 函数调用
      case CoreModel.Call call -> visitCallImpl(call, ctx);

      // Lambda
      case CoreModel.Lambda lambda -> visitLambdaImpl(lambda, ctx);
    };
  }

  private Void visitExpressionChild(CoreModel.Expr expr, Ctx ctx) {
    if (expr != null) {
      visitExpression(expr, ctx);
    }
    return null;
  }

  private Void visitConstructImpl(CoreModel.Construct c, Ctx ctx) {
    if (c.fields != null) {
      for (var field : c.fields) {
        if (field.expr != null) {
          visitExpression(field.expr, ctx);
        }
      }
    }
    return null;
  }

  private Void visitCallImpl(CoreModel.Call call, Ctx ctx) {
    // 遍历被调用的表达式
    if (call.target != null) {
      visitExpression(call.target, ctx);
    }
    // 遍历参数
    if (call.args != null) {
      for (var arg : call.args) {
        visitExpression(arg, ctx);
      }
    }
    return null;
  }

  private Void visitLambdaImpl(CoreModel.Lambda lambda, Ctx ctx) {
    // 遍历参数类型
    if (lambda.params != null) {
      for (var param : lambda.params) {
        if (param.type != null) {
          visitType(param.type, ctx);
        }
      }
    }
    // 遍历返回类型
    if (lambda.ret != null) {
      visitType(lambda.ret, ctx);
    }
    // 遍历函数体
    if (lambda.body != null) {
      visitBlock(lambda.body, ctx);
    }
    return null;
  }

  // ========== 模式节点（默认不处理，子类按需覆写） ==========

  @Override
  public Void visitPattern(CoreModel.Pattern p, Ctx ctx) {
    return switch (p) {
      case CoreModel.PatNull pn -> null;
      case CoreModel.PatName pn -> null;
      case CoreModel.PatInt pi -> null;
      case CoreModel.PatCtor pc -> visitPatCtorImpl(pc, ctx);
    };
  }

  private Void visitPatCtorImpl(CoreModel.PatCtor pc, Ctx ctx) {
    // 遍历嵌套模式
    if (pc.args != null) {
      for (var arg : pc.args) {
        visitPattern(arg, ctx);
      }
    }
    return null;
  }

  // ========== 类型节点（默认不处理，子类按需覆写） ==========

  @Override
  public Void visitType(CoreModel.Type t, Ctx ctx) {
    return switch (t) {
      case CoreModel.TypeName tn -> null;
      case CoreModel.TypeVar tv -> null;
      case CoreModel.TypeApp ta -> visitTypeAppImpl(ta, ctx);
      case CoreModel.Result r -> visitResultTypeImpl(r, ctx);
      case CoreModel.Maybe m -> visitTypeChild(m.type, ctx);
      case CoreModel.Option o -> visitTypeChild(o.type, ctx);
      case CoreModel.ListT l -> visitTypeChild(l.type, ctx);
      case CoreModel.MapT m -> visitMapTypeImpl(m, ctx);
      case CoreModel.FuncType f -> visitFuncTypeImpl(f, ctx);
      case CoreModel.PiiType pii -> visitTypeChild(pii.baseType, ctx);
    };
  }

  private Void visitTypeChild(CoreModel.Type type, Ctx ctx) {
    if (type != null) {
      visitType(type, ctx);
    }
    return null;
  }

  private Void visitTypeAppImpl(CoreModel.TypeApp ta, Ctx ctx) {
    if (ta.args != null) {
      for (var arg : ta.args) {
        visitType(arg, ctx);
      }
    }
    return null;
  }

  private Void visitResultTypeImpl(CoreModel.Result r, Ctx ctx) {
    if (r.ok != null) {
      visitType(r.ok, ctx);
    }
    if (r.err != null) {
      visitType(r.err, ctx);
    }
    return null;
  }

  private Void visitMapTypeImpl(CoreModel.MapT m, Ctx ctx) {
    if (m.key != null) {
      visitType(m.key, ctx);
    }
    if (m.val != null) {
      visitType(m.val, ctx);
    }
    return null;
  }

  private Void visitFuncTypeImpl(CoreModel.FuncType f, Ctx ctx) {
    if (f.params != null) {
      for (var param : f.params) {
        visitType(param, ctx);
      }
    }
    if (f.ret != null) {
      visitType(f.ret, ctx);
    }
    return null;
  }
}
