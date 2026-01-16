package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.Type;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.TypeSystem;

import java.util.*;

/**
 * 泛型类型检查器（Generic Type Checker）
 * <p>
 * 处理泛型类型参数验证、类型统一和类型变量替换。
 * 使用 Hindley-Milner 统一算法进行类型推断。
 * <p>
 * 核心功能：
 * - 类型参数验证：检查类型参数数量匹配
 * - 类型统一：使用 TypeSystem.unify 进行 Hindley-Milner 统一
 * - 类型替换：将类型变量替换为具体类型
 * - 嵌套泛型：支持 List<Result<T, E>> 等复杂泛型
 */
public final class GenericTypeChecker {

  // ========== 字段 ==========

  private final DiagnosticBuilder diagnostics;

  // ========== 构造器 ==========

  public GenericTypeChecker(DiagnosticBuilder diagnostics) {
    if (diagnostics == null) {
      throw new IllegalArgumentException("diagnostics cannot be null");
    }
    this.diagnostics = diagnostics;
  }

  // ========== 公共 API ==========

  /**
   * 检查泛型类型参数数量
   * <p>
   * 验证类型应用（TypeApp）的参数数量与声明是否匹配。
   *
   * @param expected 期望的类型参数数量
   * @param actual   实际的类型参数列表
   * @param typeName 类型名称（用于错误消息）
   * @param span     源码位置
   */
  public void checkTypeParameterCount(
    int expected,
    List<Type> actual,
    String typeName,
    Optional<CoreModel.Origin> span
  ) {
    if (actual.size() != expected) {
      diagnostics.error(
        ErrorCode.TYPE_MISMATCH,
        span,
        Map.of(
          "expected", typeName + "<" + expected + " type parameters>",
          "actual", typeName + "<" + actual.size() + " type parameters>"
        )
      );
    }
  }

  /**
   * 统一泛型函数调用
   * <p>
   * 给定泛型函数类型和实参，推断类型变量的绑定。
   *
   * @param funcType 泛型函数类型
   * @param argTypes 实参类型列表
   * @param span     源码位置
   * @return 类型变量绑定映射（变量名 -> 具体类型）
   */
  public Map<String, Type> unifyGenericCall(
    CoreModel.FuncType funcType,
    List<Type> argTypes,
    Optional<CoreModel.Origin> span
  ) {
    var bindings = new HashMap<String, Type>();

    // 检查参数数量
    if (funcType.params.size() != argTypes.size()) {
      diagnostics.error(
        ErrorCode.TYPE_MISMATCH,
        span,
        Map.of(
          "expected", funcType.params.size() + " arguments",
          "actual", argTypes.size() + " arguments"
        )
      );
      return bindings;
    }

    // 统一每个参数
    for (int i = 0; i < funcType.params.size(); i++) {
      var paramType = funcType.params.get(i);
      var argType = argTypes.get(i);

      if (!TypeSystem.unify(argType, paramType, bindings)) {
        diagnostics.error(
          ErrorCode.TYPE_MISMATCH,
          span,
          Map.of(
            "expected", TypeSystem.format(paramType),
            "actual", TypeSystem.format(argType)
          )
        );
      }
    }

    return bindings;
  }

  /**
   * 替换类型变量
   * <p>
   * 将类型中的所有类型变量替换为绑定中的具体类型。
   * 支持递归替换嵌套泛型类型。
   *
   * @param type     原始类型
   * @param bindings 类型变量绑定
   * @return 替换后的类型
   */
  public Type substituteTypeVars(Type type, Map<String, Type> bindings) {
    return switch (type) {
      case CoreModel.TypeVar tv -> {
        // 查找绑定，如果没有则返回原类型变量
        yield bindings.getOrDefault(tv.name, tv);
      }

      case CoreModel.TypeName tn -> tn; // 具体类型不需要替换

      case CoreModel.TypeApp ta -> {
        // 递归替换类型应用的参数
        var substituted = new CoreModel.TypeApp();
        substituted.base = ta.base;
        substituted.args = ta.args.stream()
          .map(arg -> substituteTypeVars(arg, bindings))
          .toList();
        substituted.origin = ta.origin;
        yield substituted;
      }

      case CoreModel.FuncType ft -> {
        // 递归替换函数类型的参数和返回类型
        var substituted = new CoreModel.FuncType();
        substituted.params = ft.params.stream()
          .map(param -> substituteTypeVars(param, bindings))
          .toList();
        substituted.ret = substituteTypeVars(ft.ret, bindings);
        substituted.origin = ft.origin;
        yield substituted;
      }

      case CoreModel.Result r -> {
        // 递归替换 Result 的 ok 和 err 类型
        var substituted = new CoreModel.Result();
        substituted.ok = substituteTypeVars(r.ok, bindings);
        substituted.err = substituteTypeVars(r.err, bindings);
        substituted.origin = r.origin;
        yield substituted;
      }

      case CoreModel.Maybe m -> {
        // 递归替换 Maybe 的内部类型
        var substituted = new CoreModel.Maybe();
        substituted.type = substituteTypeVars(m.type, bindings);
        substituted.origin = m.origin;
        yield substituted;
      }

      case CoreModel.Option o -> {
        // 递归替换 Option 的内部类型
        var substituted = new CoreModel.Option();
        substituted.type = substituteTypeVars(o.type, bindings);
        substituted.origin = o.origin;
        yield substituted;
      }

      case CoreModel.ListT l -> {
        // 递归替换 List 的元素类型
        var substituted = new CoreModel.ListT();
        substituted.type = substituteTypeVars(l.type, bindings);
        substituted.origin = l.origin;
        yield substituted;
      }

      case CoreModel.MapT m -> {
        // 递归替换 Map 的键和值类型
        var substituted = new CoreModel.MapT();
        substituted.key = substituteTypeVars(m.key, bindings);
        substituted.val = substituteTypeVars(m.val, bindings);
        substituted.origin = m.origin;
        yield substituted;
      }
      case CoreModel.PiiType pii -> {
        var substituted = new CoreModel.PiiType();
        substituted.baseType = substituteTypeVars(pii.baseType, bindings);
        substituted.sensitivity = pii.sensitivity;
        substituted.category = pii.category;
        substituted.origin = pii.origin;
        yield substituted;
      }
    };
  }

