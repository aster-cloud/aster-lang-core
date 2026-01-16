package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.Type;

import java.util.*;

/**
 * 类型系统核心算法
 * <p>
 * 提供类型相等性判断、Hindley-Milner 类型统一、子类型检查、类型展开等核心功能。
 * 完全复制 TypeScript 版本的类型系统逻辑，确保类型检查行为一致。
 * <p>
 * 核心算法：
 * - equals: 结构化类型相等性判断（支持严格/宽松模式）
 * - unify: Hindley-Milner 类型统一（用于泛型类型推断）
 * - isSubtype: 子类型关系检查（支持 Maybe/Option 互转）
 * - expand: 类型别名展开（支持递归别名检测）
 * - format: 类型格式化（用于错误消息）
 */
public final class TypeSystem {

  // ========== 常量 ==========

  /**
   * Unknown 类型（类型推断失败时的占位符）
   */
  private static final CoreModel.TypeName UNKNOWN = new CoreModel.TypeName();

  static {
    UNKNOWN.name = "Unknown";
    UNKNOWN.origin = null;
  }

  // ========== 工具方法 ==========

  /**
   * 获取 Unknown 类型
   */
  public static Type unknown() {
    return UNKNOWN;
  }

  /**
   * 检查类型是否为 Unknown
   */
  public static boolean isUnknown(Type type) {
    if (type == null) return true;
    if (type instanceof CoreModel.TypeName tn) {
      return "Unknown".equals(tn.name);
    }
    return false;
  }

  // ========== 核心算法 ==========

  /**
   * 类型相等性判断（结构化比较）
   *
   * @param t1     第一个类型
   * @param t2     第二个类型
   * @param strict 是否严格模式（true: Unknown 与任何类型不等；false: Unknown 与任何类型相等）
   * @return 类型相等返回 true
   */
  public static boolean equals(Type t1, Type t2, boolean strict) {
    // 宽松模式：Unknown 与任何类型相等
    if (!strict && (isUnknown(t1) || isUnknown(t2))) {
      return true;
    }

    if (t1 instanceof CoreModel.PiiType || t2 instanceof CoreModel.PiiType) {
      return equals(unwrapPii(t1), unwrapPii(t2), strict);
    }

    // 类型种类不同
    if (t1.getClass() != t2.getClass()) {
      return false;
    }

    // 使用 pattern matching switch 比较
    return switch (t1) {
      case CoreModel.TypeName tn1 -> {
        var tn2 = (CoreModel.TypeName) t2;
        yield Objects.equals(tn1.name, tn2.name);
      }
      case CoreModel.TypeVar tv1 -> {
        var tv2 = (CoreModel.TypeVar) t2;
        yield Objects.equals(tv1.name, tv2.name);
      }
      case CoreModel.TypeApp ta1 -> {
        var ta2 = (CoreModel.TypeApp) t2;
        if (!Objects.equals(ta1.base, ta2.base)) yield false;
        if (ta1.args == null || ta2.args == null) yield ta1.args == ta2.args;
        if (ta1.args.size() != ta2.args.size()) yield false;
        for (int i = 0; i < ta1.args.size(); i++) {
          if (!equals(ta1.args.get(i), ta2.args.get(i), strict)) yield false;
        }
        yield true;
      }
      case CoreModel.Maybe m1 -> {
        var m2 = (CoreModel.Maybe) t2;
        yield equals(m1.type, m2.type, strict);
      }
      case CoreModel.Option o1 -> {
        var o2 = (CoreModel.Option) t2;
        yield equals(o1.type, o2.type, strict);
      }
      case CoreModel.Result r1 -> {
        var r2 = (CoreModel.Result) t2;
        yield equals(r1.ok, r2.ok, strict) && equals(r1.err, r2.err, strict);
      }
      case CoreModel.ListT l1 -> {
        var l2 = (CoreModel.ListT) t2;
        yield equals(l1.type, l2.type, strict);
      }
      case CoreModel.MapT m1 -> {
        var m2 = (CoreModel.MapT) t2;
        yield equals(m1.key, m2.key, strict) && equals(m1.val, m2.val, strict);
      }
      case CoreModel.FuncType f1 -> {
        var f2 = (CoreModel.FuncType) t2;
        if (f1.params == null || f2.params == null) yield f1.params == f2.params;
        if (f1.params.size() != f2.params.size()) yield false;
        for (int i = 0; i < f1.params.size(); i++) {
          if (!equals(f1.params.get(i), f2.params.get(i), strict)) yield false;
        }
        yield equals(f1.ret, f2.ret, strict);
      }
      default -> false;
    };
  }

