package aster.core.typecheck;

/**
 * 内置类型名称常量
 * <p>
 * 集中管理 Aster 语言的内置类型名称，避免在代码中分散使用字符串字面量。
 * 这些常量用于类型推断、类型检查和代码生成。
 * <p>
 * 设计原则：
 * - 所有内置类型名称都在此处定义
 * - 提供类型名判断的辅助方法
 * - 维护向后兼容的类型别名映射
 */
public final class BuiltinTypes {

  // ========== 基础类型 ==========

  /** 整数类型 */
  public static final String INT = "Int";

  /** 布尔类型 */
  public static final String BOOL = "Bool";

  /** 长整数类型 */
  public static final String LONG = "Long";

  /** 双精度浮点数类型 */
  public static final String DOUBLE = "Double";

  /** 字符串类型（标准名称） */
  public static final String STRING = "String";

  /** 文本类型（历史遗留别名，向后兼容） */
  public static final String TEXT = "Text";

  /** 数字类型（映射为 Double） */
  public static final String NUMBER = "Number";

  // ========== 辅助方法 ==========

  /**
   * 判断给定类型名是否为字符串类型（包括 String 和 Text）
   *
   * @param typeName 类型名称
   * @return 如果是字符串类型返回 true
   */
  public static boolean isStringType(String typeName) {
    return STRING.equals(typeName) || TEXT.equals(typeName);
  }

  /**
   * 判断给定类型名是否为基础类型
   *
   * @param typeName 类型名称
   * @return 如果是基础类型返回 true
   */
  public static boolean isPrimitiveType(String typeName) {
    return INT.equals(typeName)
        || BOOL.equals(typeName)
        || LONG.equals(typeName)
        || DOUBLE.equals(typeName)
        || isStringType(typeName)
        || NUMBER.equals(typeName);
  }

  /**
   * 获取字符串类型的标准名称（将 Text 规范化为 String）
   *
   * @param typeName 类型名称
   * @return 如果是字符串类型返回 String，否则返回原值
   */
  public static String normalizeStringType(String typeName) {
    return TEXT.equals(typeName) ? STRING : typeName;
  }

  // 禁止实例化
  private BuiltinTypes() {
    throw new AssertionError("BuiltinTypes 是工具类，不应被实例化");
  }
}
