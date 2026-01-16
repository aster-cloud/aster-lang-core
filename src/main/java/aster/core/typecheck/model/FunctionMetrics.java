package aster.core.typecheck.model;

import java.util.Optional;

/**
 * 函数静态分析指标
 * <p>
 * 提供函数复杂度和结构的量化指标，用于代码质量评估和维护辅助。
 * <p>
 * 核心指标：
 * - 函数长度：语句数量、代码行数
 * - 返回路径：return 语句数量、分支数量
 * - 嵌套复杂度：最大嵌套深度
 * <p>
 * 使用场景：
 * - 识别过于复杂的函数（statementCount > 50, maxNestingDepth > 3）
 * - 检测多返回路径函数（returnCount > 5）
 * - 辅助重构决策（提供量化依据）
 */
public record FunctionMetrics(
  /*
   * 函数名称
   */
  String functionName,

  /*
   * 语句总数（包括所有嵌套语句）
   * <p>
   * 计算规则：递归统计所有 Statement 节点
   * <p>
   * 建议阈值：<= 50（超过需要考虑拆分）
   */
  int statementCount,

  /*
   * return 语句数量
   * <p>
   * 多个返回路径增加函数复杂度，但某些情况下是合理的（早期返回优化）
   * <p>
   * 建议阈值：<= 5（超过需要审查是否过于复杂）
   */
  int returnCount,

  /*
    分支数量（if 语句 + match case 数量）
    <p>
    反映控制流复杂度
    <p>
    建议阈值：<= 10（超过需要考虑使用多态或策略模式）
   */
  int branchCount,

  /*
   * 最大嵌套深度
   * <p>
   * 从函数体开始计算，每进入一层 Block/If/Match 增加 1
   * <p>
   * 建议阈值：<= 3（超过严重影响可读性，必须重构）
   */
  int maxNestingDepth,

  /*
   * 函数体行数（可选，需要 origin 信息）
   * <p>
   * 如果函数体缺少 origin 信息则为 empty
   * <p>
   * 建议阈值：<= 100 行（超过需要拆分）
   */
  Optional<Integer> bodyLineCount
) {

  /**
   * 创建指标（不含行数信息）
   */
  public FunctionMetrics(
    String functionName,
    int statementCount,
    int returnCount,
    int branchCount,
    int maxNestingDepth
  ) {
    this(functionName, statementCount, returnCount, branchCount, maxNestingDepth, Optional.empty());
  }

  /**
   * 判断函数是否复杂（任一指标超过阈值）
   * <p>
   * 复杂度阈值：
   * - 语句数 > 50
   * - 返回路径 > 5
   * - 分支数 > 10
   * - 嵌套深度 > 3
   */
  public boolean isComplex() {
    return statementCount > 50
      || returnCount > 5
      || branchCount > 10
      || maxNestingDepth > 3;
  }

  /**
   * 判断函数是否过长（行数 > 100）
   * <p>
   * 如果没有行数信息则返回 false
   */
  public boolean isTooLong() {
    return bodyLineCount.map(lines -> lines > 100).orElse(false);
  }

  /**
   * 获取复杂度评分（0-100，越高越复杂）
   * <p>
   * 评分规则：
   * - 语句数：每 10 条 +10 分
   * - 返回路径：每个 +5 分
   * - 分支数：每个 +3 分
   * - 嵌套深度：每层 +15 分
   * <p>
   * 评分参考：
   * - 0-30: 简单
   * - 31-60: 中等
   * - 61-100: 复杂（需要重构）
   */
  public int complexityScore() {
    int score = 0;
    score += (statementCount / 10) * 10;  // 语句数贡献
    score += returnCount * 5;              // 返回路径贡献
    score += branchCount * 3;              // 分支贡献
    score += maxNestingDepth * 15;         // 嵌套深度贡献
    return Math.min(score, 100);           // 最高 100 分
  }

  /**
   * 获取复杂度等级描述
   */
  public String complexityLevel() {
    int score = complexityScore();
    if (score <= 30) return "简单";
    if (score <= 60) return "中等";
    return "复杂";
  }

  @Override
  public String toString() {
    var sb = new StringBuilder();
    sb.append("FunctionMetrics[").append(functionName).append("]\n");
    sb.append("  语句数: ").append(statementCount).append("\n");
    sb.append("  返回路径: ").append(returnCount).append("\n");
    sb.append("  分支数: ").append(branchCount).append("\n");
    sb.append("  最大嵌套深度: ").append(maxNestingDepth).append("\n");
    bodyLineCount.ifPresent(lines -> sb.append("  函数体行数: ").append(lines).append("\n"));
    sb.append("  复杂度评分: ").append(complexityScore()).append(" (").append(complexityLevel()).append(")");
    return sb.toString();
  }
}
