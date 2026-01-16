package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.*;
import aster.core.typecheck.*;
import aster.core.typecheck.model.SymbolInfo;
import aster.core.typecheck.model.VisitorContext;

import java.util.*;

/**
 * 基础类型检查器（Base Type Checker）
 * <p>
 * 处理基础类型推断、表达式类型检查和函数调用验证。
 * 不包含泛型、效果和异步检查（由专门的检查器处理）。
 * <p>
 * 核心功能：
 * - 表达式类型推断：推断所有表达式的类型
 * - 函数调用检查：验证参数数量和类型
 * - Lambda 类型检查：验证闭包的类型正确性
 * - 构造器检查：验证数据类型构造
 * <p>
 * 公开可见性说明：本类从 package-private 改为 public，
 * 仅用于 {@link aster.core.typecheck.TypeChecker} Facade 模式的依赖注入。
 * 不建议直接使用，请通过 TypeChecker 统一入口调用。
 *
 * @see aster.core.typecheck.TypeChecker
 */
public final class BaseTypeChecker {

  // ========== 字段 ==========

  private final SymbolTable symbolTable;
  private final DiagnosticBuilder diagnostics;
  private final GenericTypeChecker genericChecker;

  // ========== 构造器 ==========

  public BaseTypeChecker(SymbolTable symbolTable, DiagnosticBuilder diagnostics, GenericTypeChecker genericChecker) {
    this.symbolTable = symbolTable;
    this.diagnostics = diagnostics;
    this.genericChecker = genericChecker;
  }

  // ========== 核心方法：表达式类型推断 ==========

  /**
   * 推断表达式的类型
   */
  public Type typeOfExpr(Expr expr, VisitorContext ctx) {
    return switch (expr) {
      // 字面量类型
      case CoreModel.Bool b -> createTypeName("Bool");
      case CoreModel.IntE i -> createTypeName("Int");
      case CoreModel.LongE l -> createTypeName("Long");
      case CoreModel.DoubleE d -> createTypeName("Double");
      case CoreModel.StringE s -> createTypeName("String");
      case CoreModel.NullE n -> {
        var maybe = new CoreModel.Maybe();
        maybe.type = TypeSystem.unknown();
        yield maybe;
      }

      // 名称引用
      case CoreModel.Name name -> {
        var symbol = symbolTable.lookup(name.name);
        if (symbol.isEmpty()) {
          if (!name.name.contains(".")) {
            diagnostics.undefinedVariable(name.name, Optional.ofNullable(name.origin));
          }
          yield TypeSystem.unknown();
        }
        yield symbol.get().type();
      }

      // 包装类型
      case CoreModel.Ok ok -> {
        var inner = typeOfExpr(ok.expr, ctx);
        var result = new CoreModel.Result();
        result.ok = inner;
        result.err = TypeSystem.unknown();
        yield result;
      }

      case CoreModel.Err err -> {
        var inner = typeOfExpr(err.expr, ctx);
        var result = new CoreModel.Result();
        result.ok = TypeSystem.unknown();
        result.err = inner;
        yield result;
      }

      case CoreModel.Some some -> {
        var inner = typeOfExpr(some.expr, ctx);
        var option = new CoreModel.Option();
        option.type = inner;
        yield option;
      }

      case CoreModel.NoneE none -> {
        var option = new CoreModel.Option();
        option.type = TypeSystem.unknown();
        yield option;
      }

      // 函数调用
      case CoreModel.Call call -> checkCall(call, ctx);

      // Lambda
      case CoreModel.Lambda lambda -> checkLambda(lambda, ctx);

      // 构造器
      case CoreModel.Construct construct -> checkConstruct(construct);

      // Await
      case CoreModel.Await await -> checkAwait(await, ctx);
    };
  }

  // ========== 语句类型检查 ==========

