package aster.core.cedar;

/** Cedar 文本工具：字符串字面量转义与 Aster 命名空间下的实体引用。 */
public final class CedarText {

  private CedarText() {}

  /** 返回带双引号的 Cedar 字符串字面量；控制字符转义，其余字符（含 CJK）原样保留。 */
  public static String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    s.chars().forEach(c -> sb.append(escape((char) c)));
    return sb.append('"').toString();
  }

  /** 实体引用 {@code Aster::<type>::"<id>"}，id 按字符串字面量转义。 */
  public static String entity(String type, String id) {
    return "Aster::" + type + "::" + quote(id);
  }

  // 单字符转义：先处理具名转义，其余 0x00–0x1F 与 0x7F 用 Cedar 的 u{小写十六进制} 转义
  private static String escape(char c) {
    return switch (c) {
      case '\\' -> "\\\\";
      case '"' -> "\\\"";
      case '\n' -> "\\n";
      case '\r' -> "\\r";
      case '\t' -> "\\t";
      case '\0' -> "\\0";
      default -> (c < 0x20 || c == 0x7F) ? "\\u{" + Integer.toHexString(c) + "}" : String.valueOf(c);
    };
  }
}
