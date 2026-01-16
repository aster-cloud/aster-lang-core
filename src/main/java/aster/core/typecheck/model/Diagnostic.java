package aster.core.typecheck.model;

import aster.core.ir.CoreModel.Origin;
import aster.core.typecheck.ErrorCode;

import java.util.Map;
import java.util.Optional;

/**
 * 类型检查诊断信息
 * <p>
 * 记录类型检查过程中发现的错误、警告和提示信息。
 * 完全复制 TypeScript 版本设计，确保诊断输出的一致性。
 *
 * @param severity 严重级别（ERROR、WARNING、INFO）
 * @param code     错误码（来自 {@link ErrorCode}）
 * @param message  格式化后的错误消息
 * @param span     源码位置信息（可选）
 * @param help     辅助提示信息（可选）
 * @param data     附加数据（用于错误消息参数化）
 */
public record Diagnostic(
  Severity severity,
  ErrorCode code,
  String message,
  Optional<Origin> span,
  Optional<String> help,
  Map<String, Object> data
) {

  /**
   * 诊断严重级别
   */
  public enum Severity {
    /** 错误：阻止编译继续 */
    ERROR,
    /** 警告：不阻止编译但需要注意 */
    WARNING,
    /** 提示：建议性信息 */
    INFO
  }

  /**
   * 创建紧凑构造器，确保非空约束
   */
  public Diagnostic {
    if (severity == null) {
      throw new IllegalArgumentException("severity cannot be null");
    }
    if (code == null) {
      throw new IllegalArgumentException("code cannot be null");
    }
    if (message == null) {
      throw new IllegalArgumentException("message cannot be null");
    }
    if (span == null) {
      span = Optional.empty();
    }
    if (help == null) {
      help = Optional.empty();
    }
    if (data == null) {
      data = Map.of();
    }
  }
}