  /**
   * 检查语句（返回语句的"返回类型"）
   */
  public Optional<Type> checkStatement(Stmt stmt, VisitorContext ctx) {
    return switch (stmt) {
      case CoreModel.Let let -> {
        var exprType = typeOfExpr(let.expr, ctx);
        // 定义符号
        symbolTable.define(
          let.name,
          exprType,
          SymbolInfo.SymbolKind.VARIABLE,
          SymbolTable.DefineOptions.immutable(let.origin)
        );
        yield Optional.empty();
      }

      case CoreModel.Set set -> {
        var exprType = typeOfExpr(set.expr, ctx);
        var symbol = symbolTable.lookup(set.name);
        if (symbol.isPresent()) {
          if (!symbol.get().mutable()) {
            diagnostics.error(
              ErrorCode.TYPE_MISMATCH,
              Optional.ofNullable(set.origin),
              Map.of("error", "Cannot assign to immutable variable " + set.name)
            );
          } else if (!TypeSystem.equals(symbol.get().type(), exprType, false)) {
            diagnostics.typeMismatch(symbol.get().type(), exprType, Optional.ofNullable(set.origin));
          }
        }
        yield Optional.empty();
      }

      case CoreModel.Return ret -> Optional.of(typeOfExpr(ret.expr, ctx));

      case CoreModel.If ifStmt -> checkIf(ifStmt, ctx);

      case CoreModel.Match match -> checkMatch(match, ctx);

      case CoreModel.Block block -> checkBlock(block, ctx);

      case CoreModel.Scope scope -> {
        symbolTable.enterScope(SymbolTable.ScopeType.BLOCK);
        var result = Optional.<Type>empty();
        for (var s : scope.statements) {
          var stmtType = checkStatement(s, ctx);
          if (stmtType.isPresent()) {
            result = stmtType;
          }
        }
        symbolTable.exitScope();
        yield result;
      }

      case CoreModel.Start start -> {
        typeOfExpr(start.expr, ctx);
        yield Optional.empty();
      }

      case CoreModel.Wait wait -> Optional.empty();

      case CoreModel.Workflow workflow -> {
        if (workflow.steps != null) {
          for (var step : workflow.steps) {
            if (step.body != null) {
              checkBlock(step.body, ctx);
            }
            if (step.compensate != null) {
              checkBlock(step.compensate, ctx);
            }
          }
        }
        yield Optional.empty();
      }
    };
  }

  /**
   * 检查代码块
   */
  public Optional<Type> checkBlock(Block block, VisitorContext ctx) {
    var result = Optional.<Type>empty();
    for (var stmt : block.statements) {
      var stmtType = checkStatement(stmt, ctx);
      if (stmtType.isPresent()) {
        result = stmtType;
      }
    }
    return result;
  }

  // ========== 私有辅助方法 ==========

