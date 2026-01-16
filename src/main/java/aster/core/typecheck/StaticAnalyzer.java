package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.typecheck.model.FunctionMetrics;

import java.util.Optional;

/**
 * 静态分析器
 * <p>
 * 提供函数级别的静态分析指标，用于代码质量评估和维护辅助。
 * <p>
 * 核心功能：
 * - 计算函数复杂度指标（语句数、返回路径、分支数、嵌套深度）
 * - 提供量化的重构建议依据
 * - 辅助识别过于复杂的函数
 * <p>
 * 使用示例：
 * <pre>
 * var analyzer = new StaticAnalyzer();
 * var metrics = analyzer.analyzeFunctionMetrics(func);
 * if (metrics.isComplex()) {
 *   System.out.println("函数过于复杂，建议重构");
 * }
 * </pre>
 */
public final class StaticAnalyzer {

  /**
   * 分析函数的静态指标
   *
   * @param func 待分析的函数
   * @return 函数指标对象
   */
  public FunctionMetrics analyzeFunctionMetrics(CoreModel.Func func) {
    if (func == null) {
      throw new IllegalArgumentException("Function cannot be null");
    }

    var functionName = func.name != null ? func.name : "<anonymous>";
    var statementCount = 0;
    var returnCount = 0;
    var branchCount = 0;
    var maxNestingDepth = 0;
    Optional<Integer> bodyLineCount = Optional.empty();

    // 分析函数体（如果存在）
    if (func.body != null) {
      statementCount = countStatements(func.body);
      returnCount = countReturns(func.body);
      branchCount = countBranches(func.body);
      maxNestingDepth = calculateMaxNestingDepth(func.body, 0);
      bodyLineCount = calculateBodyLineCount(func.body);
    }

    return new FunctionMetrics(
      functionName,
      statementCount,
      returnCount,
      branchCount,
      maxNestingDepth,
      bodyLineCount
    );
  }

  /**
   * 递归统计语句数量
   */
  private int countStatements(CoreModel.Block block) {
    if (block == null || block.statements == null) {
      return 0;
    }

    int count = block.statements.size();

    // 递归统计嵌套语句
    for (var stmt : block.statements) {
      count += countStatementsInStmt(stmt);
    }

    return count;
  }

  /**
   * 统计单个语句内的嵌套语句数
   */
  private int countStatementsInStmt(CoreModel.Stmt stmt) {
    return switch (stmt) {
      case CoreModel.If ifStmt -> {
        int count = 0;
        if (ifStmt.thenBlock != null) {
          count += countStatements(ifStmt.thenBlock);
        }
        if (ifStmt.elseBlock != null) {
          count += countStatements(ifStmt.elseBlock);
        }
        yield count;
      }
      case CoreModel.Match match -> {
        int count = 0;
        if (match.cases != null) {
          for (var caseStmt : match.cases) {
            if (caseStmt.body instanceof CoreModel.Block block) {
              count += countStatements(block);
            } else {
              count += 1; // 单语句 case
            }
          }
        }
        yield count;
      }
      case CoreModel.Scope scope -> {
        if (scope.statements != null) {
          yield scope.statements.size() + scope.statements.stream()
            .mapToInt(this::countStatementsInStmt)
            .sum();
        }
        yield 0;
      }
      default -> 0;
    };
  }

  /**
   * 统计 return 语句数量
   */
  private int countReturns(CoreModel.Block block) {
    if (block == null || block.statements == null) {
      return 0;
    }

    int count = 0;
    for (var stmt : block.statements) {
      count += countReturnsInStmt(stmt);
    }
    return count;
  }

  /**
   * 统计单个语句内的 return 数量
   */
  private int countReturnsInStmt(CoreModel.Stmt stmt) {
    return switch (stmt) {
      case CoreModel.Return ret -> 1;
      case CoreModel.If ifStmt -> {
        int count = 0;
        if (ifStmt.thenBlock != null) {
          count += countReturns(ifStmt.thenBlock);
        }
        if (ifStmt.elseBlock != null) {
          count += countReturns(ifStmt.elseBlock);
        }
        yield count;
      }
      case CoreModel.Match match -> {
        int count = 0;
        if (match.cases != null) {
          for (var caseStmt : match.cases) {
            if (caseStmt.body instanceof CoreModel.Block block) {
              count += countReturns(block);
            } else if (caseStmt.body instanceof CoreModel.Return) {
              count += 1;
            }
          }
        }
        yield count;
      }
      case CoreModel.Scope scope -> {
        if (scope.statements != null) {
          yield scope.statements.stream()
            .mapToInt(this::countReturnsInStmt)
            .sum();
        }
        yield 0;
      }
      default -> 0;
    };
  }

