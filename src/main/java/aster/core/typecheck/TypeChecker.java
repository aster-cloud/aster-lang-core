package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.*;
import aster.core.typecheck.capability.ManifestConfig;
import aster.core.typecheck.capability.ManifestReader;
import aster.core.typecheck.checkers.*;
import aster.core.typecheck.pii.PiiTypeChecker;
import aster.core.typecheck.model.Diagnostic;
import aster.core.typecheck.model.SymbolInfo;
import aster.core.typecheck.model.VisitorContext;

import java.nio.file.Path;
import java.util.*;

/**
 * 类型检查器（Type Checker）
 * <p>
 * 主协调器，整合所有专门检查器并提供公共 API。
 * 采用 Facade 模式封装复杂的类型检查流程。
 * <p>
 * 核心功能：
 * - 模块级类型检查：两遍扫描（收集类型 → 检查函数）
 * - 检查器协调：基础类型、泛型、效果、异步纪律
 * - 诊断收集：汇总所有检查器的错误和警告
 * - 公共 API：对外提供统一的类型检查入口
 */
public final class TypeChecker {

  // ========== 核心组件 ==========

  private final SymbolTable symbolTable;
  private final DiagnosticBuilder diagnostics;
  private final EffectConfig effectConfig;

  // ========== 专门检查器 ==========

  private final BaseTypeChecker baseChecker;
  private final GenericTypeChecker genericChecker;
  private final EffectChecker effectChecker;
  private final AsyncDisciplineChecker asyncChecker;
  private final PiiTypeChecker piiChecker;
  private final CapabilityChecker capabilityChecker;

  // ========== 构造器 ==========

  public TypeChecker() {
    this.symbolTable = new SymbolTable();
    this.diagnostics = new DiagnosticBuilder();
    this.effectConfig = EffectConfig.getInstance();

    // 【修复】创建专门检查器，注意依赖注入顺序：GenericTypeChecker 必须在 BaseTypeChecker 之前
    this.genericChecker = new GenericTypeChecker(diagnostics);
    this.baseChecker = new BaseTypeChecker(symbolTable, diagnostics, genericChecker);
    this.effectChecker = new EffectChecker(symbolTable, effectConfig, diagnostics);
    this.asyncChecker = new AsyncDisciplineChecker(diagnostics);
    this.piiChecker = new PiiTypeChecker();
    this.capabilityChecker = new CapabilityChecker();
    loadManifestFromEnv();
  }

  // ========== 公共 API ==========

  /**
   * 检查模块的类型正确性
   *
   * @param module 模块 IR
   * @return 诊断结果列表（错误、警告、提示）
   */
  public List<Diagnostic> typecheckModule(CoreModel.Module module) {
    if (module == null) {
      return List.of();
    }
    diagnostics.clear();
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);

    try {
      // 定义内置类型别名（向后兼容）
      defineBuiltinTypeAliases();

      // 第一遍：收集类型定义
      collectTypeDefinitions(module);

      // 【修复】注入类型别名映射，使下游检查器可以展开别名
      var typeAliases = symbolTable.getTypeAliases();

      // 创建访问上下文
      var ctx = new VisitorContext(
        symbolTable,
        diagnostics,
        typeAliases, // 传入实际的类型别名映射
        TypeSystem.unknown(),
        VisitorContext.Effect.PURE
      );

      // 第二遍：检查所有声明
      if (module.decls != null) {
        for (var decl : module.decls) {
          checkDeclaration(decl, ctx);
        }
      }
    } finally {
      symbolTable.exitScope();
    }

    var baseDiagnostics = new ArrayList<>(diagnostics.getDiagnostics());
    if (module != null && module.decls != null) {
      var funcs = module.decls.stream()
        .filter(CoreModel.Func.class::isInstance)
        .map(CoreModel.Func.class::cast)
        .toList();
      baseDiagnostics.addAll(capabilityChecker.checkModule(funcs));
      // P0-1 (ADR-0009): PII flow 分析永远启用，不再依赖 env var。
      // 与 TypeScript 端 typecheckModule / typecheckBrowser 保持一致语义。
      // PII flow 分析本身 environment-agnostic（PiiChecker 不读 env / fs）。
      baseDiagnostics.addAll(piiChecker.checkModule(funcs));
    }