  /**
   * 检查函数调用
   */
  private Type checkCall(CoreModel.Call call, VisitorContext ctx) {
    var funcType = typeOfExpr(call.target, ctx);

    // 特殊处理：not(x)
    if (call.target instanceof CoreModel.Name name && "not".equals(name.name)) {
      if (call.args.size() != 1) {
        diagnostics.error(
          ErrorCode.NOT_CALL_ARITY,
          Optional.ofNullable(call.origin),
          Map.of()
        );
      } else {
        typeOfExpr(call.args.get(0), ctx);
      }
      return createTypeName("Bool");
    }

    // 如果不是函数类型，返回 unknown
    if (!(funcType instanceof CoreModel.FuncType ft)) {
      return TypeSystem.unknown();
    }

    // 【关键修复】在进行任何类型比较前，先展开函数类型中的所有别名
    var expandedFt = expandFuncType(ft);

    // 参数数量检查
    if (call.args.size() != expandedFt.params.size()) {
      diagnostics.error(
        ErrorCode.TYPE_MISMATCH,
        Optional.ofNullable(call.origin),
        Map.of(
          "expected", expandedFt.params.size() + " arguments",
          "actual", call.args.size() + " arguments"
        )
      );
    }

    // 推断参数类型列表（只执行一次，避免重复推断）
    // 同时展开参数类型中的别名
    var argTypes = call.args.stream()
      .map(arg -> expandType(typeOfExpr(arg, ctx)))
      .toList();

    // 检查是否是泛型函数调用，如果是则进行类型统一和替换
    if (containsTypeVar(expandedFt)) {
      var bindings = genericChecker.unifyGenericCall(expandedFt, argTypes, Optional.ofNullable(call.origin));

      // 参数类型检查（使用替换后的类型）
      for (int i = 0; i < Math.min(call.args.size(), expandedFt.params.size()); i++) {
        var argType = argTypes.get(i);
        var paramType = genericChecker.substituteTypeVars(expandedFt.params.get(i), bindings);
        if (!TypeSystem.equals(argType, paramType, false)) {
          diagnostics.typeMismatch(paramType, argType, Optional.ofNullable(call.origin));
        }
      }

      // 返回替换后的返回类型
      return genericChecker.substituteTypeVars(expandedFt.ret, bindings);
    } else {
      // 非泛型函数：传统类型检查（使用展开后的类型）
      for (int i = 0; i < Math.min(call.args.size(), expandedFt.params.size()); i++) {
        var argType = argTypes.get(i);
        var paramType = expandedFt.params.get(i);
        if (!TypeSystem.equals(argType, paramType, false)) {
          diagnostics.typeMismatch(paramType, argType, Optional.ofNullable(call.origin));
        }
      }

      return expandedFt.ret;
    }
  }

  /**
   * 检查类型是否包含类型变量（支持别名展开）
   * 
   * 关键修复：在检查 TypeName 时，首先尝试解析类型别名。
   * 如果 TypeName 指向一个别名，展开后递归检查展开结果是否包含类型变量。
   * 
   * 示例：
   * - type Box<T> = Result<T, String>
   * - containsTypeVar(Box) 应该返回 true（因为展开后是 Result<T, String>）
   */
  private boolean containsTypeVar(Type type) {
    return switch (type) {
      case CoreModel.TypeVar tv -> true;
      
      case CoreModel.TypeName tn -> {
        // 【修复】尝试展开别名
        var resolved = symbolTable.resolveTypeAlias(tn.name);
        if (resolved.isPresent() && resolved.get() != tn) {
          // 别名存在且展开后不同，递归检查展开结果
          yield containsTypeVar(resolved.get());
        }
        // 不是别名或展开失败，TypeName 本身不包含类型变量
        yield false;
      }
      
      case CoreModel.TypeApp ta -> ta.args.stream().anyMatch(this::containsTypeVar);
      case CoreModel.FuncType ft ->
        ft.params.stream().anyMatch(this::containsTypeVar) || containsTypeVar(ft.ret);
      case CoreModel.Result r -> containsTypeVar(r.ok) || containsTypeVar(r.err);
      case CoreModel.Maybe m -> containsTypeVar(m.type);
      case CoreModel.Option o -> containsTypeVar(o.type);
      case CoreModel.ListT l -> containsTypeVar(l.type);
      case CoreModel.MapT m -> containsTypeVar(m.key) || containsTypeVar(m.val);
      case CoreModel.PiiType pii -> containsTypeVar(pii.baseType);
    };
  }

