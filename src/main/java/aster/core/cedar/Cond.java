package aster.core.cedar;

/**
 * 条件 c 的两个 Cedar 表达式（ADR 0047 §3）：{@code t} 为「无错求值且为真」，{@code f} 为「无错求值且为假」。
 * 两者都已自带括号或取反前缀，可直接以 {@code &&} 拼接。
 */
public record Cond(String t, String f) {

  /** 交换 T/F，对应 {@code not}。 */
  public Cond negate() {
    return new Cond(f, t);
  }
}