  /**
   * 统计分支数量（if 语句 + match case 数量）
   */
  private int countBranches(CoreModel.Block block) {
    if (block == null || block.statements == null) {
      return 0;
    }

    int count = 0;
    for (var stmt : block.statements) {
      count += countBranchesInStmt(stmt);
    }
    return count;
  }

  /**
   * 统计单个语句内的分支数
   */
  private int countBranchesInStmt(CoreModel.Stmt stmt) {
    return switch (stmt) {
      case CoreModel.If ifStmt -> {
        int count = 1; // if 本身算一个分支
        if (ifStmt.thenBlock != null) {
          count += countBranches(ifStmt.thenBlock);
        }
        if (ifStmt.elseBlock != null) {
          count += countBranches(ifStmt.elseBlock);
        }
        yield count;
      }
      case CoreModel.Match match -> {
        int count = match.cases != null ? match.cases.size() : 0;
        if (match.cases != null) {
          for (var caseStmt : match.cases) {
            if (caseStmt.body instanceof CoreModel.Block block) {
              count += countBranches(block);
            }
          }
        }
        yield count;
      }
      case CoreModel.Scope scope -> {
        if (scope.statements != null) {
          yield scope.statements.stream()
            .mapToInt(this::countBranchesInStmt)
            .sum();
        }
        yield 0;
      }
      default -> 0;
    };
  }

  /**
   * 计算最大嵌套深度
   *
   * @param block        当前代码块
   * @param currentDepth 当前深度
   * @return 最大嵌套深度
   */
  private int calculateMaxNestingDepth(CoreModel.Block block, int currentDepth) {
    if (block == null || block.statements == null) {
      return currentDepth;
    }

    int maxDepth = currentDepth;

    for (var stmt : block.statements) {
      int stmtDepth = calculateNestingDepthInStmt(stmt, currentDepth + 1);
      maxDepth = Math.max(maxDepth, stmtDepth);
    }

    return maxDepth;
  }

  /**
   * 计算单个语句的嵌套深度
   */
  private int calculateNestingDepthInStmt(CoreModel.Stmt stmt, int currentDepth) {
    return switch (stmt) {
      case CoreModel.If ifStmt -> {
        int maxDepth = currentDepth;
        if (ifStmt.thenBlock != null) {
          maxDepth = Math.max(maxDepth, calculateMaxNestingDepth(ifStmt.thenBlock, currentDepth));
        }
        if (ifStmt.elseBlock != null) {
          maxDepth = Math.max(maxDepth, calculateMaxNestingDepth(ifStmt.elseBlock, currentDepth));
        }
        yield maxDepth;
      }
      case CoreModel.Match match -> {
        int maxDepth = currentDepth;
        if (match.cases != null) {
          for (var caseStmt : match.cases) {
            if (caseStmt.body instanceof CoreModel.Block block) {
              maxDepth = Math.max(maxDepth, calculateMaxNestingDepth(block, currentDepth));
            }
          }
        }
        yield maxDepth;
      }
      case CoreModel.Scope scope -> {
        int maxDepth = currentDepth;
        if (scope.statements != null) {
          for (var nestedStmt : scope.statements) {
            maxDepth = Math.max(maxDepth, calculateNestingDepthInStmt(nestedStmt, currentDepth + 1));
          }
        }
        yield maxDepth;
      }
      default -> currentDepth;
    };
  }

  /**
   * 计算函数体行数（基于 origin 信息）
   */
  private Optional<Integer> calculateBodyLineCount(CoreModel.Block block) {
    if (block == null || block.origin == null) {
      return Optional.empty();
    }

    var origin = block.origin;
    if (origin.start == null || origin.end == null) {
      return Optional.empty();
    }

    int lineCount = origin.end.line - origin.start.line + 1;
    return Optional.of(lineCount);
  }
}
