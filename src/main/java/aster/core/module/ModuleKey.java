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
   * 生成合并后顶层符号的前缀，例如 {@code risk.Scoring v2 -> risk_Scoring_v2__}。
   * <p>
   * 编码必须无碰撞：朴素的 {@code name.replace('.', '_')} 会让 {@code a.b} 与
   * {@code a_b} 折叠到同一前缀。这里先把已有的 {@code _} 转义为 {@code __}，再把
   * {@code .} 编码为单个 {@code _}；因此 {@code _} 永远成对出现于“原始下划线”，
   * 单个 {@code _} 永远来自“点”，二者不会混淆——
   * {@code a.b -> a_b_v1__}，{@code a_b -> a__b_v1__}（不同前缀）。
   */
  public String mangle() {
    String encoded = moduleName.replace("_", "__").replace('.', '_');
    return encoded + "_v" + version + "__";
  }
}
