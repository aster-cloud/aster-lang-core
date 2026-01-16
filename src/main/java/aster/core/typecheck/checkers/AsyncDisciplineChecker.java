package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.Origin;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.ErrorCode;

import java.util.*;

/**
 * 异步纪律检查器（Async Discipline Checker）
 * <p>
 * 验证 Start/Wait 异步控制流的正确性，确保异步任务不会泄漏。
 * <p>
 * 核心规则：
 * - Start 启动的异步任务必须被 Wait 等待
 * - Wait 等待的任务必须先被 Start 启动
 * - 同一个任务不能被重复启动或等待
 * - 分支路径的异步任务状态必须一致
 */
public final class AsyncDisciplineChecker {

  // ========== 字段 ==========

  private final DiagnosticBuilder diagnostics;

  // ========== 构造器 ==========

  public AsyncDisciplineChecker(DiagnosticBuilder diagnostics) {
    if (diagnostics == null) {
      throw new IllegalArgumentException("diagnostics cannot be null");
    }
    this.diagnostics = diagnostics;
  }

  // ========== 公共 API ==========

  /**
   * 检查函数的异步纪律
   *
   * @param func 函数声明
   */
  public void checkFunction(CoreModel.Func func) {
    if (func.body == null) {
      return;
    }

    var analysis = analyzeAsync(func.body);

    // 检查未 Wait 的任务
    for (var entry : analysis.starts.entrySet()) {
      var taskName = entry.getKey();
      var startSpans = entry.getValue();

      if (!analysis.waits.containsKey(taskName)) {
        // 启动但从未等待
        diagnostics.error(
          ErrorCode.ASYNC_START_NOT_WAITED,
          startSpans.isEmpty() ? Optional.empty() : Optional.of(startSpans.get(0)),
          Map.of("task", taskName)
        );
      }
    }

    // 检查 Wait 了未启动的任务
    for (var entry : analysis.waits.entrySet()) {
      var taskName = entry.getKey();
      var waitSpans = entry.getValue();

      if (!analysis.starts.containsKey(taskName)) {
        // 等待但从未启动
        diagnostics.error(
          ErrorCode.ASYNC_WAIT_NOT_STARTED,
          waitSpans.isEmpty() ? Optional.empty() : Optional.of(waitSpans.get(0)),
          Map.of("task", taskName)
        );
      }
    }

    // 检查重复启动
    for (var entry : analysis.starts.entrySet()) {
      var taskName = entry.getKey();
      var startSpans = entry.getValue();

      if (startSpans.size() > 1) {
        diagnostics.error(
          ErrorCode.ASYNC_DUPLICATE_START,
          Optional.of(startSpans.get(1)), // 第二次出现的位置
          Map.of("task", taskName, "count", startSpans.size())
        );
      }
    }

    // 检查重复等待
    for (var entry : analysis.waits.entrySet()) {
      var taskName = entry.getKey();
      var waitSpans = entry.getValue();

      if (waitSpans.size() > 1) {
        diagnostics.warning(
          ErrorCode.ASYNC_DUPLICATE_WAIT,
          Optional.of(waitSpans.get(1)), // 第二次出现的位置
          Map.of("task", taskName, "count", waitSpans.size())
        );
      }
    }
  }

  // ========== 私有辅助方法 ==========

  /**
   * 分析异步任务结果
   */
  private record AsyncAnalysis(
    Map<String, List<Origin>> starts,  // 任务名 -> Start 位置列表
    Map<String, List<Origin>> waits    // 任务名 -> Wait 位置列表
  ) {
  }

  /**
   * 分析代码块中的异步操作
   */
  private AsyncAnalysis analyzeAsync(CoreModel.Block block) {
    var starts = new HashMap<String, List<Origin>>();
    var waits = new HashMap<String, List<Origin>>();

    analyzeStatements(block.statements, starts, waits);

    return new AsyncAnalysis(starts, waits);
  }

  /**
   * 递归分析语句列表
   */
  private void analyzeStatements(
    List<CoreModel.Stmt> statements,
    Map<String, List<Origin>> starts,
    Map<String, List<Origin>> waits
  ) {
    for (var stmt : statements) {
      analyzeStatement(stmt, starts, waits);
    }
  }

