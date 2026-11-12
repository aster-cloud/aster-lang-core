package aster.core.parser;

/**
 * 类型嵌套测试用的源码形态（aster-lang-core#250）。{@code depth} 是类型解析的进入次数（含叶子类型名），
 * 与 {@link AsterCustomLexer#MAX_TYPE_NESTING_DEPTH} 和 aster-lang-ts {@code MAX_TYPE_RECURSION_DEPTH} 同一单位。
 */
final class TypeNestingShapes {

  private TypeNestingShapes() {
  }

  // 形态：list 链、option 链、Map 值 / 键 / 键值交替、result 的成功 / 失败分量、函数类型返回值。
  // 不含「result 成功 / 失败分量交替」：那种写法在 core 的 ANTLR 预测里耗时随深度指数增长（32 层约 9 秒），
  // 与本守卫无关，另案处理。
  static String type(String form, int depth) {
    int k = depth - 1;
    return switch (form) {
      case "list" -> "list of ".repeat(k) + "Int";
      case "option" -> "option of ".repeat(k) + "Int";
      case "mapval" -> "Map Text to ".repeat(k) + "Int";
      case "mapkey" -> "Map ".repeat(k) + "Int" + " to Int".repeat(k);
      case "mapalt" -> {
        String t = "Int";
        for (int i = 1; i <= k; i++) {
          t = i % 2 == 1 ? "Map " + t + " to Int" : "Map Text to " + t;
        }
        yield t;
      }
      case "resultok" -> "result of ".repeat(k) + "Int" + " and Text".repeat(k);
      case "resulterr" -> "result of Int and ".repeat(k) + "Text";
      case "func" -> "(Int) -> ".repeat(k) + "Int";
      default -> throw new IllegalArgumentException(form);
    };
  }

  /** 类型放在参数位置，后接 {@code , produce}（逗号紧随类型是 ANTLR 预测最贵的上下文）。 */
  static String source(String form, int depth) {
    return "Module probe.\n\nRule f given x as " + type(form, depth) + ", produce Int:\n  Return 1.\n";
  }
}