  /**
   * Hindley-Milner 类型统一
   * <p>
   * 用于泛型类型推断。尝试找到类型变量的绑定，使得两个类型结构相等。
   *
   * @param t1       第一个类型
   * @param t2       第二个类型
   * @param bindings 类型变量绑定（输出参数）
   * @return 统一成功返回 true
   */
  public static boolean unify(Type t1, Type t2, Map<String, Type> bindings) {
    // Unknown 与任何类型统一
    if (isUnknown(t1) || isUnknown(t2)) {
      return true;
    }

    // 处理类型变量绑定
    if (t1 instanceof CoreModel.TypeVar tv1) {
      return bindTypeVar(tv1, t2, bindings);
    }
    if (t2 instanceof CoreModel.TypeVar tv2) {
      return bindTypeVar(tv2, t1, bindings);
    }

    if (t1 instanceof CoreModel.PiiType || t2 instanceof CoreModel.PiiType) {
      return unify(unwrapPii(t1), unwrapPii(t2), bindings);
    }

    // 类型种类不同
    if (t1.getClass() != t2.getClass()) {
      return false;
    }

    // 递归统一复合类型
    return switch (t1) {
      case CoreModel.Maybe m1 -> {
        var m2 = (CoreModel.Maybe) t2;
        yield unify(m1.type, m2.type, bindings);
      }
      case CoreModel.Option o1 -> {
        var o2 = (CoreModel.Option) t2;
        yield unify(o1.type, o2.type, bindings);
      }
      case CoreModel.Result r1 -> {
        var r2 = (CoreModel.Result) t2;
        yield unify(r1.ok, r2.ok, bindings) && unify(r1.err, r2.err, bindings);
      }
      case CoreModel.ListT l1 -> {
        var l2 = (CoreModel.ListT) t2;
        yield unify(l1.type, l2.type, bindings);
      }
      case CoreModel.MapT m1 -> {
        var m2 = (CoreModel.MapT) t2;
        yield unify(m1.key, m2.key, bindings) && unify(m1.val, m2.val, bindings);
      }
      case CoreModel.TypeApp ta1 -> {
        var ta2 = (CoreModel.TypeApp) t2;
        if (!Objects.equals(ta1.base, ta2.base)) yield false;
        if (ta1.args == null || ta2.args == null) yield ta1.args == ta2.args;
        if (ta1.args.size() != ta2.args.size()) yield false;
        for (int i = 0; i < ta1.args.size(); i++) {
          if (!unify(ta1.args.get(i), ta2.args.get(i), bindings)) yield false;
        }
        yield true;
      }
      case CoreModel.FuncType f1 -> {
        var f2 = (CoreModel.FuncType) t2;
        if (f1.params == null || f2.params == null) yield f1.params == f2.params;
        if (f1.params.size() != f2.params.size()) yield false;
        for (int i = 0; i < f1.params.size(); i++) {
          if (!unify(f1.params.get(i), f2.params.get(i), bindings)) yield false;
        }
        yield unify(f1.ret, f2.ret, bindings);
      }
      default -> equals(t1, t2, true);
    };
  }

  /**
   * 绑定类型变量
   */
  private static boolean bindTypeVar(CoreModel.TypeVar tv, Type type, Map<String, Type> bindings) {
    var current = bindings.get(tv.name);
    if (current == null) {
      bindings.put(tv.name, type);
      return true;
    }
    return equals(current, type, false);
  }

  /**
   * 子类型关系检查
   * <p>
   * 支持特殊规则：
   * - Maybe<T> ⊆ Option<T>（双向）
   * - Result<T1, E1> ⊆ Result<T2, E2> 当且仅当 T1 ⊆ T2 且 E1 ⊆ E2
   *
   * @param sub 子类型
   * @param sup 父类型
   * @return 存在子类型关系返回 true
   */
  public static boolean isSubtype(Type sub, Type sup) {
    // 相等类型
    if (equals(sub, sup, false)) {
      return true;
    }

    // Unknown 作为 top type
    if (isUnknown(sup)) {
      return true;
    }

    // Unknown 作为 bottom type 的否定
    if (isUnknown(sub)) {
      return false;
    }

    if (sub instanceof CoreModel.PiiType || sup instanceof CoreModel.PiiType) {
      return isSubtype(unwrapPii(sub), unwrapPii(sup));
    }

    // Maybe/Option 互转
    if (sup instanceof CoreModel.Option supOpt && sub instanceof CoreModel.Maybe subMaybe) {
      return isSubtype(subMaybe.type, supOpt.type);
    }
    if (sup instanceof CoreModel.Maybe supMaybe && sub instanceof CoreModel.Option subOpt) {
      return isSubtype(subOpt.type, supMaybe.type);
    }

    // Result 协变
    if (sup instanceof CoreModel.Result supRes && sub instanceof CoreModel.Result subRes) {
      return isSubtype(subRes.ok, supRes.ok) && isSubtype(subRes.err, supRes.err);
    }

    return false;
  }

