package aster.core.capability;

import java.util.Locale;
import java.util.Optional;

/**
 * Capability 枚举，定义编译期识别的资源类别。
 * <p>
 * 该枚举与 TypeScript 端保持一致，便于后续在 effectCaps 字段中序列化。
 */
public enum CapabilityKind {
  HTTP("Http", EffectClass.IO),
  // 与 TS 端 capability taxonomy 对齐（shared/capabilities.json 单源）：
  // NETWORK/CRYPTO/PROCESS 为 TS 后加（commit 063ff6d），Java 补齐消除双引擎枚举 drift。
  NETWORK("Network", EffectClass.IO),
  SQL("Sql", EffectClass.IO),
  TIME("Time", EffectClass.IO),
  FILES("Files", EffectClass.IO),
  SECRETS("Secrets", EffectClass.IO),
  // CRYPTO 是 cpu-class：本地哈希/签名/加密常为 CPU 密集（与 TS CAPABILITY_EFFECT_CLASS 对齐）；
  // 远程 KMS 应额外用 Network/Secrets 或显式 IO 表达。缺声明报 MISSING_CPU 非 MISSING_IO。
  CRYPTO("Crypto", EffectClass.CPU),
  PROCESS("Process", EffectClass.IO),
  AI_MODEL("AiModel", EffectClass.IO),
  CPU("Cpu", EffectClass.CPU),
  PAYMENT("Payment", EffectClass.IO),
  INVENTORY("Inventory", EffectClass.IO);

  /**
   * Capability 的 effect 分类，是 capability taxonomy 的一部分（单源 shared/capabilities.json
   * 的 class 字段），由 CapabilityParityTest 守门。放进枚举而非 CapabilityChecker 局部 set，
   * 避免 workflow/manifest/runtime 等多处需要分类时各自复制导致 drift。
   */
  public enum EffectClass {
    /** 外部交互，需 @io。 */
    IO,
    /** 本地计算密集，需 @cpu 或 @io（如 CRYPTO 本地密码学）。 */
    CPU
  }

  private final String displayName;
  private final EffectClass effectClass;

  CapabilityKind(String displayName, EffectClass effectClass) {
    this.displayName = displayName;
    this.effectClass = effectClass;
  }

  /**
   * 获取对外展示的能力名称（与 CNL/TS 版本一致）。
   */
  public String displayName() {
    return displayName;
  }

  /** capability 的 effect 分类（io/cpu）。 */
  public EffectClass effectClass() {
    return effectClass;
  }

  /** 是否 cpu-class（本地计算密集，需 @cpu 不强制 @io）。 */
  public boolean isCpuClass() {
    return effectClass == EffectClass.CPU;
  }

  /** 是否 io-class（外部交互，需 @io）。 */
  public boolean isIoClass() {
    return effectClass == EffectClass.IO;
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
