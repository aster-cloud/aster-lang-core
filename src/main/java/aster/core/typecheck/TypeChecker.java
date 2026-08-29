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
  private final java.util.Set<String> importAliases = new HashSet<>();
  private final java.util.Set<String> moduleTopLevelNames = new HashSet<>();

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
    importAliases.clear();
    moduleTopLevelNames.clear();
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);

    try {
      // 定义内置类型别名（向后兼容）
      defineBuiltinTypeAliases();

      // 第一遍：收集类型定义
      collectTypeDefinitions(module);
      collectTopLevelNames(module);
      validateEntryAnnotations(module);

      // 【修复】注入类型别名映射，使下游检查器可以展开别名
      var typeAliases = symbolTable.getTypeAliases();

      // data 声明表：构造器字段校验需要字段列表，而 SymbolTable 只登记类型名
      // （defineDataType 丢弃了 fields）。★2026-08-17 审计：此前 checkConstruct
      // 是空壳，FIELD_TYPE_MISMATCH / UNKNOWN_FIELD / MISSING_REQUIRED_FIELD
      // 在 Java 侧 emit 站点数为 0，而 TS 侧全部实现。
      var dataDecls = new java.util.HashMap<String, CoreModel.Data>();
      if (module.decls != null) {
        for (var decl : module.decls) {
          if (decl instanceof CoreModel.Data data && data.name != null) {
            dataDecls.put(data.name, data);
          }
        }
      }

      // 创建访问上下文
      var ctx = new VisitorContext(
        symbolTable,
        diagnostics,
        typeAliases, // 传入实际的类型别名映射
        TypeSystem.unknown(),
        VisitorContext.Effect.PURE,
        dataDecls
      );

      // 第一遍补充：预注册全部函数签名（issue #125 body）。
      //
      // ★必须先把**所有**函数签名入表，再检查任何一个函数体。否则符号可见性
      //   取决于声明顺序：`main` 写在 `helper` 之前 → 检查 main 体时 helper 未入表
      //   → 假 UNDEFINED_VARIABLE；互递归则无论怎么排序都必有一侧报错。
      //   与 Data/Enum 的第一遍预扫描同理——签名先行，函数体后检查。
      if (module.decls != null) {
        for (var decl : module.decls) {
          if (decl instanceof CoreModel.Func func) {
            defineFunctionSignature(func);
          }
        }
      }

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
      // 模块名仅用于 CAPABILITY_NOT_ALLOWED 的消息文案（对齐 TS 的 {module} 参数）
      capabilityChecker.setModuleName(module.name);
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
   * 把单个函数的签名注册进**模块作用域**。
   *
   * <p>★必须在检查任何函数体**之前**对全部函数跑完一轮，否则前向引用与互递归
   * 必报假 {@code UNDEFINED_VARIABLE}（issue #125 body）：`main` 写在 `helper`
   * 之前时，检查 main 体的那一刻 helper 还没入表；互递归则无论怎么排序都必有一侧报错。
   *
   * <p>只注册签名，不碰函数体——函数体在第二遍逐个检查。
   */
  private void defineFunctionSignature(CoreModel.Func func) {
    // 构建函数类型
    var funcType = new CoreModel.FuncType();
    funcType.params = func.params.stream().map(p -> p.type).toList();
    funcType.ret = func.ret;
    funcType.origin = func.origin;

    // 存储函数声明的最高级别效果，用于后续效果推断
    // 计算所有声明效果中的最大值（PURE < CPU < IO）
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
  }

  /**
   * 检查函数声明
   */
  private void checkFunction(CoreModel.Func func, VisitorContext ctx) {
    // 签名已在 collectFunctionSignatures 预扫描阶段注册，此处只检查函数体。

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
    // TypeChecker 当前只看到单个 CoreModel.Module，没有 aster-api 解析后的
    // ModuleGraph，因此不能验证外部模块存在性或导出符号集合。这里先做本模块
    // 内可确定的轻量冲突检查；跨模块符号解析留给 ModuleResolver/linker 集成强化。
    var visibleName = importVisibleName(imp);
    if (visibleName == null || visibleName.isBlank()) {
      return;
    }
    if (!importAliases.add(visibleName)) {
      diagnostics.warning(
        ErrorCode.IMPORT_SYMBOL_CONFLICT,
        Optional.ofNullable(imp.origin),
        Map.of("symbol", visibleName, "reason", "duplicate import alias")
      );
    }
    if (moduleTopLevelNames.contains(visibleName)) {
      diagnostics.warning(
        ErrorCode.IMPORT_SYMBOL_CONFLICT,
        Optional.ofNullable(imp.origin),
        Map.of("symbol", visibleName, "reason", "conflicts with local top-level declaration")
      );
    }
  }

  private void collectTopLevelNames(CoreModel.Module module) {
    if (module == null || module.decls == null) {
      return;
    }
    for (var decl : module.decls) {
      switch (decl) {
        case CoreModel.Func func -> moduleTopLevelNames.add(func.name);
        case CoreModel.Data data -> moduleTopLevelNames.add(data.name);
        case CoreModel.Enum enumDecl -> moduleTopLevelNames.add(enumDecl.name);
        case CoreModel.Import ignored -> {
        }
      }
    }
    moduleTopLevelNames.remove(null);
  }

  private String importVisibleName(CoreModel.Import imp) {
    if (imp == null) {
      return null;
    }
    if (imp.alias != null && !imp.alias.isBlank()) {
      return imp.alias;
    }
    if (imp.path == null || imp.path.isBlank()) {
      return null;
    }
    var dot = imp.path.lastIndexOf('.');
    return dot >= 0 && dot < imp.path.length() - 1 ? imp.path.substring(dot + 1) : imp.path;
  }

  /**
   * 校验模块级 @entry 唯一性。
   */
  private void validateEntryAnnotations(CoreModel.Module module) {
    if (module == null || module.decls == null) {
      return;
    }

    var entryFuncs = module.decls.stream()
      .filter(CoreModel.Func.class::isInstance)
      .map(CoreModel.Func.class::cast)
      .filter(this::hasEntryAnnotation)
      .toList();

    if (entryFuncs.size() <= 1) {
      return;
    }

    var ruleNames = entryFuncs.stream()
      .map(func -> func.name)
      .filter(Objects::nonNull)
      .toList();
    diagnostics.error(
      ErrorCode.MULTIPLE_ENTRY_RULES,
      Optional.ofNullable(entryFuncs.get(0).origin),
      Map.of("rules", String.join(", ", ruleNames))
    );
  }

  private boolean hasEntryAnnotation(CoreModel.Func func) {
    return func.annotations != null && func.annotations.stream()
      .anyMatch(annotation -> annotation != null && "entry".equals(annotation.name));
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

}