  /**
   * 类型别名展开
   * <p>
   * 递归展开类型别名，检测循环别名。
   *
   * @param type    待展开的类型
   * @param aliases 类型别名映射（类型名 -> 类型定义）
   * @return 展开后的类型
   */
  public static Type expand(Type type, Map<String, Type> aliases) {
    var visited = new HashSet<String>();
    return expandRecursive(type, aliases, visited);
  }

  private static Type expandRecursive(Type type, Map<String, Type> aliases, Set<String> visited) {
    return switch (type) {
      case CoreModel.TypeName tn -> {
        var alias = aliases.get(tn.name);
        if (alias == null) yield tn;
        if (visited.contains(tn.name)) yield tn; // 循环别名
        visited.add(tn.name);
        var expanded = expandRecursive(alias, aliases, visited);
        visited.remove(tn.name);
        yield expanded;
      }
      case CoreModel.Maybe m -> {
        var expanded = new CoreModel.Maybe();
        expanded.type = expandRecursive(m.type, aliases, visited);
        expanded.origin = m.origin;
        yield expanded;
      }
      case CoreModel.Option o -> {
        var expanded = new CoreModel.Option();
        expanded.type = expandRecursive(o.type, aliases, visited);
        expanded.origin = o.origin;
        yield expanded;
      }
      case CoreModel.Result r -> {
        var expanded = new CoreModel.Result();
        expanded.ok = expandRecursive(r.ok, aliases, visited);
        expanded.err = expandRecursive(r.err, aliases, visited);
        expanded.origin = r.origin;
        yield expanded;
      }
      case CoreModel.ListT l -> {
        var expanded = new CoreModel.ListT();
        expanded.type = expandRecursive(l.type, aliases, visited);
        expanded.origin = l.origin;
        yield expanded;
      }
      case CoreModel.MapT m -> {
        var expanded = new CoreModel.MapT();
        expanded.key = expandRecursive(m.key, aliases, visited);
        expanded.val = expandRecursive(m.val, aliases, visited);
        expanded.origin = m.origin;
        yield expanded;
      }
      case CoreModel.TypeApp ta -> {
        var expanded = new CoreModel.TypeApp();
        expanded.base = ta.base;
        if (ta.args != null) {
          expanded.args = ta.args.stream()
            .map(arg -> expandRecursive(arg, aliases, visited))
            .toList();
        }
        expanded.origin = ta.origin;
        yield expanded;
      }
      case CoreModel.FuncType f -> {
        var expanded = new CoreModel.FuncType();
        if (f.params != null) {
          expanded.params = f.params.stream()
            .map(param -> expandRecursive(param, aliases, visited))
            .toList();
        }
        expanded.ret = expandRecursive(f.ret, aliases, visited);
        expanded.origin = f.origin;
        yield expanded;
      }
      case CoreModel.PiiType pii -> {
        var expanded = new CoreModel.PiiType();
        expanded.baseType = expandRecursive(pii.baseType, aliases, visited);
        expanded.sensitivity = pii.sensitivity;
        expanded.category = pii.category;
        expanded.origin = pii.origin;
        yield expanded;
      }
      default -> type;
    };
  }

  /**
   * 类型格式化（用于错误消息）
   *
   * @param type 待格式化的类型
   * @return 格式化后的字符串
   */
  public static String format(Type type) {
    if (type == null || isUnknown(type)) {
      return "Unknown";
    }

    return switch (type) {
      case CoreModel.TypeName tn -> tn.name;
      case CoreModel.TypeVar tv -> tv.name;
      case CoreModel.TypeApp ta -> {
        if (ta.args == null || ta.args.isEmpty()) {
          yield ta.base;
        }
        var argsStr = ta.args.stream()
          .map(TypeSystem::format)
          .reduce((a, b) -> a + ", " + b)
          .orElse("");
        yield ta.base + "<" + argsStr + ">";
      }
      case CoreModel.Maybe m -> format(m.type) + "?";
      case CoreModel.Option o -> "Option<" + format(o.type) + ">";
      case CoreModel.Result r -> "Result<" + format(r.ok) + ", " + format(r.err) + ">";
      case CoreModel.ListT l -> "List<" + format(l.type) + ">";
      case CoreModel.MapT m -> "Map<" + format(m.key) + ", " + format(m.val) + ">";
      case CoreModel.FuncType f -> {
        if (f.params == null || f.params.isEmpty()) {
          yield "() -> " + format(f.ret);
        }
        var paramsStr = f.params.stream()
          .map(TypeSystem::format)
          .reduce((a, b) -> a + ", " + b)
          .orElse("");
        yield "(" + paramsStr + ") -> " + format(f.ret);
      }
      case CoreModel.PiiType pii -> {
        var level = pii.sensitivity != null ? pii.sensitivity : "L1";
        var category = pii.category != null ? pii.category : "unknown";
        yield "@pii(" + level + ", " + category + ") " + format(pii.baseType);
      }
      default -> "Unknown";
    };
  }