  /**
   * 分析单个语句
   */
  private void analyzeStatement(
    CoreModel.Stmt stmt,
    Map<String, List<Origin>> starts,
    Map<String, List<Origin>> waits
  ) {
    switch (stmt) {
      case CoreModel.Start start -> {
        // 收集 Start 语句
        var taskName = start.name;
        if (taskName != null && !taskName.isEmpty()) {
          starts.computeIfAbsent(taskName, k -> new ArrayList<>())
            .add(start.origin);
        }
      }

      case CoreModel.Wait wait -> {
        // 收集 Wait 语句
        for (var taskName : wait.names) {
          waits.computeIfAbsent(taskName, k -> new ArrayList<>())
            .add(wait.origin);
        }
      }

      case CoreModel.Block block -> {
        analyzeStatements(block.statements, starts, waits);
      }

      case CoreModel.Scope scope -> {
        analyzeStatements(scope.statements, starts, waits);
      }

      case CoreModel.If ifStmt -> {
        // 分析 then 分支
        if (ifStmt.thenBlock != null) {
          analyzeStatements(ifStmt.thenBlock.statements, starts, waits);
        }
        // 分析 else 分支
        if (ifStmt.elseBlock != null) {
          analyzeStatements(ifStmt.elseBlock.statements, starts, waits);
        }
      }

      case CoreModel.Match match -> {
        // 分析所有 match 分支
        for (var kase : match.cases) {
          analyzeStatement(kase.body, starts, waits);
        }
      }

      default -> {
        // 其他语句不包含异步操作
      }
    }
  }

  /**
   * 检查分支路径的异步一致性
   * <p>
   * 确保 if/match 的不同分支具有一致的异步任务状态。
   *
   * @param thenBranch then 分支语句
   * @param elseBranch else 分支语句（可选）
   */
  public void checkBranchAsyncConsistency(
    List<CoreModel.Stmt> thenBranch,
    Optional<List<CoreModel.Stmt>> elseBranch
  ) {
    var thenStarts = collectStartedTasks(thenBranch);
    var thenWaits = collectWaitedTasks(thenBranch);

    if (elseBranch.isPresent()) {
      var elseStarts = collectStartedTasks(elseBranch.get());
      var elseWaits = collectWaitedTasks(elseBranch.get());

      // 检查启动的任务是否一致
      if (!thenStarts.equals(elseStarts)) {
        var onlyInThen = new HashSet<>(thenStarts);
        onlyInThen.removeAll(elseStarts);
        var onlyInElse = new HashSet<>(elseStarts);
        onlyInElse.removeAll(thenStarts);

        if (!onlyInThen.isEmpty() || !onlyInElse.isEmpty()) {
          diagnostics.warning(
            ErrorCode.ASYNC_START_NOT_WAITED, // 使用现有错误码
            Optional.empty(),
            Map.of(
              "thenOnly", String.join(", ", onlyInThen),
              "elseOnly", String.join(", ", onlyInElse)
            )
          );
        }
      }

      // 检查等待的任务是否一致
      if (!thenWaits.equals(elseWaits)) {
        var onlyInThen = new HashSet<>(thenWaits);
        onlyInThen.removeAll(elseWaits);
        var onlyInElse = new HashSet<>(elseWaits);
        onlyInElse.removeAll(thenWaits);

        if (!onlyInThen.isEmpty() || !onlyInElse.isEmpty()) {
          diagnostics.warning(
            ErrorCode.ASYNC_WAIT_NOT_STARTED, // 使用现有错误码
            Optional.empty(),
            Map.of(
              "thenOnly", String.join(", ", onlyInThen),
              "elseOnly", String.join(", ", onlyInElse)
            )
          );
        }
      }
    }
  }

  /**
   * 收集语句列表中启动的所有任务名
   */
  private Set<String> collectStartedTasks(List<CoreModel.Stmt> statements) {
    var tasks = new HashSet<String>();
    var starts = new HashMap<String, List<Origin>>();
    var waits = new HashMap<String, List<Origin>>();

    analyzeStatements(statements, starts, waits);
    tasks.addAll(starts.keySet());

    return tasks;
  }

  /**
   * 收集语句列表中等待的所有任务名
   */
  private Set<String> collectWaitedTasks(List<CoreModel.Stmt> statements) {
    var tasks = new HashSet<String>();
    var starts = new HashMap<String, List<Origin>>();
    var waits = new HashMap<String, List<Origin>>();

    analyzeStatements(statements, starts, waits);
    tasks.addAll(waits.keySet());

    return tasks;
  }
}