  /**
   * 验证类型应用
   * <p>
   * 检查 TypeApp 是否正确使用（参数数量、类型约束等）。
   *
   * @param typeApp 类型应用
   * @param span    源码位置
   */
  public void validateTypeApplication(CoreModel.TypeApp typeApp, Optional<CoreModel.Origin> span) {
    // 根据基础类型检查参数数量
    switch (typeApp.base) {
      case "List" -> {
        if (typeApp.args.size() != 1) {
          checkTypeParameterCount(1, typeApp.args, "List", span);
        }
      }
      case "Map" -> {
        if (typeApp.args.size() != 2) {
          checkTypeParameterCount(2, typeApp.args, "Map", span);
        }
      }
      case "Result" -> {
        if (typeApp.args.size() != 2) {
          checkTypeParameterCount(2, typeApp.args, "Result", span);
        }
      }
      case "Maybe", "Option" -> {
        if (typeApp.args.size() != 1) {
          checkTypeParameterCount(1, typeApp.args, typeApp.base, span);
        }
      }
      // 其他自定义泛型类型需要从模块上下文获取声明
      default -> {
        // 暂不检查，留给 BaseTypeChecker 处理
      }
    }
  }

  /**
   * 检查类型变量一致性
   * <p>
   * 确保同一个类型变量在多次使用时绑定到相同的具体类型。
   *
   * @param bindings 类型变量绑定
   * @param varName  类型变量名
   * @param newType  新推断的类型
   * @param span     源码位置
   * @return 是否一致
   */
  public boolean checkTypeVarConsistency(
    Map<String, Type> bindings,
    String varName,
    Type newType,
    Optional<CoreModel.Origin> span
  ) {
    if (bindings.containsKey(varName)) {
      var existingType = bindings.get(varName);
      if (!TypeSystem.equals(existingType, newType, true)) {
        diagnostics.error(
          ErrorCode.TYPEVAR_INCONSISTENT,
          span,
          Map.of(
            "var", varName,
            "type1", TypeSystem.format(existingType),
            "type2", TypeSystem.format(newType)
          )
        );
        return false;
      }
    }
    return true;
  }

  /**
   * 实例化泛型类型
   * <p>
   * 将泛型类型（如 List<T>）实例化为具体类型（如 List<Int>）。
   *
   * @param genericType 泛型类型
   * @param typeArgs    类型参数
   * @return 实例化后的类型
   */
  public Type instantiateGenericType(Type genericType, List<Type> typeArgs) {
    // 构建类型变量到具体类型的映射
    var bindings = new HashMap<String, Type>();

    // 收集泛型类型中的类型变量
    var typeVars = collectTypeVars(genericType);

    // 绑定类型变量
    if (typeVars.size() == typeArgs.size()) {
      for (int i = 0; i < typeVars.size(); i++) {
        bindings.put(typeVars.get(i), typeArgs.get(i));
      }
    }

    // 替换类型变量
    return substituteTypeVars(genericType, bindings);
  }

  // ========== 私有辅助方法 ==========

  /**
   * 收集类型中的所有类型变量名
   */
  private List<String> collectTypeVars(Type type) {
    var vars = new ArrayList<String>();
    collectTypeVarsRecursive(type, vars, new HashSet<>());
    return vars;
  }

  /**
   * 递归收集类型变量
   */
  private void collectTypeVarsRecursive(Type type, List<String> vars, Set<String> seen) {
    switch (type) {
      case CoreModel.TypeVar tv -> {
        if (!seen.contains(tv.name)) {
          vars.add(tv.name);
          seen.add(tv.name);
        }
      }
      case CoreModel.TypeApp ta -> {
        for (var arg : ta.args) {
          collectTypeVarsRecursive(arg, vars, seen);
        }
      }
      case CoreModel.FuncType ft -> {
        for (var param : ft.params) {
          collectTypeVarsRecursive(param, vars, seen);
        }
        collectTypeVarsRecursive(ft.ret, vars, seen);
      }
      case CoreModel.Result r -> {
        collectTypeVarsRecursive(r.ok, vars, seen);
        collectTypeVarsRecursive(r.err, vars, seen);
      }
      case CoreModel.Maybe m -> collectTypeVarsRecursive(m.type, vars, seen);
      case CoreModel.Option o -> collectTypeVarsRecursive(o.type, vars, seen);
      case CoreModel.ListT l -> collectTypeVarsRecursive(l.type, vars, seen);
      case CoreModel.MapT m -> {
        collectTypeVarsRecursive(m.key, vars, seen);
        collectTypeVarsRecursive(m.val, vars, seen);
      }
      default -> {
        // TypeName 不包含类型变量
      }
    }
  }
}