    return List.copyOf(baseDiagnostics);
  }

  // ========== 第一遍：收集类型定义 ==========

  /**
   * 收集模块中的所有类型定义（Data、Enum）
   */
  private void collectTypeDefinitions(CoreModel.Module module) {
    if (module == null || module.decls == null) return;
    for (var decl : module.decls) {
      switch (decl) {
        case CoreModel.Data data -> defineDataType(data);
        case CoreModel.Enum enumDecl -> defineEnumType(enumDecl);
        default -> {
          // Func 和 Import 在第二遍处理
        }
      }
    }
  }

  /**
   * 定义数据类型（product type）
   */
  private void defineDataType(CoreModel.Data data) {
    var typeName = new CoreModel.TypeName();
    typeName.name = data.name;
    typeName.origin = data.origin;

    symbolTable.define(
      data.name,
      typeName,
      SymbolInfo.SymbolKind.DATA_TYPE,
      new SymbolTable.DefineOptions(false, Optional.ofNullable(data.origin), false, Optional.empty(), Optional.empty())
    );
  }

  /**
   * 定义枚举类型（sum type）
   */
  private void defineEnumType(CoreModel.Enum enumDecl) {
    var typeName = new CoreModel.TypeName();
    typeName.name = enumDecl.name;
    typeName.origin = enumDecl.origin;

    symbolTable.define(
      enumDecl.name,
      typeName,
      SymbolInfo.SymbolKind.DATA_TYPE,
      new SymbolTable.DefineOptions(false, Optional.ofNullable(enumDecl.origin), false, Optional.empty(), Optional.empty())
    );
  }

  // ========== 第二遍：检查声明 ==========

  /**
   * 检查声明（Func、Data、Enum、Import）
   */
  private void checkDeclaration(CoreModel.Decl decl, VisitorContext ctx) {
    switch (decl) {
      case CoreModel.Func func -> checkFunction(func, ctx);
      case CoreModel.Import imp -> checkImport(imp);
      case CoreModel.Data data -> {
        // 数据类型已在第一遍处理
      }
      case CoreModel.Enum enumDecl -> {
        // 枚举类型已在第一遍处理
      }
    }
  }

  /**
   * 检查函数声明
   */
  private void checkFunction(CoreModel.Func func, VisitorContext ctx) {
    // 构建函数类型
    var funcType = new CoreModel.FuncType();
    funcType.params = func.params.stream().map(p -> p.type).toList();
    funcType.ret = func.ret;
    funcType.origin = func.origin;

    // 【修复】在模块作用域（当前作用域）注册函数符号，确保跨函数调用可见
    // 存储函数声明的最高级别效果，用于后续效果推断
    // 计算所有声明效果中的最大值（PURE < CPU < IO < ASYNC）
    Optional<String> declaredEffect;
    if (func.effects.isEmpty()) {
      declaredEffect = Optional.empty();
    } else {
      var maxEffect = EffectChecker.Effect.PURE;
      for (String effectStr : func.effects) {
        var effectEnum = EffectChecker.Effect.fromString(effectStr);
        if (effectEnum.compareTo(maxEffect) > 0) {
          maxEffect = effectEnum;
        }
      }
      declaredEffect = Optional.of(maxEffect.name());
    }

    symbolTable.define(
      func.name,
      funcType,
      SymbolInfo.SymbolKind.FUNCTION,
      new SymbolTable.DefineOptions(false, Optional.ofNullable(func.origin), false, Optional.empty(), declaredEffect)
    );

    // 进入函数作用域检查函数体
    symbolTable.enterScope(SymbolTable.ScopeType.FUNCTION);

    // 定义参数符号（在函数作用域内）
    for (var param : func.params) {
      symbolTable.define(
        param.name,
        param.type,
        SymbolInfo.SymbolKind.PARAMETER,
        new SymbolTable.DefineOptions(false, Optional.ofNullable(func.origin), false, Optional.empty(), Optional.empty())
      );
    }

    // 检查函数体
    if (func.body != null) {
      var bodyReturnType = baseChecker.checkBlock(func.body, ctx);

      // 验证返回类型（展开别名后再比较）
      if (bodyReturnType.isPresent()) {
        var expandedBodyType = baseChecker.expandType(bodyReturnType.get());
        var expandedDeclaredType = baseChecker.expandType(func.ret);
        
        if (!TypeSystem.equals(expandedBodyType, expandedDeclaredType, false)) {
          diagnostics.error(
            ErrorCode.RETURN_TYPE_MISMATCH,
            Optional.ofNullable(func.origin),
            Map.of(
              "expected", TypeSystem.format(func.ret),
              "actual", TypeSystem.format(bodyReturnType.get())
            )
          );
        }
      }

      // 检查效果兼容性：验证推断效果在声明效果范围内
      var inferredEffect = effectChecker.inferBlockEffect(func.body, ctx);

      if (func.effects.isEmpty()) {
        // 无声明效果 = PURE，直接检查
        effectChecker.checkEffectCompatibility(EffectChecker.Effect.PURE, inferredEffect, Optional.ofNullable(func.origin));
      } else {
        // 【修复】检查所有声明的效果，取最宽容的效果进行兼容性检查
        var maxDeclaredEffect = EffectChecker.Effect.PURE;
        for (String effectStr : func.effects) {
          var effectEnum = EffectChecker.Effect.fromString(effectStr);
          if (effectEnum.compareTo(maxDeclaredEffect) > 0) {
            maxDeclaredEffect = effectEnum;
          }
        }
        effectChecker.checkEffectCompatibility(maxDeclaredEffect, inferredEffect, Optional.ofNullable(func.origin));
      }

      // 检查异步纪律
      asyncChecker.checkFunction(func);
    }

    symbolTable.exitScope();
  }

  /**
   * 检查导入声明
   */
  private void checkImport(CoreModel.Import imp) {
    // 导入检查暂时简化：仅记录模块路径
    // 完整实现需要模块解析和符号导入
  }

  // ========== 内置类型别名 ==========

  /**
   * 定义内置类型别名（向后兼容）
   * <p>
   * Text 是 String 的历史遗留别名，为了向后兼容，在这里预定义。
   * 用户代码中使用 Text 的地方会自动展开为 String。
   * <p>
   * 【幂等性】如果别名已存在（TypeChecker 实例复用场景），跳过注册。
   */
  private void defineBuiltinTypeAliases() {
    // 检查是否已注册，避免重复调用时抛异常
    if (symbolTable.resolveTypeAlias(BuiltinTypes.TEXT).isEmpty()) {
      var stringType = new CoreModel.TypeName();
      stringType.name = BuiltinTypes.STRING;

      // 定义 Text = String（向后兼容旧代码）
      symbolTable.defineTypeAlias(BuiltinTypes.TEXT, stringType, Optional.empty());
    }
  }

  // ========== 辅助方法 ==========

  /**
   * 获取所有诊断结果
   */
  public List<Diagnostic> getDiagnostics() {
    return diagnostics.getDiagnostics();
  }

  /**
   * 获取符号表（用于调试）
   */
  public SymbolTable getSymbolTable() {
    return symbolTable;
  }

  /**
   * 获取基础类型检查器（用于测试）
   */
  BaseTypeChecker getBaseChecker() {
    return baseChecker;
  }

  /**
   * 获取泛型类型检查器（用于测试）
   */
  GenericTypeChecker getGenericChecker() {
    return genericChecker;
  }

  /**
   * 获取效果检查器（用于测试）
   */
  EffectChecker getEffectChecker() {
    return effectChecker;
  }

  /**
   * 获取异步纪律检查器（用于测试）
   */
  AsyncDisciplineChecker getAsyncChecker() {
    return asyncChecker;
  }

  /**
   * 注入 Manifest 配置（用于 CLI/测试自定义能力范围）。
   */
  public void setManifest(ManifestConfig manifest) {
    capabilityChecker.setManifest(manifest);
  }

  private void loadManifestFromEnv() {
    var manifestPath = System.getenv("ASTER_MANIFEST_PATH");
    if (manifestPath == null || manifestPath.isBlank()) {
      return;
    }
    try {
      var config = ManifestReader.read(Path.of(manifestPath));
      setManifest(config);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("加载 ASTER_MANIFEST_PATH 指定的 Manifest 失败: " + manifestPath, ex);
    }
  }

  /**
   * @deprecated 自 ADR-0009 (P0-1) 起，PII flow 分析永远启用。本函数仅保留为
   *   no-op stub，下一个 major release 移除。所有 caller 应当假定 PII 检查
   *   总是运行。
   *
   *   原渐进式 opt-in 策略（ENFORCE_PII / ASTER_ENFORCE_PII 环境变量）已是
   *   security hazard：同一策略在不同 runtime 报告不同安全结论，违背 Aster
   *   "PII as a first-class type" 承诺。
   *
   * @return always true
   */
  @Deprecated(since = "ADR-0009 P0-1", forRemoval = true)
  private boolean shouldEnforcePii() {
    return true;
  }
}