  /**
   * 检查 Lambda
   */
  private Type checkLambda(CoreModel.Lambda lambda, VisitorContext ctx) {
    symbolTable.enterScope(SymbolTable.ScopeType.LAMBDA);

    // 定义参数符号
    for (var param : lambda.params) {
      symbolTable.define(
        param.name,
        param.type,
        SymbolInfo.SymbolKind.PARAMETER,
        SymbolTable.DefineOptions.immutable(lambda.origin)
      );
    }

    // 检查函数体
    var bodyType = checkBlock(lambda.body, ctx);

    // 【关键修复】验证返回类型前先展开别名
    if (bodyType.isPresent()) {
      var expandedBodyType = expandType(bodyType.get());
      var expandedDeclaredType = expandType(lambda.ret);
      
      if (!TypeSystem.equals(expandedBodyType, expandedDeclaredType, false)) {
        diagnostics.error(
          ErrorCode.RETURN_TYPE_MISMATCH,
          Optional.ofNullable(lambda.origin),
          Map.of(
            "expected", TypeSystem.format(lambda.ret),
            "actual", TypeSystem.format(bodyType.get())
          )
        );
      }
    }

    symbolTable.exitScope();

    // 返回函数类型
    var funcType = new CoreModel.FuncType();
    funcType.params = lambda.params.stream().map(p -> p.type).toList();
    funcType.ret = lambda.ret;
    return funcType;
  }

  /**
   * 检查构造器
   */
  private Type checkConstruct(CoreModel.Construct construct) {
    // 简化实现：返回类型名
    // 完整实现需要查找 data 声明并验证字段
    return createTypeName(construct.typeName);
  }

  /**
   * 检查 Await
   */
  private Type checkAwait(CoreModel.Await await, VisitorContext ctx) {
    var awaitedType = typeOfExpr(await.expr, ctx);

    // First try direct type matching
    if (awaitedType instanceof CoreModel.Maybe maybe) {
      return maybe.type;
    } else if (awaitedType instanceof CoreModel.Result result) {
      return result.ok;
    }

    // Try expanding type aliases
    var normalized = TypeSystem.expand(awaitedType, ctx.getTypeAliases());
    if (normalized instanceof CoreModel.Maybe maybe) {
      return maybe.type;
    } else if (normalized instanceof CoreModel.Result result) {
      return result.ok;
    }

    // Try TypeApp-based matching (for aliased types)
    var unwrapped = unwrapAwaitable(normalized);
    if (unwrapped.isPresent()) {
      return unwrapped.get();
    }

    diagnostics.warning(
      ErrorCode.AWAIT_TYPE,
      Optional.ofNullable(await.origin),
      Map.of("type", TypeSystem.format(awaitedType))
    );
    return TypeSystem.unknown();
  }

  /**
   * 检查 If 语句
   */
  private Optional<Type> checkIf(CoreModel.If ifStmt, VisitorContext ctx) {
    // 检查条件
    var condType = typeOfExpr(ifStmt.cond, ctx);
    if (!TypeSystem.equals(condType, createTypeName("Bool"), false)) {
      diagnostics.typeMismatch(createTypeName("Bool"), condType, Optional.ofNullable(ifStmt.origin));
    }

    // 检查 then 分支
    var thenType = checkBlock(ifStmt.thenBlock, ctx);

    // 检查 else 分支
    if (ifStmt.elseBlock != null) {
      var elseType = checkBlock(ifStmt.elseBlock, ctx);

      // 如果两个分支都有返回，检查类型一致性
      if (thenType.isPresent() && elseType.isPresent()) {
        if (!TypeSystem.equals(thenType.get(), elseType.get(), false)) {
          diagnostics.error(
            ErrorCode.IF_BRANCH_MISMATCH,
            Optional.ofNullable(ifStmt.origin),
            Map.of(
              "then", TypeSystem.format(thenType.get()),
              "else", TypeSystem.format(elseType.get())
            )
          );
        }
        return thenType;
      }
    }

    return Optional.empty();
  }

