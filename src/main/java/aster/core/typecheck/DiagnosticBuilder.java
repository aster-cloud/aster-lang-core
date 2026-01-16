package aster.core.typecheck;

import aster.core.ir.CoreModel.Origin;
import aster.core.ir.CoreModel.Type;
import aster.core.typecheck.model.Diagnostic;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断构建器（Diagnostic Builder）
 * <p>
 * 收集类型检查过程中产生的错误、警告和提示信息，支持参数化错误消息。
 * 完全复制 TypeScript 版本的诊断系统设计，确保错误报告一致。
 * <p>
 * 核心功能：
 * - 错误收集：支持 ERROR、WARNING、INFO 三种级别
 * - 消息格式化：支持参数占位符替换（如 {expected}、{actual}）
 * - 便捷方法：提供常见错误的快捷构建方法（如 typeMismatch、undefinedVariable）
 * - 批量查询：支持获取所有诊断、检查是否有错误等
 */
public final class DiagnosticBuilder {

  // ========== 字段 ==========

  private final List<Diagnostic> diagnostics = new ArrayList<>();

  // ========== 占位符替换正则 ==========

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)}");

  // ========== 公共方法：添加诊断 ==========

  /**
   * 添加错误
   *
   * @param code   错误码
   * @param span   源码位置
   * @param params 参数映射（用于消息模板占位符替换）
   * @return this（支持链式调用）
   */
  public DiagnosticBuilder error(ErrorCode code, Optional<Origin> span, Map<String, Object> params) {
    return add(code, span, params, Diagnostic.Severity.ERROR);
  }

  /**
   * 添加警告
   *
   * @param code   错误码
   * @param span   源码位置
   * @param params 参数映射
   * @return this
   */
  public DiagnosticBuilder warning(ErrorCode code, Optional<Origin> span, Map<String, Object> params) {
    return add(code, span, params, Diagnostic.Severity.WARNING);
  }

  /**
   * 添加提示
   *
   * @param code   错误码
   * @param span   源码位置
   * @param params 参数映射
   * @return this
   */
  public DiagnosticBuilder info(ErrorCode code, Optional<Origin> span, Map<String, Object> params) {
    return add(code, span, params, Diagnostic.Severity.INFO);
  }

  // ========== 便捷方法：常见错误 ==========

  /**
   * 类型不匹配错误
   *
   * @param expected 期望类型
   * @param actual   实际类型
   * @param span     源码位置
   * @return this
   */
  public DiagnosticBuilder typeMismatch(Type expected, Type actual, Optional<Origin> span) {
    var params = Map.<String, Object>of(
      "expected", TypeSystem.format(expected),
      "actual", TypeSystem.format(actual)
    );
    return error(ErrorCode.TYPE_MISMATCH, span, params);
  }

  /**
   * 未定义变量错误
   *
   * @param name 变量名
   * @param span 源码位置
   * @return this
   */
  public DiagnosticBuilder undefinedVariable(String name, Optional<Origin> span) {
    return error(ErrorCode.UNDEFINED_VARIABLE, span, Map.of("name", name));
  }

  /**
   * 效果违反错误
   * <p>
   * 分析声明的效果与推断的效果差异，生成对应的错误/警告。
   *
   * @param declared 声明的效果列表
   * @param inferred 推断的效果列表
   * @param span     源码位置
   * @return this
   */
  public DiagnosticBuilder effectViolation(List<String> declared, List<String> inferred, Optional<Origin> span) {
    var declaredSet = new HashSet<>(declared);
    var inferredSet = new HashSet<>(inferred);

    // 缺失的效果（推断中有但声明中没有）
    var missing = new ArrayList<String>();
    for (var effect : inferred) {
      if (!declaredSet.contains(effect)) {
        missing.add(effect);
      }
    }

    // 多余的效果（声明中有但推断中没有）
    var redundant = new ArrayList<String>();
    for (var effect : declared) {
      if (!inferredSet.contains(effect)) {
        redundant.add(effect);
      }
    }

    // 为每个缺失的效果生成错误
    for (var effect : missing) {
      switch (effect) {
        case "io" -> error(ErrorCode.EFF_INFER_MISSING_IO, span, Map.of("func", ""));
        case "cpu" -> error(ErrorCode.EFF_INFER_MISSING_CPU, span, Map.of("func", ""));
        default -> error(ErrorCode.EFF_CAP_MISSING, span, Map.of(
          "func", "",
          "cap", effect,
          "declared", String.join(", ", declared)
        ));
      }
    }

    // 为每个多余的效果生成警告/提示
    for (var effect : redundant) {
      switch (effect) {
        case "io" -> warning(ErrorCode.EFF_INFER_REDUNDANT_IO, span, Map.of("func", ""));
        case "cpu" -> {
          if (declaredSet.contains("io")) {
            warning(ErrorCode.EFF_INFER_REDUNDANT_CPU_WITH_IO, span, Map.of("func", ""));
          } else {
            warning(ErrorCode.EFF_INFER_REDUNDANT_CPU, span, Map.of("func", ""));
          }
        }
        default -> info(ErrorCode.EFF_CAP_SUPERFLUOUS, span, Map.of("func", "", "cap", effect));
      }
    }

    return this;
  }

  // ========== 查询方法 ==========

  /**
   * 获取所有诊断（返回不可变副本）
   */
  public List<Diagnostic> getDiagnostics() {
    return List.copyOf(diagnostics);
  }

  /**
   * 检查是否存在错误
   */
  public boolean hasErrors() {
    return diagnostics.stream()
      .anyMatch(diag -> diag.severity() == Diagnostic.Severity.ERROR);
  }

  /**
   * 清空所有诊断
   */
  public void clear() {
    diagnostics.clear();
  }

  /**
   * 获取错误数量
   */
  public int getErrorCount() {
    return (int) diagnostics.stream()
      .filter(diag -> diag.severity() == Diagnostic.Severity.ERROR)
      .count();
  }

  /**
   * 获取警告数量
   */
  public int getWarningCount() {
    return (int) diagnostics.stream()
      .filter(diag -> diag.severity() == Diagnostic.Severity.WARNING)
      .count();
  }

  // ========== 私有辅助方法 ==========

  /**
   * 添加诊断（内部实现）
   *
   * @param code             错误码
   * @param span             源码位置
   * @param params           参数映射
   * @param severityOverride 严重级别覆写（如果为 null 则使用 ErrorCode 的默认级别）
   * @return this
   */
  private DiagnosticBuilder add(
    ErrorCode code,
    Optional<Origin> span,
    Map<String, Object> params,
    Diagnostic.Severity severityOverride
  ) {
    // 获取消息模板并格式化
    var template = code.messageTemplate();
    var message = formatMessage(template, params);

    // 确定严重级别（优先使用覆写值）
    var severity = severityOverride != null ? severityOverride : convertSeverity(code.severity());

    // 获取帮助文本
    var help = code.help() != null && !code.help().isEmpty()
      ? Optional.of(code.help())
      : Optional.<String>empty();

    // 构建诊断
    var diagnostic = new Diagnostic(
      severity,
      code,
      message,
      span,
      help,
      params.isEmpty() ? Map.of() : Map.copyOf(params)
    );

    diagnostics.add(diagnostic);
    return this;
  }

  /**
   * 格式化消息模板（替换占位符）
   * <p>
   * 支持 {key} 格式的占位符，替换为 params 中的对应值。
   * 如果值为 null 或不存在，则保留原占位符。
   * 如果值为数组，则用逗号连接。
   *
   * @param template 消息模板
   * @param params   参数映射
   * @return 格式化后的消息
   */
  private String formatMessage(String template, Map<String, Object> params) {
    var matcher = PLACEHOLDER_PATTERN.matcher(template);
    var result = new StringBuilder();

    while (matcher.find()) {
      var key = matcher.group(1);
      var value = params.get(key);

      String replacement;
      if (value == null) {
        replacement = "{" + key + "}"; // 保留原占位符
      } else if (value instanceof List<?> list) {
        replacement = String.join(", ", list.stream().map(Object::toString).toList());
      } else if (value instanceof Object[] array) {
        replacement = String.join(", ", Arrays.stream(array).map(Object::toString).toList());
      } else {
        replacement = value.toString();
      }

      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /**
   * 转换 ErrorCode.Severity 到 Diagnostic.Severity
   */
  private Diagnostic.Severity convertSeverity(ErrorCode.Severity errorCodeSeverity) {
    return switch (errorCodeSeverity) {
      case ERROR -> Diagnostic.Severity.ERROR;
      case WARNING -> Diagnostic.Severity.WARNING;
      case INFO -> Diagnostic.Severity.INFO;
    };
  }
}
