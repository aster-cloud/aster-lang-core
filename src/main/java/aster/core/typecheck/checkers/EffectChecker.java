package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.Origin;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.EffectConfig;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.SymbolTable;
import aster.core.typecheck.model.SymbolInfo;
import aster.core.typecheck.model.VisitorContext;

import java.util.*;

/**
 * 效果检查器（Effect Checker）
 * <p>
 * 基于格论的效果系统，处理效果推断、效果传播和兼容性检查。
 * 使用 EffectConfig 配置驱动的前缀推断规则。
 * <p>
 * 效果层次（偏序关系）：
 * PURE ⊑ CPU ⊑ IO
 * PURE ⊑ ASYNC
 * ASYNC 是顶层效果（所有效果的最小上界）
 * <p>
 * 核心功能：
 * - 效果推断：根据函数调用前缀和类型注解推断效果
 * - 效果传播：计算表达式和代码块的综合效果
 * - 效果Join：实现格论中的最小上界运算
 * - 兼容性检查：验证函数体效果不超过声明效果
 */
public final class EffectChecker {

  // ========== 效果枚举 ==========

  /**
   * 效果级别
   */
  public enum Effect {
    /** 纯函数：无副作用 (∅) */
    PURE,
    /** CPU 密集型：计算密集但无 I/O */
    CPU,
    /** I/O 操作：包含外部交互 */
    IO,
    /** 异步操作：包含 async/await */
    ASYNC;

    /**
     * 从字符串解析效果
     */
    public static Effect fromString(String str) {
      return switch (str.toLowerCase()) {
        case "async" -> ASYNC;
        case "io" -> IO;
        case "cpu" -> CPU;
        case "pure", "" -> PURE;
        default -> PURE;
      };
    }

    @Override
    public String toString() {
      return switch (this) {
        case PURE -> "pure";
        case CPU -> "cpu";
        case IO -> "io";
        case ASYNC -> "async";
      };
    }
  }

  // ========== 字段 ==========

  private final SymbolTable symbolTable;
  private final EffectConfig effectConfig;
  private final DiagnosticBuilder diagnostics;

  // ========== 构造器 ==========

  public EffectChecker(SymbolTable symbolTable, EffectConfig effectConfig, DiagnosticBuilder diagnostics) {
    if (symbolTable == null) {
      throw new IllegalArgumentException("symbolTable cannot be null");
    }
    if (effectConfig == null) {
      throw new IllegalArgumentException("effectConfig cannot be null");
    }
    if (diagnostics == null) {
      throw new IllegalArgumentException("diagnostics cannot be null");
    }
    this.symbolTable = symbolTable;
    this.effectConfig = effectConfig;
    this.diagnostics = diagnostics;
  }

  // ========== 效果推断 ==========

  /**
   * 推断表达式的效果
   *
   * @param expr 表达式
   * @param ctx  访问上下文
   * @return 推断的效果
   */
  public Effect inferEffect(CoreModel.Expr expr, VisitorContext ctx) {
    return switch (expr) {
      case CoreModel.Call call -> inferCallEffect(call, ctx);
      case CoreModel.Lambda lambda -> inferBlockEffect(lambda.body, ctx);
      case CoreModel.Ok ok -> inferEffect(ok.expr, ctx);
      case CoreModel.Err err -> inferEffect(err.expr, ctx);
      case CoreModel.Some some -> inferEffect(some.expr, ctx);
      case CoreModel.Await await -> {
        // await 表达式总是标记为 ASYNC
        var exprEffect = inferEffect(await.expr, ctx);
        yield join(Effect.ASYNC, exprEffect);
      }
      case CoreModel.Construct construct -> {
        var maxEffect = Effect.PURE;
        for (var field : construct.fields) {
          maxEffect = join(maxEffect, inferEffect(field.expr, ctx));
        }
        yield maxEffect;
      }
      // 字面量和名称引用都是纯的
      default -> Effect.PURE;
    };
  }