  /**
   * 检查 Match 语句
   */
  private Optional<Type> checkMatch(CoreModel.Match match, VisitorContext ctx) {
    // 检查被匹配的表达式
    var exprType = typeOfExpr(match.expr, ctx);

    // 检查所有分支
    Type firstCaseType = null;
    for (var kase : match.cases) {
      var caseType = checkStatement(kase.body, ctx);
      if (caseType.isPresent()) {
        if (firstCaseType == null) {
          firstCaseType = caseType.get();
        } else if (!TypeSystem.equals(firstCaseType, caseType.get(), false)) {
          diagnostics.error(
            ErrorCode.MATCH_BRANCH_MISMATCH,
            Optional.ofNullable(match.origin),
            Map.of(
              "type1", TypeSystem.format(firstCaseType),
              "type2", TypeSystem.format(caseType.get())
            )
          );
        }
      }
    }

    return firstCaseType != null ? Optional.of(firstCaseType) : Optional.empty();
  }

  /**
   * 创建类型名
   */
  private CoreModel.TypeName createTypeName(String name) {
    var type = new CoreModel.TypeName();
    type.name = name;
    return type;
  }

  /**
   * 解包支持 await 的类型（Maybe / Option / Result 或其别名）。
   */
  private Optional<Type> unwrapAwaitable(Type type) {
    if (type == null || TypeSystem.isUnknown(type)) {
      return Optional.empty();
    }

    if (type instanceof CoreModel.Maybe maybe) {
      return Optional.ofNullable(maybe.type);
    }
    if (type instanceof CoreModel.Option option) {
      return Optional.ofNullable(option.type);
    }
    if (type instanceof CoreModel.Result result) {
      return Optional.ofNullable(result.ok);
    }
    if (type instanceof CoreModel.TypeApp typeApp) {
      return unwrapAwaitableFromTypeApp(typeApp);
    }
    return Optional.empty();
  }

  private Optional<Type> unwrapAwaitableFromTypeApp(CoreModel.TypeApp typeApp) {
    if (typeApp.args == null || typeApp.args.isEmpty()) {
      return Optional.empty();
    }
    if (isMaybeLike(typeApp.base)) {
      return Optional.ofNullable(typeApp.args.get(0));
    }
    if (isResultLike(typeApp.base)) {
      return Optional.ofNullable(typeApp.args.get(0));
    }
    return Optional.empty();
  }

  private boolean isMaybeLike(String baseName) {
    return matchesSimpleName(baseName, "Maybe") || matchesSimpleName(baseName, "Option");
  }

  private boolean isResultLike(String baseName) {
    return matchesSimpleName(baseName, "Result");
  }

  private boolean matchesSimpleName(String candidate, String expected) {
    if (candidate == null || expected == null) {
      return false;
    }
    if (candidate.equals(expected)) {
      return true;
    }
    var idx = candidate.lastIndexOf('.');
    if (idx < 0) {
      return false;
    }
    return candidate.substring(idx + 1).equals(expected);
  }

  // ========== 别名展开辅助方法 ==========

