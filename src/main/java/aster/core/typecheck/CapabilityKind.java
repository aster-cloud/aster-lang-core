package aster.core.typecheck;

import java.util.Locale;
import java.util.Optional;

/**
 * Capability 枚举，定义编译期识别的资源类别。
 * <p>
 * 该枚举与 TypeScript 端保持一致，便于后续在 effectCaps 字段中序列化。
 */
public enum CapabilityKind {
  HTTP("Http"),
  SQL("Sql"),
  TIME("Time"),
  FILES("Files"),
  SECRETS("Secrets"),
  AI_MODEL("AiModel"),
  CPU("Cpu"),
  PAYMENT("Payment"),
  INVENTORY("Inventory");

  private final String displayName;

  CapabilityKind(String displayName) {
    this.displayName = displayName;
  }

  /**
   * 获取对外展示的能力名称（与 CNL/TS 版本一致）。
   */
  public String displayName() {
    return displayName;
  }

  /**
   * 根据 displayName 或枚举名称解析 CapabilityKind。
   *
   * @param label 来自 effectCaps 的能力名称（例如 Http、Sql、AiModel）
   */
  public static Optional<CapabilityKind> fromLabel(String label) {
    if (label == null) {
      return Optional.empty();
    }
    var trimmed = label.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    var canonical = trimmed.replace('-', '_').toUpperCase(Locale.ROOT);
    var canonicalNoUnderscore = canonical.replace("_", "");
    for (var kind : values()) {
      if (kind.displayName.equalsIgnoreCase(trimmed)) {
        return Optional.of(kind);
      }
      var enumName = kind.name();
      if (enumName.equals(canonical) || enumName.replace("_", "").equals(canonicalNoUnderscore)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }

  @Override
  public String toString() {
    return displayName;
  }
}