  /**
   * 推断语句的效果
   */
  public Effect inferStatementEffect(CoreModel.Stmt stmt, VisitorContext ctx) {
    return switch (stmt) {
      case CoreModel.Let let -> inferEffect(let.expr, ctx);
      case CoreModel.Set set -> inferEffect(set.expr, ctx);
      case CoreModel.Return ret -> inferEffect(ret.expr, ctx);
      case CoreModel.If ifStmt -> {
        var condEffect = inferEffect(ifStmt.cond, ctx);
        var thenEffect = inferBlockEffect(ifStmt.thenBlock, ctx);
        var elseEffect = ifStmt.elseBlock != null
          ? inferBlockEffect(ifStmt.elseBlock, ctx)
          : Effect.PURE;
        yield join(join(condEffect, thenEffect), elseEffect);
      }
      case CoreModel.Match match -> {
        var exprEffect = inferEffect(match.expr, ctx);
        var maxCaseEffect = Effect.PURE;
        for (var kase : match.cases) {
          var caseEffect = inferStatementEffect(kase.body, ctx);
          maxCaseEffect = join(maxCaseEffect, caseEffect);
        }
        yield join(exprEffect, maxCaseEffect);
      }
      case CoreModel.Block block -> inferBlockEffect(block, ctx);
      case CoreModel.Scope scope -> {
        var maxEffect = Effect.PURE;
        for (var s : scope.statements) {
          maxEffect = join(maxEffect, inferStatementEffect(s, ctx));
        }
        yield maxEffect;
      }
      case CoreModel.Start start -> inferEffect(start.expr, ctx);
      case CoreModel.Wait wait -> Effect.PURE; // Wait 本身不产生效果
      case CoreModel.Workflow workflow -> {
        var maxEffect = Effect.PURE;
        if (workflow.steps != null) {
          for (var step : workflow.steps) {
            if (step.body != null) {
              maxEffect = join(maxEffect, inferBlockEffect(step.body, ctx));
            }
            if (step.compensate != null) {
              maxEffect = join(maxEffect, inferBlockEffect(step.compensate, ctx));
            }
          }
        }
        yield maxEffect;
      }
    };
  }

  /**
   * 推断代码块的效果
   */
  public Effect inferBlockEffect(CoreModel.Block block, VisitorContext ctx) {
    var maxEffect = Effect.PURE;
    for (var stmt : block.statements) {
      maxEffect = join(maxEffect, inferStatementEffect(stmt, ctx));
    }
    return maxEffect;
  }

  /**
   * 推断函数调用的效果
   * <p>
   * 推断策略：
   * 1. 从被调用函数的符号表中读取声明的效果（优先级最高）
   * 2. 根据配置的前缀推断（IO/CPU前缀）
   * 3. 传播参数效果
   */
  private Effect inferCallEffect(CoreModel.Call call, VisitorContext ctx) {
    // 1. 【修复】优先从符号表读取函数声明的效果
    if (call.target instanceof CoreModel.Name name) {
      var funcName = name.name;

      // 查找函数符号
      var symbolOpt = symbolTable.lookup(funcName);
      if (symbolOpt.isPresent()) {
        var symbol = symbolOpt.get();
        // 如果是函数且有声明效果，直接使用
        if (symbol.kind() == SymbolInfo.SymbolKind.FUNCTION && symbol.declaredEffect().isPresent()) {
          return Effect.fromString(symbol.declaredEffect().get());
        }
      }

      // 2. 回退到前缀推断（配置驱动）
      // 检查 IO 前缀
      for (var prefix : effectConfig.getIOPrefixes()) {
        if (funcName.startsWith(prefix)) {
          return Effect.IO;
        }
      }

      // 检查 CPU 前缀
      for (var prefix : effectConfig.getCPUPrefixes()) {
        if (funcName.startsWith(prefix)) {
          return Effect.CPU;
        }
      }
    }

    // 3. 默认传播参数效果（取最大值）
    var maxEffect = Effect.PURE;
    for (var arg : call.args) {
      maxEffect = join(maxEffect, inferEffect(arg, ctx));
    }

    return maxEffect;
  }