  /**
   * 展开类型中的所有别名引用
   *
   * 递归处理复合类型（Result、Option、FuncType 等），
   * 将所有 TypeName 别名展开为其底层类型。
   *
   * @param type 待展开的类型
   * @return 展开后的类型
   */
  public Type expandType(Type type) {
    return switch (type) {
      case CoreModel.TypeName tn -> {
        var resolved = symbolTable.resolveTypeAlias(tn.name);
        yield resolved.orElse(tn);
      }
      case CoreModel.Result r -> {
        var expanded = new CoreModel.Result();
        expanded.ok = expandType(r.ok);
        expanded.err = expandType(r.err);
        expanded.origin = r.origin;
        yield expanded;
      }
      case CoreModel.Option o -> {
        var expanded = new CoreModel.Option();
        expanded.type = expandType(o.type);
        expanded.origin = o.origin;
        yield expanded;
      }
      case CoreModel.Maybe m -> {
        var expanded = new CoreModel.Maybe();
        expanded.type = expandType(m.type);
        expanded.origin = m.origin;
        yield expanded;
      }
      case CoreModel.ListT l -> {
        var expanded = new CoreModel.ListT();
        expanded.type = expandType(l.type);
        expanded.origin = l.origin;
        yield expanded;
      }
      case CoreModel.MapT m -> {
        var expanded = new CoreModel.MapT();
        expanded.key = expandType(m.key);
        expanded.val = expandType(m.val);
        expanded.origin = m.origin;
        yield expanded;
      }
      case CoreModel.FuncType ft -> expandFuncType(ft);
      case CoreModel.TypeApp ta -> {
        // 【关键修复】展开 TypeApp 的基类并执行类型参数替换
        // 场景：type Box<T> = Result<T, String>
        //      Box<Int> 应展开为 Result<Int, String>

        var expandedBase = symbolTable.resolveTypeAlias(ta.base).orElse(null);
        if (expandedBase != null) {
          // 别名存在，需要将别名定义中的类型变量替换为实际参数
          // 例如：expandedBase = Result<TypeVar("T"), String>
          //      ta.args = [Int]
          //      结果 = Result<Int, String>

          // 首先展开实际参数
          var expandedArgs = ta.args.stream()
            .map(this::expandType)
            .toList();

          // 获取别名的显式类型参数（如果有）
          var typeParams = symbolTable.getTypeAliasParams(ta.base).orElse(List.of());

          // 执行类型参数替换
          var substituted = substituteTypeParams(expandedBase, expandedArgs, typeParams);
          yield substituted;
        } else {
          // 基类不是别名，仅展开参数
          var expanded = new CoreModel.TypeApp();
          expanded.base = ta.base;
          expanded.args = ta.args.stream()
            .map(this::expandType)
            .toList();
          expanded.origin = ta.origin;
          yield expanded;
        }
      }
      default -> type; // TypeVar 等不展开
    };
  }

  /**
   * 展开 FuncType 中的别名引用
   */
  private CoreModel.FuncType expandFuncType(CoreModel.FuncType ft) {
    var expanded = new CoreModel.FuncType();
    expanded.params = ft.params.stream()
      .map(this::expandType)
      .toList();
    expanded.ret = expandType(ft.ret);
    expanded.origin = ft.origin;
    return expanded;
  }

  /**
   * 执行类型参数替换
   * 
   * 将类型定义中的类型变量（TypeVar）替换为实际类型参数。
   * 这是泛型别名展开的核心逻辑。
   * 
   * 例如：
   * - 别名定义：Result<TypeVar("T"), String>
   * - 实际参数：[Int]
   * - 替换结果：Result<Int, String>
   * 
   * @param type 待替换的类型（通常是别名的定义）
   * @param args 实际类型参数
   * @return 替换后的类型
   */
  private Type substituteTypeParams(Type type, List<Type> args) {
    // 构建类型变量到实际参数的映射
    // 假设类型变量按字母顺序 T, U, V... 或按出现顺序
    // 这里使用简化策略：收集所有类型变量并按出现顺序映射
    var typeVars = collectTypeVars(type);
    return substituteTypeParams(type, args, typeVars);
  }

  /**
   * 执行类型参数替换（使用显式类型参数列表）
   *
   * @param type 待替换的类型
   * @param args 实际类型参数
   * @param typeParams 显式类型参数列表（如 ["T", "E"]）
   * @return 替换后的类型
   */
  private Type substituteTypeParams(Type type, List<Type> args, List<String> typeParams) {
    // 如果没有显式类型参数，回退到自动推断
    if (typeParams.isEmpty()) {
      return substituteTypeParams(type, args);
    }

    // 使用显式类型参数构建绑定映射
    var bindings = new HashMap<String, Type>();
    for (int i = 0; i < Math.min(typeParams.size(), args.size()); i++) {
      bindings.put(typeParams.get(i), args.get(i));
    }

    // 执行替换
    return substituteTypeVarsInType(type, bindings);
  }

  /**
   * 收集类型中的所有类型变量（按出现顺序，去重）
   */
  private List<String> collectTypeVars(Type type) {
    var vars = new java.util.LinkedHashSet<String>();
    collectTypeVarsHelper(type, vars);
    return new ArrayList<>(vars);
  }

