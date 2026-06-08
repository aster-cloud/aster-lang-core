package aster.core.module;

/**
 * 已解析模块的稳定标识。
 *
 * @param moduleName 模块全名
 * @param version    钉住版本
 */
public record ModuleKey(String moduleName, int version) {

  public ModuleKey {
    if (moduleName == null || moduleName.isBlank()) {
      throw new IllegalArgumentException("moduleName must not be blank");
    }
  }

  /**
   * 生成合并后顶层符号的前缀，例如 risk.Scoring v2 -> risk_Scoring_v2__。
   */
  public String mangle() {
    return moduleName.replace('.', '_') + "_v" + version + "__";
  }
}