  // ========== 效果兼容性检查 ==========

  /**
   * 检查效果兼容性
   * <p>
   * 实际效果必须是声明效果的子效果（actual ⊑ declared）。
   *
   * @param declared 声明的效果
   * @param actual   实际推断的效果
   * @param span     源码位置
   */
  public void checkEffectCompatibility(Effect declared, Effect actual, Optional<Origin> span) {
    if (!isSubEffect(actual, declared)) {
      diagnostics.error(ErrorCode.EFF_MISSING_IO, span, Map.of(
        "func", "",
        "declared", declared.toString(),
        "actual", actual.toString()
      ));
    }
  }

  /**
   * 检查函数效果违反
   *
   * @param declaredEffects 声明的效果列表（如 ["io", "cpu"]）
   * @param inferredEffects 推断的效果列表
   * @param span            源码位置
   */
  public void checkEffectViolation(List<String> declaredEffects, List<String> inferredEffects, Optional<Origin> span) {
    diagnostics.effectViolation(declaredEffects, inferredEffects, span);
  }

  // ========== 效果运算 ==========

  /**
   * 效果 Join（格论中的最小上界）
   * <p>
   * Join 规则：
   * - PURE ⊔ PURE = PURE
   * - PURE ⊔ CPU = CPU
   * - PURE ⊔ IO = IO
   * - PURE ⊔ ASYNC = ASYNC
   * - CPU ⊔ CPU = CPU
   * - CPU ⊔ IO = IO
   * - CPU ⊔ ASYNC = ASYNC
   * - IO ⊔ IO = IO
   * - IO ⊔ ASYNC = ASYNC
   * - ASYNC ⊔ ASYNC = ASYNC
   * <p>
   * 效果层次：ASYNC 是最高级别，独立于 IO/CPU 分支
   *
   * @param e1 效果1
   * @param e2 效果2
   * @return 最小上界
   */
  public Effect join(Effect e1, Effect e2) {
    // ASYNC 是最高级别
    if (e1 == Effect.ASYNC || e2 == Effect.ASYNC) {
      return Effect.ASYNC;
    }
    if (e1 == Effect.IO || e2 == Effect.IO) {
      return Effect.IO;
    }
    if (e1 == Effect.CPU || e2 == Effect.CPU) {
      return Effect.CPU;
    }
    return Effect.PURE;
  }

  /**
   * 效果子类型关系
   * <p>
   * 偏序关系：PURE ⊑ CPU ⊑ IO，PURE ⊑ ASYNC
   * ASYNC 是顶层效果，所有效果都是 ASYNC 的子效果
   *
   * @param sub 子效果
   * @param sup 超效果
   * @return 如果 sub ⊑ sup 返回 true
   */
  public boolean isSubEffect(Effect sub, Effect sup) {
    return switch (sup) {
      case ASYNC -> true; // 所有效果都是 ASYNC 的子效果
      case IO -> sub != Effect.ASYNC; // PURE、CPU、IO 是 IO 的子效果
      case CPU -> sub == Effect.PURE || sub == Effect.CPU; // PURE 和 CPU 是 CPU 的子效果
      case PURE -> sub == Effect.PURE; // 只有 PURE 是 PURE 的子效果
    };
  }

  /**
   * 效果比较（用于诊断消息）
   *
   * @param e1 效果1
   * @param e2 效果2
   * @return 0 表示相等，负数表示 e1 < e2，正数表示 e1 > e2
   */
  public int compareEffects(Effect e1, Effect e2) {
    return Integer.compare(e1.ordinal(), e2.ordinal());
  }
}