  // ========== 类型推断辅助方法 ==========

  /**
   * 推断列表元素类型
   * <p>
   * 检查所有元素类型是否一致，如果一致返回该类型，否则返回 Unknown。
   *
   * @param elements 列表元素表达式
   * @return 推断的元素类型
   */
  public static Type inferListElementType(List<CoreModel.Expr> elements) {
    if (elements == null || elements.isEmpty()) {
      return unknown();
    }

    var elementTypes = new ArrayList<Type>();
    for (var element : elements) {
      var inferred = inferStaticType(element);
      if (inferred != null) {
        elementTypes.add(inferred);
      }
    }

    if (elementTypes.isEmpty()) {
      return unknown();
    }

    var first = elementTypes.get(0);
    for (int i = 1; i < elementTypes.size(); i++) {
      if (!equals(first, elementTypes.get(i), false)) {
        return unknown();
      }
    }

    return first;
  }

  /**
   * 推断函数类型
   *
   * @param params 函数参数
   * @param body   函数体
   * @return 推断的函数类型
   */
  public static CoreModel.FuncType inferFunctionType(List<CoreModel.Param> params, CoreModel.Block body) {
    var funcType = new CoreModel.FuncType();
    if (params != null) {
      funcType.params = params.stream().map(p -> p.type).toList();
    } else {
      funcType.params = List.of();
    }
    funcType.ret = inferReturnType(body);
    return funcType;
  }

  /**
   * 推断返回类型（扫描函数体寻找 return 语句）
   */
  private static Type inferReturnType(CoreModel.Block body) {
    if (body == null || body.statements == null) {
      return unknown();
    }

    // 倒序扫描寻找 return 语句
    for (int i = body.statements.size() - 1; i >= 0; i--) {
      var stmt = body.statements.get(i);
      if (stmt instanceof CoreModel.Return ret) {
        var type = inferStaticType(ret.expr);
        return type != null ? type : unknown();
      }
    }

    return unknown();
  }

  /**
   * 静态类型推断（仅基于表达式结构，不依赖上下文）
   */
  private static Type inferStaticType(CoreModel.Expr expr) {
    if (expr == null) {
      return null;
    }

    return switch (expr) {
      case CoreModel.Bool b -> createTypeName("Bool");
      case CoreModel.IntE i -> createTypeName("Int");
      case CoreModel.LongE l -> createTypeName("Long");
      case CoreModel.DoubleE d -> createTypeName("Double");
      case CoreModel.StringE s -> createTypeName("String");
      case CoreModel.NullE n -> {
        var maybe = new CoreModel.Maybe();
        maybe.type = unknown();
        yield maybe;
      }
      case CoreModel.Ok ok -> {
        var result = new CoreModel.Result();
        result.ok = inferStaticType(ok.expr);
        if (result.ok == null) result.ok = unknown();
        result.err = unknown();
        yield result;
      }
      case CoreModel.Err err -> {
        var result = new CoreModel.Result();
        result.ok = unknown();
        result.err = inferStaticType(err.expr);
        if (result.err == null) result.err = unknown();
        yield result;
      }
      case CoreModel.Some some -> {
        var option = new CoreModel.Option();
        option.type = inferStaticType(some.expr);
        if (option.type == null) option.type = unknown();
        yield option;
      }
      case CoreModel.NoneE none -> {
        var option = new CoreModel.Option();
        option.type = unknown();
        yield option;
      }
      case CoreModel.Lambda lambda -> {
        var funcType = new CoreModel.FuncType();
        funcType.params = lambda.params != null
          ? lambda.params.stream().map(p -> p.type).toList()
          : List.of();
        funcType.ret = lambda.ret;
        yield funcType;
      }
      case CoreModel.Construct ctor -> createTypeName(ctor.typeName);
      default -> null;
    };
  }

  /**
   * 创建 TypeName
   */
  private static CoreModel.TypeName createTypeName(String name) {
    var tn = new CoreModel.TypeName();
    tn.name = name;
    return tn;
  }

  private static Type unwrapPii(Type type) {
    if (type instanceof CoreModel.PiiType pii) {
      return pii.baseType != null ? pii.baseType : unknown();
    }
    return type;
  }
}