  /**
   * 递归收集类型变量
   */
  private void collectTypeVarsHelper(Type type, java.util.Set<String> vars) {
    switch (type) {
      case CoreModel.TypeVar tv -> vars.add(tv.name);
      case CoreModel.Result r -> {
        collectTypeVarsHelper(r.ok, vars);
        collectTypeVarsHelper(r.err, vars);
      }
      case CoreModel.Option o -> collectTypeVarsHelper(o.type, vars);
      case CoreModel.Maybe m -> collectTypeVarsHelper(m.type, vars);
      case CoreModel.ListT l -> collectTypeVarsHelper(l.type, vars);
      case CoreModel.MapT m -> {
        collectTypeVarsHelper(m.key, vars);
        collectTypeVarsHelper(m.val, vars);
      }
      case CoreModel.FuncType ft -> {
        ft.params.forEach(p -> collectTypeVarsHelper(p, vars));
        collectTypeVarsHelper(ft.ret, vars);
      }
      case CoreModel.TypeApp ta -> {
        ta.args.forEach(arg -> collectTypeVarsHelper(arg, vars));
      }
      case CoreModel.PiiType pii -> collectTypeVarsHelper(pii.baseType, vars);
      default -> {} // TypeName 等不包含类型变量
    }
  }

  /**
   * 在类型中替换类型变量
   */
  private Type substituteTypeVarsInType(Type type, Map<String, Type> bindings) {
    return switch (type) {
      case CoreModel.TypeVar tv -> bindings.getOrDefault(tv.name, tv);
      case CoreModel.TypeName tn -> tn; // 类型名不变
      case CoreModel.Result r -> {
        var substituted = new CoreModel.Result();
        substituted.ok = substituteTypeVarsInType(r.ok, bindings);
        substituted.err = substituteTypeVarsInType(r.err, bindings);
        substituted.origin = r.origin;
        yield substituted;
      }
      case CoreModel.Option o -> {
        var substituted = new CoreModel.Option();
        substituted.type = substituteTypeVarsInType(o.type, bindings);
        substituted.origin = o.origin;
        yield substituted;
      }
      case CoreModel.Maybe m -> {
        var substituted = new CoreModel.Maybe();
        substituted.type = substituteTypeVarsInType(m.type, bindings);
        substituted.origin = m.origin;
        yield substituted;
      }
      case CoreModel.ListT l -> {
        var substituted = new CoreModel.ListT();
        substituted.type = substituteTypeVarsInType(l.type, bindings);
        substituted.origin = l.origin;
        yield substituted;
      }
      case CoreModel.MapT m -> {
        var substituted = new CoreModel.MapT();
        substituted.key = substituteTypeVarsInType(m.key, bindings);
        substituted.val = substituteTypeVarsInType(m.val, bindings);
        substituted.origin = m.origin;
        yield substituted;
      }
      case CoreModel.FuncType ft -> {
        var substituted = new CoreModel.FuncType();
        substituted.params = ft.params.stream()
          .map(p -> substituteTypeVarsInType(p, bindings))
          .toList();
        substituted.ret = substituteTypeVarsInType(ft.ret, bindings);
        substituted.origin = ft.origin;
        yield substituted;
      }
      case CoreModel.PiiType pii -> {
        var substituted = new CoreModel.PiiType();
        substituted.baseType = substituteTypeVarsInType(pii.baseType, bindings);
        substituted.sensitivity = pii.sensitivity;
        substituted.category = pii.category;
        substituted.origin = pii.origin;
        yield substituted;
      }
      case CoreModel.TypeApp ta -> {
        var substituted = new CoreModel.TypeApp();
        substituted.base = ta.base;
        substituted.args = ta.args.stream()
          .map(arg -> substituteTypeVarsInType(arg, bindings))
          .toList();
        substituted.origin = ta.origin;
        yield substituted;
      }
    };
  }
}
