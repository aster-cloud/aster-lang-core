package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.ir.CoreModel.*;
import aster.core.typecheck.*;
import aster.core.typecheck.governance.VerdictSignatures;
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
  /** 带点路径首段是否为已知限定名（模块导入别名）；TypeChecker 在预扫描后注入。 */
  private java.util.function.Predicate<String> knownQualifier = name -> false;

  public void setKnownQualifier(java.util.function.Predicate<String> knownQualifier) {
    this.knownQualifier = knownQualifier == null ? name -> false : knownQualifier;
  }

  /**
   * 调用检查中视为 Unknown 的类型实例（按引用识别）：
   * <ul>
   *   <li>只按名字推断出的类型：未写类型的形参 / lambda 形参的占位类型（如 amount → Float）、
   *       省略返回类型且按函数体推断失败时按规则名猜出的返回类型占位；</li>
   *   <li>泛型规则签名里的类型参数（`Rule id of T given x as T`，解析后是名为 T 的 TypeName）。
   *       代价：类型参数之间不做合一，会放过 `inc(id(t))`（t 为 Text）、`pick(t, 1)` 这类真实错误；
   *       TS 对含类型变量的形参同样放行，两引擎一致，待统一实现泛型合一后再收紧。</li>
   * </ul>
   * 实参比较与「调用目标是否为函数」两项检查把它们当作 Unknown，与 TS 一致：拿不准时放行。
   * 其他用途（悬浮提示、函数体内的检查、其余诊断）不变。按引用识别使其能随 Let 绑定、
   * 函数值、Some(...) 等原样传播，且不改动 IR。
   */
  private final java.util.Set<Type> uncheckedForCalls = Collections.newSetFromMap(new IdentityHashMap<>());

  /** 登记一个调用检查中视为 Unknown 的类型实例（见 {@link #uncheckedForCalls}）。 */
  public void markUncheckedForCalls(Type type) {
    if (type != null) {
      uncheckedForCalls.add(type);
    }
  }

  /** 登记类型中所有名字属于 {@code typeParams} 的 TypeName（泛型规则的类型参数）。 */
  public void markTypeParamsUncheckedForCalls(Type type, Collection<String> typeParams) {
    if (type == null || typeParams == null || typeParams.isEmpty()) {
      return;
    }
    switch (type) {
      case CoreModel.TypeName tn -> {
        if (typeParams.contains(tn.name)) {
          markUncheckedForCalls(tn);
        }
      }
      case CoreModel.TypeApp ta -> {
        if (ta.args != null) ta.args.forEach(arg -> markTypeParamsUncheckedForCalls(arg, typeParams));
      }
      case CoreModel.FuncType f -> {
        if (f.params != null) f.params.forEach(param -> markTypeParamsUncheckedForCalls(param, typeParams));
        markTypeParamsUncheckedForCalls(f.ret, typeParams);
      }
      case CoreModel.ListT l -> markTypeParamsUncheckedForCalls(l.type, typeParams);
      case CoreModel.Maybe m -> markTypeParamsUncheckedForCalls(m.type, typeParams);
      case CoreModel.Option o -> markTypeParamsUncheckedForCalls(o.type, typeParams);
      case CoreModel.Result r -> {
        markTypeParamsUncheckedForCalls(r.ok, typeParams);
        markTypeParamsUncheckedForCalls(r.err, typeParams);
      }
      case CoreModel.MapT m -> {
        markTypeParamsUncheckedForCalls(m.key, typeParams);
        markTypeParamsUncheckedForCalls(m.val, typeParams);
      }
      case CoreModel.PiiType pii -> markTypeParamsUncheckedForCalls(pii.baseType, typeParams);
      default -> {
      }
    }
  }

  /**
   * 赋值类位置（Set、Return、lambda 返回）的可赋值性：先把按名字推断的部分换成 Unknown（{@link #uncheckedForCalls}），
   * 再展开别名，按 {@link TypeSystem#isSubtype} 判定（数值拓宽、T → T?、None → T? 放行，收窄仍不放行，#232）。
   */
  public boolean isAssignable(Type value, Type target) {
    return TypeSystem.isSubtype(expandType(eraseUncheckedForCalls(value)), expandType(eraseUncheckedForCalls(target)));
  }

  /** 清空登记（每次检查模块前后调用，避免跨模块持有 IR 引用）。 */
  public void clearUncheckedForCalls() {
    uncheckedForCalls.clear();
  }

  /** 当前函数体的声明返回类型（null = 不逐条比对 Return，如 lambda 体或返回类型按名推断）。 */
  private Type declaredReturn_;

  // ========== 构造器 ==========

  public BaseTypeChecker(SymbolTable symbolTable, DiagnosticBuilder diagnostics, GenericTypeChecker genericChecker) {
    this.symbolTable = symbolTable;
    this.diagnostics = diagnostics;
    this.genericChecker = genericChecker;
  }

  // ========== 符号定义 ==========

  /**
   * 在当前作用域定义符号；重名时报 E104 DUPLICATE_SYMBOL 并继续，绝不抛异常。
   *
   * <p>★{@code typecheckModule} 的公开契约是返回诊断列表。重名（同名函数/Data/Enum、
   * 形参重名、形参后再 Let、同名 Let、Lambda 形参重名、{@code Ctor(x, x)} 模式绑定）
   * 都是最普通的用户输入，若以异常形式穿透会把整个模块
   * 的其它诊断一并吞掉，CLI 端只剩一段 Java 栈。所有符号定义站点必须统一走这里，
   * 与 TS 侧 {@code defineSymbol} 报 DUPLICATE_SYMBOL 后跳过定义的行为对齐。
   *
   * <p>ErrorCode.DUPLICATE_SYMBOL(E104) 本就用 {name} 占位符渲染，此处直接复用；
   * 诊断位置取 {@code options.span()}，与符号自身记录的位置一致。
   *
   * @return true 表示定义成功；false 表示重名（已记录诊断，符号表未改动）
   */
  public boolean defineOrReportDuplicate(
    String name,
    Type type,
    SymbolInfo.SymbolKind kind,
    SymbolTable.DefineOptions options
  ) {
    if (symbolTable.define(name, type, kind, options)) {
      return true;
    }
    diagnostics.error(
      ErrorCode.DUPLICATE_SYMBOL,
      options.span(),
      Map.of("name", name)
    );
    return false;
  }

  /**
   * 查找把 {@code variant} 声明为变体的枚举名（按模块声明顺序取第一个）。
   * 用于把裸变体名解析成枚举类型值；无命中返回空。
   */
  private static Optional<String> enumOwningVariant(String variant, VisitorContext ctx) {
    var enums = ctx.getEnumDecls();
    if (enums == null) {
      return Optional.empty();
    }
    for (var enumDecl : enums.values()) {
      if (enumDecl.variants != null && enumDecl.variants.contains(variant)) {
        return Optional.ofNullable(enumDecl.name);
      }
    }
    return Optional.empty();
  }

  /**
   * 限定枚举成员 {@code E.M}（ADR 0051）：与裸成员 {@code M} 同为枚举 E 的值。首段为局部值时不适用
   * （局部名优先，按字段路径处理）；首段为类型名或未登记时才按枚举成员判定。
   */
  private Optional<String> qualifiedEnumOf(String name, VisitorContext ctx) {
    var parts = name.split("\\.", -1);
    var enums = ctx.getEnumDecls();
    if (parts.length != 2 || enums == null) {
      return Optional.empty();
    }
    var head = symbolTable.lookup(parts[0]);
    if (head.isPresent() && head.get().kind() != SymbolInfo.SymbolKind.DATA_TYPE) {
      return Optional.empty();
    }
    var enumDecl = enums.get(parts[0]);
    boolean member = enumDecl != null && enumDecl.variants != null && enumDecl.variants.contains(parts[1]);
    return member ? Optional.of(enumDecl.name) : Optional.empty();
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
      case CoreModel.DecimalE dec -> createTypeName("Decimal");
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
          // 裸枚举变体（`status set to Rejected`）：符号表只登记枚举类型名，不登记变体，
          // 与 TS 一致——先查符号，查不到再在模块枚举里找同名变体，命中则取枚举类型。
          var owner = enumOwningVariant(name.name, ctx);
          if (owner.isPresent()) {
            yield createTypeName(owner.get());
          }
          if (!name.name.contains(".")) {
            diagnostics.undefinedVariable(name.name, Optional.ofNullable(name.origin));
            yield TypeSystem.unknown();
          }
          var qualified = qualifiedEnumOf(name.name, ctx);
          if (qualified.isPresent()) {
            yield createTypeName(qualified.get());
          }
          yield fieldPathType(name, ctx);
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
      case CoreModel.Construct construct -> checkConstruct(construct, ctx);

      // Await
      case CoreModel.Await await -> checkAwait(await, ctx);

      // ADR 0019 G2b：表达式级 if —— cond 须 Bool，类型 = 两分支统一后的类型。
      case CoreModel.IfE ifx -> checkIfExpr(ifx, ctx);

      // ADR 0024 C0：列表字面量 —— 类型为 List of <元素统一类型>。空列表元素类型 unknown；
      // 元素类型不一时取首元素类型（与 IfE 分支统一同理，留待更严的统一逻辑后续增强）。
      case CoreModel.ListE list -> {
        var listType = new CoreModel.ListT();
        if (list.elements == null || list.elements.isEmpty()) {
          listType.type = TypeSystem.unknown();
        } else {
          var firstType = typeOfExpr(list.elements.get(0), ctx);
          listType.type = firstType;
          // ★校验其余元素与首元素类型一致（issue #128 body）。
          //   此前这里只调 typeOfExpr 丢弃返回值——既不比较也不发诊断，
          //   注释声称的「不一致出诊断」是空话，`[1, "a", true]` 被静默定型为 List<Int>。
          //   E020 LIST_ELEMENT_TYPE_MISMATCH 早已在 ErrorCode 与 error_codes.json 里
          //   定义好，但全仓 emit 站点数为 0——本次把它接上。
          //
          //   非 strict 比较：unknown 与任何类型相容，避免在元素本身已出错时级联报错。
          //   不阻断——列表类型仍取首元素类型，与 IfE 分支不一致时取 then 分支同理。
          for (int i = 1; i < list.elements.size(); i++) {
            var elemType = typeOfExpr(list.elements.get(i), ctx);
            if (!TypeSystem.equals(firstType, elemType, false)) {
              diagnostics.error(
                ErrorCode.LIST_ELEMENT_TYPE_MISMATCH,
                Optional.ofNullable(list.origin),
                Map.of(
                  "expected", TypeSystem.format(firstType),
                  "actual", TypeSystem.format(elemType)
                )
              );
            }
          }
        }
        yield listType;
      }
    };
  }

  /**
   * 表达式级 if 类型推断（ADR 0019 G2b）：cond 须 Bool；类型 = then/else 两分支的统一类型。
   * 与语句级 {@link #checkIf} 区别：else 必需（表达式两方向都要有值），故总是比较两分支。
   * 分支类型不一致 → IF_BRANCH_MISMATCH（与语句级同一错误码），返回 then 分支类型继续推断。
   */
  private Type checkIfExpr(CoreModel.IfE ifx, VisitorContext ctx) {
    var condType = typeOfExpr(ifx.cond, ctx);
    if (!TypeSystem.equals(eraseUncheckedForCalls(condType), createTypeName("Bool"), false)) {
      diagnostics.typeMismatch(createTypeName("Bool"), condType, Optional.ofNullable(ifx.origin));
    }
    var thenType = typeOfExpr(ifx.thenE, ctx);
    var elseType = typeOfExpr(ifx.elseE, ctx);
    if (!TypeSystem.equals(thenType, elseType, false)) {
      diagnostics.error(
        ErrorCode.IF_BRANCH_MISMATCH,
        Optional.ofNullable(ifx.origin),
        Map.of(
          "then", TypeSystem.format(thenType),
          "else", TypeSystem.format(elseType)
        )
      );
    }
    return thenType;
  }

  /**
   * Set 语句（ADR 0052），诊断与 TS assignSymbol 逐码一致：
   * 未定义或不是局部名（函数、类型）→ E101；目标是形参、模式绑定，或 lambda 体内捕获的外层变量 → E001；
   * 值类型既不等于也不是目标类型的子类型 → E002。
   */
  private void checkSet(CoreModel.Set set, Type exprType) {
    var origin = Optional.ofNullable(set.origin);
    var symbol = symbolTable.lookup(set.name)
      .filter(s -> s.kind() != SymbolInfo.SymbolKind.FUNCTION && s.kind() != SymbolInfo.SymbolKind.DATA_TYPE);
    if (symbol.isEmpty()) {
      diagnostics.undefinedVariable(set.name, origin);
      return;
    }
    String immutable = immutableTargetLabel(set.name, symbol.get());
    if (immutable != null) {
      diagnostics.error(
        ErrorCode.TYPE_MISMATCH,
        origin,
        Map.of("expected", "a Let binding", "actual", immutable + " '" + set.name + "'")
      );
      return;
    }
    Type target = symbol.get().type();
    if (!isAssignable(exprType, target)) {
      diagnostics.error(
        ErrorCode.TYPE_MISMATCH_ASSIGN,
        origin,
        Map.of("name", set.name, "expected", TypeSystem.format(target), "actual", TypeSystem.format(exprType))
      );
    }
  }

  /**
   * 不可赋值目标在诊断里的英文称呼（与 TS 一致）；可赋值时返回 null。
   * lambda 按值捕获外层变量，体内 Set 捕获变量的语义未定义，故一律拒绝（ADR 0052 §2.2）。
   */
  private String immutableTargetLabel(String name, SymbolInfo symbol) {
    return switch (symbol.kind()) {
      case PARAMETER -> "parameter";
      case PATTERN -> "pattern binding";
      default -> !symbol.mutable() ? "immutable binding"
        : symbolTable.resolvesAcrossLambda(name) ? "captured variable" : null;
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
        // ADR 0052：Let 绑定可被 Set 赋值（写回其所在作用域）；形参、lambda 形参、模式绑定不可变。
        defineOrReportDuplicate(
          let.name,
          exprType,
          SymbolInfo.SymbolKind.VARIABLE,
          SymbolTable.DefineOptions.mutable(let.origin)
        );
        yield Optional.empty();
      }

      case CoreModel.Set set -> {
        checkSet(set, typeOfExpr(set.expr, ctx));
        yield Optional.empty();
      }

      case CoreModel.Return ret -> {
        var retType = typeOfExpr(ret.expr, ctx);
        checkReturnAgainstDeclared(ret, retType);
        yield Optional.of(retType);
      }

      case CoreModel.If ifStmt -> checkIf(ifStmt, ctx);

      case CoreModel.Match match -> checkMatch(match, ctx);

      case CoreModel.Block block -> checkBlock(block, ctx);

      case CoreModel.Scope scope -> {
        symbolTable.enterScope(SymbolTable.ScopeType.BLOCK);
        var result = Optional.<Type>empty();
        try {
          for (var s : scope.statements) {
            var stmtType = checkStatement(s, ctx);
            if (stmtType.isPresent()) {
              result = stmtType;
            }
          }
        } finally {
          // 块体检查抛异常也不能把作用域留在栈上，否则后续检查都在错误的作用域里进行
          symbolTable.exitScope();
        }
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
            // step 体与 compensate 体各有独立作用域（与 truffle#163 一致，#236）：体内 Let 不外泄，
            // 跨 step 读取另一 step 的 Let 报未定义；不同 step 的同名 Let 互不冲突。
            checkScopedBlock(step.body, ctx);
            checkScopedBlock(step.compensate, ctx);
          }
        }
        yield Optional.empty();
      }
    };
  }

  /** 在独立的块作用域中检查代码块（null 跳过）；finally 退出，异常也不把作用域留在栈上。 */
  private void checkScopedBlock(Block block, VisitorContext ctx) {
    if (block == null) {
      return;
    }
    symbolTable.enterScope(SymbolTable.ScopeType.BLOCK);
    // step / compensate 体内的 Return 是该 step 的结果（truffle WorkflowNode 捕获 ReturnException 作为
    // step 返回值），不是函数返回值，所以不与函数声明的返回类型比对（#239）。
    var saved = declaredReturn_;
    declaredReturn_ = null;
    try {
      checkBlock(block, ctx);
    } finally {
      declaredReturn_ = saved;
      symbolTable.exitScope();
    }
  }

  /**
   * 检查函数体：体内每条 Return（任意嵌套 If / Match 分支 / workflow step）都与声明返回类型比对（#239）。
   *
   * <p>块结果类型只保留最后一条有类型的语句，无 Otherwise 的 If 不产生类型，所以只比较块结果会漏掉
   * 分支里的提前 Return；改为在 Return 处逐条比对，E003 落在该 Return 的位置。
   * {@code declaredReturn} 为 null 表示不比对（按名推断的返回类型占位）。
   */
  public Optional<Type> checkFunctionBody(Block block, Type declaredReturn, VisitorContext ctx) {
    var saved = declaredReturn_;
    declaredReturn_ = declaredReturn;
    try {
      return checkBlock(block, ctx);
    } finally {
      declaredReturn_ = saved;
    }
  }

  /**
   * Return 与声明类型同一套可赋值规则：数值拓宽、T → T?、None → T? 放行，Unknown 与按名推断的类型视为未知（#232）。
   */
  private void checkReturnAgainstDeclared(CoreModel.Return ret, Type actual) {
    if (declaredReturn_ == null || isAssignable(actual, declaredReturn_)) {
      return;
    }
    diagnostics.error(
      ErrorCode.RETURN_TYPE_MISMATCH,
      Optional.ofNullable(ret.origin),
      Map.of(
        "expected", TypeSystem.format(declaredReturn_),
        "actual", TypeSystem.format(actual)
      )
    );
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
    // 运算符在 lower 后是 Call{Name(op)}，并非变量；跳过变量名查找，避免 UNDEFINED_VARIABLE 误报。
    // 与查找失败时的结果一致，目标类型视为 Unknown，由下方运算符分支各自决定返回类型。
    // 带点的调用目标（`List.contains`、`Decimal.round`、`Verdict.allow`）是内置命名空间函数：
    // TS 从不把它当 Name 表达式求类型（命名空间不是值符号），各自由下方专项分支或运行时校验；
    // 这里同样跳过，否则会对命名空间首段误报 UNDEFINED_VARIABLE。
    Type funcType = isOperatorTarget(call.target) || isNamespacedTarget(call.target) || isUndeclaredBuiltinTarget(call.target)
        ? TypeSystem.unknown()
        : typeOfExpr(call.target, ctx);

    // 特殊处理：not(x)
    if (call.target instanceof CoreModel.Name name && "not".equals(name.name)) {
      if (call.args.size() != 1) {
        diagnostics.error(
          ErrorCode.NOT_CALL_ARITY,
          Optional.ofNullable(call.origin),
          Map.of()
        );
      } else {
        rejectVerdictInBoolContext(typeOfExpr(call.args.get(0), ctx), call.origin);
      }
      return createTypeName("Bool");
    }

    // 算术 + 比较运算符（+/-/*///% 与 == != < <= > >=，lower 为 Call{Name op}）：Decimal↔Double
    // 混算编译期拦截（ADR 0025，与 TS expression.ts 对齐）。Double 是二进制浮点，与精确 Decimal
    // 混算注入舍入误差；Int/Long↔Decimal 精确提升放行。Codex 审查 P0：不止算术，比较运算符也须拦
    // ——`1.0m equals to 1.0` TS runtime 当 Int 提升、Truffle 抛错 = 双引擎分歧。算术任一
    // Decimal/Int/Long 组合 → 结果 Decimal（传播，使嵌套继续被检查）；比较返回 Bool（不在此设）。
    if (call.target instanceof CoreModel.Name opName
        && (isArithmeticOperator(opName.name) || isComparisonOperator(opName.name)) && call.args.size() == 2) {
      var lt = typeOfExpr(call.args.get(0), ctx);
      var rt = typeOfExpr(call.args.get(1), ctx);
      if (isEqualityOperator(opName.name)) {
        rejectVerdictInBoolContext(lt, call.origin);
        rejectVerdictInBoolContext(rt, call.origin);
      }
      boolean lDec = isTypeNamed(lt, "Decimal"), rDec = isTypeNamed(rt, "Decimal");
      boolean lDbl = isTypeNamed(lt, "Double"), rDbl = isTypeNamed(rt, "Double");
      if ((lDec && rDbl) || (lDbl && rDec)) {
        diagnostics.error(ErrorCode.DECIMAL_DOUBLE_MIXING, Optional.ofNullable(call.origin),
            Map.of("operator", opName.name));
      }
      if (isArithmeticOperator(opName.name)) {
        boolean lIntLong = isTypeNamed(lt, "Int") || isTypeNamed(lt, "Long");
        boolean rIntLong = isTypeNamed(rt, "Int") || isTypeNamed(rt, "Long");
        if ((lDec || rDec) && (lDec || lIntLong) && (rDec || rIntLong)) {
          return createTypeName("Decimal");
        }
      }
    }

    // Codex 审查 P0：Decimal.round(x,…)/Decimal.divide(x,y,…) 的 Decimal 操作数位不得是 Double。
    if (call.target instanceof CoreModel.Name fn
        && ("Decimal.round".equals(fn.name) || "Decimal.divide".equals(fn.name))) {
      int decimalArgCount = "Decimal.divide".equals(fn.name) ? 2 : 1;
      for (int i = 0; i < Math.min(decimalArgCount, call.args.size()); i++) {
        if (isTypeNamed(typeOfExpr(call.args.get(i), ctx), "Double")) {
          diagnostics.error(ErrorCode.DECIMAL_DOUBLE_MIXING, Optional.ofNullable(call.origin),
              Map.of("operator", fn.name));
        }
      }
    }

    // Verdict 内置模块（ADR 0039）：返回 Verdict 名义类型，参数全部 Text，个数固定。
    if (call.target instanceof CoreModel.Name vn && VerdictSignatures.hasVerdictPrefix(vn.name)) {
      var arity = VerdictSignatures.arityOf(vn.name);
      if (arity.isEmpty()) {
        diagnostics.undefinedVariable(vn.name, Optional.ofNullable(call.origin));
        return TypeSystem.unknown();
      }
      var args = call.args == null ? List.<Expr>of() : call.args;
      // 个数不符时仍逐个推断参数类型，保证参数内的未定义名照常报告（与 TS 对齐）
      var argTypes = args.stream().map(arg -> expandType(typeOfExpr(arg, ctx))).toList();
      if (args.size() != arity.getAsInt()) {
        diagnostics.error(ErrorCode.GOV_VERDICT_CALL_ARITY, Optional.ofNullable(call.origin),
            Map.of("func", vn.name, "expected", arity.getAsInt(), "actual", args.size()));
        return createTypeName(VerdictSignatures.TYPE_NAME);
      }
      // 参数类型先展开别名再比较：Text 是 String 的内置别名，`reason as Text` 必须放行
      var text = createTypeName("String");
      for (var argType : argTypes) {
        if (!TypeSystem.equals(argType, text, false)) {
          diagnostics.typeMismatch(text, argType, Optional.ofNullable(call.origin));
        }
      }
      return createTypeName(VerdictSignatures.TYPE_NAME);
    }

    // #268：局部名遮蔽同名规则；局部值类型已知且不可能是函数时报 E001（与 TS 同文案）。
    // 运行期 truffle / TS 解释器同样按局部值分派，不会改调同名规则。
    if (call.target instanceof CoreModel.Name local && isNonCallableLocal(local.name)) {
      for (var arg : call.args) {
        typeOfExpr(arg, ctx);
      }
      diagnostics.error(
        ErrorCode.TYPE_MISMATCH,
        Optional.ofNullable(call.origin),
        Map.of(
          "expected", "a function",
          "actual", "'" + local.name + "' of type " + TypeSystem.format(funcType)
        )
      );
      return TypeSystem.unknown();
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

    // 推断参数类型列表（只执行一次，避免重复推断）。比较用的类型先把按名字推断的部分与
    // 泛型类型参数换成 Unknown（形参与实参两侧都是），再展开别名；返回类型仍用原签名，不影响下游推断。
    // 诊断文本用原始（仅展开别名的）类型，不暴露擦除后的 Unknown。
    var rawArgTypes = call.args.stream().map(arg -> typeOfExpr(arg, ctx)).toList();
    var argTypes = rawArgTypes.stream()
      .map(arg -> expandType(eraseUncheckedForCalls(arg)))
      .toList();
    var paramTypes = ft.params.stream()
      .map(param -> expandType(eraseUncheckedForCalls(param)))
      .toList();

    // 检查是否是泛型函数调用，如果是则进行类型统一和替换
    if (containsTypeVar(expandedFt)) {
      var checkFt = new CoreModel.FuncType();
      checkFt.params = paramTypes;
      checkFt.ret = expandedFt.ret;
      checkFt.origin = expandedFt.origin;
      var bindings = genericChecker.unifyGenericCall(checkFt, argTypes, Optional.ofNullable(call.origin));

      // 参数类型检查（使用替换后的类型）
      for (int i = 0; i < Math.min(call.args.size(), paramTypes.size()); i++) {
        var argType = argTypes.get(i);
        var paramType = genericChecker.substituteTypeVars(paramTypes.get(i), bindings);
        if (!TypeSystem.isSubtype(argType, paramType)) {
          diagnostics.typeMismatch(genericChecker.substituteTypeVars(expandedFt.params.get(i), bindings),
            expandType(rawArgTypes.get(i)), Optional.ofNullable(call.origin));
        }
      }

      // 返回替换后的返回类型
      return genericChecker.substituteTypeVars(expandedFt.ret, bindings);
    } else {
      // 非泛型函数：可赋值性检查（数值拓宽、T → T?、容器协变等见 TypeSystem.isSubtype）
      for (int i = 0; i < Math.min(call.args.size(), paramTypes.size()); i++) {
        var argType = argTypes.get(i);
        var paramType = paramTypes.get(i);
        if (!TypeSystem.isSubtype(argType, paramType)) {
          diagnostics.typeMismatch(expandedFt.params.get(i), expandType(rawArgTypes.get(i)), Optional.ofNullable(call.origin));
        }
      }

      return carryUncheckedForCalls(ft.ret, expandedFt.ret);
    }
  }

  /**
   * 返回类型占位展开别名后（如 Text → 内置别名表里共享的 String 实例）会丢掉登记；
   * 复制一份展开结果再登记，让调用结果作为实参时同样不作约束。共享实例本身不登记。
   */
  private Type carryUncheckedForCalls(Type raw, Type expanded) {
    if (!uncheckedForCalls.contains(raw) || uncheckedForCalls.contains(expanded)) {
      return expanded;
    }
    if (!(expanded instanceof CoreModel.TypeName tn)) {
      return expanded;
    }
    var copy = new CoreModel.TypeName();
    copy.name = tn.name;
    copy.origin = tn.origin;
    markUncheckedForCalls(copy);
    return copy;
  }

  /**
   * 未声明的单段 builtin 调用目标（`add(1, 2)`、`divide(10, 2)`）：truffle 在没有同名规则时直接分派 builtin，
   * 能正常运行，不报 E101（#233，与 TS 一致）。有同名符号（规则、局部名）时照常按符号检查。
   */
  private boolean isUndeclaredBuiltinTarget(CoreModel.Expr target) {
    return target instanceof CoreModel.Name n && CallTargetBuiltins.isCallTarget(n.name)
      && symbolTable.lookup(n.name).isEmpty();
  }

  /**
   * 单段名字是否为类型已知、且不可能是函数的局部名（形参、Let、模式绑定）。
   * 函数类型、类型变量、Unknown 以及只按名字推断出的类型 / 泛型类型参数都视为可能可调用。
   * 与运行期 builtin 同名的局部名不算：truffle 在调用位置先分派 builtin（见 {@link CallTargetBuiltins}），
   * `given add as Int … add(1, 2)` 能正常运行。
   */
  private boolean isNonCallableLocal(String name) {
    if (name == null || name.contains(".") || isBuiltinOperator(name) || CallTargetBuiltins.isCallTarget(name)) {
      return false;
    }
    var symbol = symbolTable.lookup(name);
    if (symbol.isEmpty() || symbol.get().kind() == SymbolInfo.SymbolKind.FUNCTION
        || symbol.get().kind() == SymbolInfo.SymbolKind.DATA_TYPE) {
      return false;
    }
    var type = eraseUncheckedForCalls(symbol.get().type());
    var expanded = TypeSystem.isUnknown(type) ? type : expandType(type);
    return !(TypeSystem.isUnknown(expanded)
        || expanded instanceof CoreModel.FuncType
        || expanded instanceof CoreModel.TypeVar
        || (expanded instanceof CoreModel.PiiType pii && pii.baseType instanceof CoreModel.FuncType));
  }

  /** 把类型中调用检查视为未知的部分（见 {@link #uncheckedForCalls}）换成 Unknown，其余结构保持不变。 */
  private Type eraseUncheckedForCalls(Type type) {
    if (type == null || uncheckedForCalls.contains(type)) {
      return TypeSystem.unknown();
    }
    return switch (type) {
      case CoreModel.FuncType f -> {
        var out = new CoreModel.FuncType();
        out.params = f.params == null ? null : f.params.stream().map(this::eraseUncheckedForCalls).toList();
        out.ret = eraseUncheckedForCalls(f.ret);
        out.origin = f.origin;
        yield out;
      }
      case CoreModel.ListT l -> {
        var out = new CoreModel.ListT();
        out.type = eraseUncheckedForCalls(l.type);
        out.origin = l.origin;
        yield out;
      }
      case CoreModel.Maybe m -> {
        var out = new CoreModel.Maybe();
        out.type = eraseUncheckedForCalls(m.type);
        out.origin = m.origin;
        yield out;
      }
      case CoreModel.Option o -> {
        var out = new CoreModel.Option();
        out.type = eraseUncheckedForCalls(o.type);
        out.origin = o.origin;
        yield out;
      }
      case CoreModel.Result r -> {
        var out = new CoreModel.Result();
        out.ok = eraseUncheckedForCalls(r.ok);
        out.err = eraseUncheckedForCalls(r.err);
        out.origin = r.origin;
        yield out;
      }
      case CoreModel.MapT m -> {
        var out = new CoreModel.MapT();
        out.key = eraseUncheckedForCalls(m.key);
        out.val = eraseUncheckedForCalls(m.val);
        out.origin = m.origin;
        yield out;
      }
      case CoreModel.TypeApp ta -> {
        var out = new CoreModel.TypeApp();
        out.base = ta.base;
        out.args = ta.args == null ? null : ta.args.stream().map(this::eraseUncheckedForCalls).toList();
        out.origin = ta.origin;
        yield out;
      }
      case CoreModel.PiiType pii -> {
        var out = new CoreModel.PiiType();
        out.baseType = eraseUncheckedForCalls(pii.baseType);
        out.sensitivity = pii.sensitivity;
        out.category = pii.category;
        out.origin = pii.origin;
        yield out;
      }
      default -> type;
    };
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
    // finally 退出作用域：与 checkFunction 一致，形参重名或函数体检查抛异常
    // 都不能把 LAMBDA 作用域留在栈上，否则外层后续语句全在错误作用域里检查。
    try {
      for (var param : lambda.params) {
        if (param.typeInferred) {
          markUncheckedForCalls(param.type);
        }
        defineOrReportDuplicate(
          param.name,
          param.type,
          SymbolInfo.SymbolKind.PARAMETER,
          SymbolTable.DefineOptions.immutable(lambda.origin)
        );
      }

      // lambda 体的 Return 由下方按 lambda 声明类型整体比对，不与外层函数的返回类型比对
      var bodyType = checkFunctionBody(lambda.body, null, ctx);

      // 验证返回类型前先展开别名
      if (bodyType.isPresent()) {
        // 与规则 Return 同一套可赋值规则（数值拓宽、T → T?；按名字推断的类型视为未知，#232）
        if (!isAssignable(bodyType.get(), lambda.ret)) {
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
    } finally {
      symbolTable.exitScope();
    }

    // 返回函数类型
    var funcType = new CoreModel.FuncType();
    funcType.params = lambda.params.stream().map(p -> p.type).toList();
    funcType.ret = lambda.ret;
    return funcType;
  }

  /**
   * 检查构造器
   */
  private Type checkConstruct(CoreModel.Construct construct, VisitorContext ctx) {
    // ★2026-08-17 审计：此前这里是空壳（注释自述「简化实现」「完整实现需要查找 data
    //   声明并验证字段」），导致 FIELD_TYPE_MISMATCH / UNKNOWN_FIELD /
    //   MISSING_REQUIRED_FIELD 三个错误码在 Java 侧 emit 站点数为 **0**，
    //   而 TS 侧（typecheck/expression.ts 的 Construct 分支）全部实现。
    //
    //   同一段 CNL：TS 报 3 个诊断，Java **静默接受**。对合规引擎而言，
    //   「未覆盖的决策分支不告警」本身就是缺陷；更麻烦的是错误码表两侧
    //   byte-identical，制造了「表对齐 = 行为对齐」的假象——表对齐了，
    //   表背后的行为一侧根本不存在。
    //
    //   本实现逐条对齐 TS：未知字段 / 字段类型不匹配 / 缺失必填字段。
    var dataDecls = ctx.getDataDecls();
    var decl = dataDecls.get(construct.typeName);
    if (decl == null || decl.fields == null) {
      // 与 TS 一致：找不到 data 声明就不校验字段（该情形由其他检查负责报错，
      // 这里再报一次只会产生重复诊断）。
      return createTypeName(construct.typeName);
    }

    var provided = new java.util.HashSet<String>();
    if (construct.fields != null) {
      for (var field : construct.fields) {
        if (field == null || field.name == null) {
          continue;
        }
        provided.add(field.name);
        var schemaField = decl.fields.stream()
            .filter(f -> f.name != null && f.name.equals(field.name))
            .findFirst()
            .orElse(null);
        if (schemaField == null) {
          diagnostics.error(ErrorCode.UNKNOWN_FIELD, Optional.ofNullable(construct.origin),
              Map.of("field", field.name, "type", decl.name));
          continue;
        }
        if (field.expr == null || schemaField.type == null) {
          continue;
        }
        var valueType = typeOfExpr(field.expr, ctx);
        // 未写类型的字段：类型只是按字段名猜的，不作约束（#232，与 TS isFieldTypeInferred 一致）。
        // 值表达式照常推断（上一行），其中的未定义名等诊断不受影响。
        if (schemaField.typeInferred) {
          continue;
        }
        // 用 isSubtype 而非 equals：与 TS 的 isAssignable 一致，允许 Int → Float
        // 等数值提升。unknown 一侧跳过——推断不出类型时报「不匹配」是误报，
        // 那属于类型推断的问题，不该在这里制造噪声。
        if (TypeSystem.isUnknown(valueType) || TypeSystem.isUnknown(schemaField.type)) {
          continue;
        }
        // ★必须先展开类型别名再比较：本仓预置了 `Text = String` 这个历史别名
        //   （见 TypeChecker 里的别名播种），字符串字面量推断出的是 String，
        //   而 data 声明常写 Text——不展开就会对**完全正确**的构造器报
        //   FIELD_TYPE_MISMATCH（实测确认过这个误报）。
        //   同 checkAwait 的做法，用 ctx 的别名表展开两侧。
        //   按名字推断类型的值（未写类型的形参等）视为未知，不作约束（#232）。
        var aliases = ctx.getTypeAliases();
        var expectedType = TypeSystem.expand(schemaField.type, aliases);
        var actualType = TypeSystem.expand(valueType, aliases);
        if (!TypeSystem.isSubtype(TypeSystem.expand(eraseUncheckedForCalls(valueType), aliases), expectedType)) {
          diagnostics.error(ErrorCode.FIELD_TYPE_MISMATCH,
              Optional.ofNullable(field.expr instanceof CoreModel.Name n ? n.origin : construct.origin),
              Map.of("field", field.name,
                  "expected", TypeSystem.format(expectedType),
                  "actual", TypeSystem.format(actualType)));
        }
      }
    }

    for (var schemaField : decl.fields) {
      if (schemaField.name != null && !provided.contains(schemaField.name)) {
        diagnostics.error(ErrorCode.MISSING_REQUIRED_FIELD, Optional.ofNullable(construct.origin),
            Map.of("type", decl.name, "field", schemaField.name));
      }
    }

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
    // 条件的类型只来自名字推断（`If approved_status:`）时视为未知（#232）
    if (!TypeSystem.equals(eraseUncheckedForCalls(condType), createTypeName("Bool"), false)) {
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
   * 把一个模式引入的绑定变量定义进**当前**作用域。
   *
   * <p>★绑定名集合必须与 {@code CoreLowering.patternBindings}（该文件第 942 行）一致，
   * 否则「lambda 捕获分析认为存在的绑定」与「类型检查器认为存在的绑定」会分叉，
   * 出现一边报错一边不报的诡异行为。
   *
   * <p>{@code PatNull} / {@code PatInt} 不引入绑定。
   *
   * @param scrutineeType 被匹配表达式的类型；{@code PatName} 原样绑定整个值，故取此类型
   */
  private void definePatternBindings(
    CoreModel.Pattern pattern,
    Type scrutineeType,
    Origin origin
  ) {
    if (pattern == null) {
      return;
    }
    if (pattern instanceof CoreModel.PatName name) {
      if (name.name != null) {
        // ★整个被匹配值原样绑定到该名字，故其类型**就是**被匹配表达式的类型。
        //   这个类型此前被算出来后直接丢弃（`var exprType = ...` 从未被使用）。
        defineOrReportDuplicate(
          name.name,
          scrutineeType,
          SymbolInfo.SymbolKind.PATTERN,
          SymbolTable.DefineOptions.immutable(origin)
        );
      }
      return;
    }
    if (pattern instanceof CoreModel.PatCtor ctor) {
      // ★构造器绑定只能记为 unknown：`CoreModel.Enum` 只有
      //   `List<String> variants`，**不携带变体的载荷类型**（见 CoreModel:94）。
      //   IR 层根本没有可供推导的字段类型，凭空编一个会让后续比较给出错误结论。
      //   unknown 是本检查器既有的「类型不可确定，不要级联报错」哨兵
      //   （typeOfExpr 对未知名称、Ok/Err 的 err 侧都用它）。
      //   精确推导需先给 Enum 加载荷类型，那是独立的语言层变更。
      // 与 truffle PatCtorNode 一致：位置 i 有嵌套模式（args）时以 args 为准，names 只补 args 之后的位置。
      // lowering 对 `Some(v)` 同时产出 names=[v] 与 args=[PatName v]，两处都登记会报假 E104（#233）。
      int argCount = ctor.args == null ? 0 : ctor.args.size();
      if (ctor.names != null) {
        for (String bound : ctor.names.subList(Math.min(argCount, ctor.names.size()), ctor.names.size())) {
          if (bound != null) {
            defineOrReportDuplicate(
              bound,
              TypeSystem.unknown(),
              SymbolInfo.SymbolKind.PATTERN,
              SymbolTable.DefineOptions.immutable(origin)
            );
          }
        }
      }
      if (ctor.args != null) {
        for (CoreModel.Pattern arg : ctor.args) {
          // 嵌套模式：载荷类型未知，故嵌套 PatName 也只能是 unknown
          definePatternBindings(arg, TypeSystem.unknown(), origin);
        }
      }
    }
    // PatNull / PatInt 不引入绑定
  }

  /**
   * 检查 Match 语句
   */
  private Optional<Type> checkMatch(CoreModel.Match match, VisitorContext ctx) {
    // 检查被匹配的表达式
    var exprType = typeOfExpr(match.expr, ctx);

    // 检查所有分支
    //
    // ★每个分支必须在**独立作用域**里检查，并先把该分支模式引入的绑定变量
    //   定义进去（issue #132）。此前这里直接 checkStatement(kase.body)，
    //   全程不碰 kase.pattern —— 于是 `match x { case Some(v) -> v }` 里的 v
    //   必然报假 UNDEFINED_VARIABLE，模式匹配这条路径实际不可用。
    //
    //   证据链：CoreLowering:822 的 lambda 捕获分析已经调用 patternBindings(kase.pattern())，
    //   说明「模式会引入绑定」在 IR 层是既定事实，只是类型检查器没跟上。
    Type firstCaseType = null;
    for (var kase : match.cases) {
      symbolTable.enterScope(SymbolTable.ScopeType.BLOCK);
      Optional<Type> caseType;
      try {
        definePatternBindings(kase.pattern, exprType, kase.origin);
        caseType = checkStatement(kase.body, ctx);
      } finally {
        // finally：分支体检查抛异常也不能把作用域留在栈上，
        // 否则后续所有检查都在错误的作用域里进行（毒化全局状态）。
        symbolTable.exitScope();
      }
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

    checkMatchExhaustiveness(match, exprType, ctx);

    return firstCaseType != null ? Optional.of(firstCaseType) : Optional.empty();
  }

  /**
   * match 穷尽性检查（issue #148），语义与 TS 侧 statement.ts 的 Match 分支逐条对齐。
   *
   * <p>★E016/E017/E018 早已定义并写进 error_codes.json，但 Java 侧此前 emit 站点数为 0——
   * 同一段 CNL 在 TS 引擎有警告、在 Java 引擎静默通过，且码表 byte-identical 制造了
   * 「表对齐 = 行为对齐」的假象。
   *
   * <p>只对可枚举的域做判断：Maybe（null / 非 null 两侧）与 enum（变体集合）。
   * Int/String 等无穷域既不能列举也无通配概念，与 TS 一致不告警。
   * 被匹配值类型 unknown 时不判——上游已经出错，不级联。
   */
  private void checkMatchExhaustiveness(CoreModel.Match match, Type scrutineeType, VisitorContext ctx) {
    var type = expandType(scrutineeType);
    if (type == null || TypeSystem.isUnknown(type) || match.cases == null) {
      return;
    }
    if (type instanceof CoreModel.Maybe) {
      checkMaybeExhaustiveness(match);
      return;
    }
    if (type instanceof CoreModel.TypeName typeName) {
      var enumDecl = ctx.getEnumDecls().get(typeName.name);
      if (enumDecl != null && enumDecl.variants != null) {
        checkEnumExhaustiveness(match, enumDecl);
      }
    }
  }

  /** Maybe 必须同时覆盖 null（PatNull）与非 null（其余任何模式）两侧。 */
  private void checkMaybeExhaustiveness(CoreModel.Match match) {
    boolean hasNullCase = false;
    boolean hasNonNullCase = false;
    for (var kase : match.cases) {
      if (kase.pattern instanceof CoreModel.PatNull) {
        hasNullCase = true;
      } else {
        hasNonNullCase = true;
      }
    }
    if (hasNullCase && hasNonNullCase) {
      return;
    }
    // missing 文案与 TS 侧逐字一致，双引擎诊断可直接对比
    String missing = hasNullCase ? "non-null value" : hasNonNullCase ? "null" : "null and non-null";
    diagnostics.warning(
      ErrorCode.NON_EXHAUSTIVE_MAYBE,
      Optional.ofNullable(match.origin),
      Map.of("missing", missing)
    );
  }

  /**
   * enum：变体可用 PatCtor（{@code Red()}）或 PatName（{@code Red}）覆盖；
   * 非变体名的 PatName 以及 PatNull/PatInt 视为通配，覆盖其余全部变体。
   * 同一变体出现两次报 DUPLICATE_ENUM_CASE（不影响穷尽性结论）。
   */
  private void checkEnumExhaustiveness(CoreModel.Match match, CoreModel.Enum enumDecl) {
    var seen = new LinkedHashSet<String>();
    boolean hasWildcard = false;
    for (var kase : match.cases) {
      String variant = coveredVariant(kase.pattern, enumDecl);
      if (variant == null) {
        // PatCtor 写了非变体的构造器名：既不覆盖任何变体也不是通配（与 TS 一致）
        hasWildcard |= !(kase.pattern instanceof CoreModel.PatCtor);
        continue;
      }
      if (!seen.add(variant)) {
        diagnostics.warning(
          ErrorCode.DUPLICATE_ENUM_CASE,
          Optional.ofNullable(kase.origin != null ? kase.origin : match.origin),
          Map.of("case", variant, "type", enumDecl.name)
        );
      }
    }
    if (hasWildcard) {
      return;
    }
    var missing = enumDecl.variants.stream().filter(v -> !seen.contains(v)).toList();
    if (!missing.isEmpty()) {
      diagnostics.warning(
        ErrorCode.NON_EXHAUSTIVE_ENUM,
        Optional.ofNullable(match.origin),
        Map.of("type", enumDecl.name, "missing", String.join(", ", missing))
      );
    }
  }

  /** 模式覆盖的 enum 变体名；不覆盖任何变体时返回 null。 */
  private static String coveredVariant(CoreModel.Pattern pattern, CoreModel.Enum enumDecl) {
    String name = switch (pattern) {
      case CoreModel.PatName patName -> patName.name;
      case CoreModel.PatCtor patCtor -> patCtor.typeName;
      case null, default -> null;
    };
    return name != null && enumDecl.variants.contains(name) ? name : null;
  }

  /**
   * 创建类型名
   */
  private CoreModel.TypeName createTypeName(String name) {
    var type = new CoreModel.TypeName();
    type.name = name;
    return type;
  }

  /** 调用目标是否为内置运算符名（与 AstBuilder 产出的 Name 拼写一致）。 */
  /** 带点的 Name 调用目标：内置命名空间函数，不是值符号。 */
  private static boolean isNamespacedTarget(CoreModel.Expr target) {
    return target instanceof CoreModel.Name name && name.name != null && name.name.contains(".");
  }

  private boolean isOperatorTarget(CoreModel.Expr target) {
    return target instanceof CoreModel.Name name && isBuiltinOperator(name.name);
  }

  /** 内置运算符：算术、比较，以及逻辑 not/and/or。 */
  private boolean isBuiltinOperator(String op) {
    return isArithmeticOperator(op) || isComparisonOperator(op)
        || "not".equals(op) || "and".equals(op) || "or".equals(op);
  }

  /** 算术运算符（lower 后的 Call target Name）：+/-/*///%。 */
  private boolean isArithmeticOperator(String op) {
    return "+".equals(op) || "-".equals(op) || "*".equals(op)
        || "/".equals(op) || "//".equals(op) || "%".equals(op);
  }

  /** 比较运算符（lower 后的 Call target Name）：== != < <= > >=。 */
  private boolean isComparisonOperator(String op) {
    return "==".equals(op) || "!=".equals(op) || "<".equals(op)
        || "<=".equals(op) || ">".equals(op) || ">=".equals(op);
  }

  /** 相等运算符（lower 后的 Call target Name）：== !=。 */
  private boolean isEqualityOperator(String op) {
    return "==".equals(op) || "!=".equals(op);
  }

  /**
   * Verdict 不可当布尔用（ADR 0039 §2.2）：出现在 not 操作数或相等比较操作数位时报 TYPE_MISMATCH(expected Bool)。
   * 只针对 Verdict，避免影响既有语料中其它类型的相等比较。
   */
  private void rejectVerdictInBoolContext(Type type, Origin origin) {
    if (isTypeNamed(type, VerdictSignatures.TYPE_NAME)) {
      diagnostics.typeMismatch(createTypeName("Bool"), type, Optional.ofNullable(origin));
    }
  }

  /**
   * 解析带点的字段访问（`application.creditScore`），与 TS 的 Name 分支对齐：
   * - 首段不是符号：按 TS 报 UNDEFINED_VARIABLE（E101）；例外只有模块导入别名。内置命名空间
   *   （`Decimal.round`、`Verdict.allow`）只出现在调用目标位置，由 checkCall 跳过，不会进入本方法。
   * - 中途类型为已声明 Data：字段不存在报 UNKNOWN_FIELD（E011），这是运行时 "无法访问成员" 的静态来源。
   * - 中途类型未知或不是已声明 Data：像 TS 一样先按字段名在全部 Data 里反推（唯一命中，或按首段名收窄到唯一），
   *   反推不到才报 E011。
   */
  private Type fieldPathType(CoreModel.Name name, VisitorContext ctx) {
    var parts = name.name.split("\\.");
    var head = symbolTable.lookup(parts[0]);
    if (head.isEmpty()) {
      // 与 TS 一致：首段不是符号就报 E101。唯一例外是模块导入别名（TS 的 imports 表在 Java 里
      // 由 TypeChecker 注入）；命名空间调用目标已在 checkCall 跳过，不会走到这里。
      if (!knownQualifier.test(parts[0])) {
        diagnostics.undefinedVariable(parts[0], Optional.ofNullable(name.origin));
        return TypeSystem.unknown();
      }
      return verdictFieldType(name.name);
    }
    if (head.get().kind() == SymbolInfo.SymbolKind.DATA_TYPE) {
      // 首段是类型名（`Status.Approved` 这类限定名）：TS 的符号表只登记值，类型名查不到即 E101；
      // Java 的符号表把类型名也登记为 DATA_TYPE，这里显式对齐。调用形式（`Verdict.deny(...)`）
      // 已在 checkCall 跳过，不会进入本方法。
      diagnostics.undefinedVariable(parts[0], Optional.ofNullable(name.origin));
      return TypeSystem.unknown();
    }
    Type current = head.get().type();
    for (int i = 1; i < parts.length; i++) {
      final String fieldName = parts[i];
      // 动态值（Unknown，例如无类型字段）上的成员访问结果仍为 Unknown，不报 E011（与 TS 对齐）
      if (TypeSystem.isUnknown(current)) {
        return TypeSystem.unknown();
      }
      var expanded = expandType(current);
      if (isTypeNamed(expanded, VerdictSignatures.TYPE_NAME) && VerdictSignatures.isVerdictField(fieldName)) {
        current = createTypeName("String");
        continue;
      }
      // 与 TS 一致：只有 TypeName（含 Unknown 与未声明的名字）才按字段名反推；
      // List/Map/函数类型等已知非记录类型直接报 E011，不能因别的 Data 恰好有同名字段而放行。
      CoreModel.Data data = null;
      if (expanded instanceof CoreModel.TypeName tn && tn.name != null) {
        data = ctx.getDataDecls().get(tn.name);
        if (data == null) {
          data = inferDataDeclFromField(ctx, parts[0], fieldName);
        }
      }
      if (data == null) {
        diagnostics.error(
          ErrorCode.UNKNOWN_FIELD,
          Optional.ofNullable(name.origin),
          Map.of("field", fieldName, "type", TypeSystem.format(current))
        );
        return TypeSystem.unknown();
      }
      final CoreModel.Data owner = data;
      var field = owner.fields == null ? Optional.<CoreModel.Field>empty()
          : owner.fields.stream().filter(f -> fieldName.equals(f.name)).findFirst();
      if (field.isEmpty()) {
        diagnostics.error(
          ErrorCode.UNKNOWN_FIELD,
          Optional.ofNullable(name.origin),
          Map.of("field", fieldName, "type", owner.name)
        );
        return TypeSystem.unknown();
      }
      // 未声明类型的字段是动态值（Action Guard 的 context 即无类型 map，ADR 0040 §2.2）
      current = field.get().typeInferred || field.get().type == null ? TypeSystem.unknown() : field.get().type;
    }
    return current;
  }

  /**
   * 按字段名反推所属 Data（TS inferDataDeclFromField 的移植）：恰有一个 Data 含该字段则取之；
   * 多个时按首段变量名（小写、去掉 _ 和 -）是否出现在 Data 名里收窄到唯一；否则放弃。
   */
  private static CoreModel.Data inferDataDeclFromField(VisitorContext ctx, String baseName, String fieldName) {
    var matches = new java.util.ArrayList<CoreModel.Data>();
    for (var data : ctx.getDataDecls().values()) {
      if (data.fields != null && data.fields.stream().anyMatch(f -> fieldName.equals(f.name))) {
        matches.add(data);
      }
    }
    if (matches.isEmpty()) {
      return null;
    }
    if (matches.size() == 1) {
      return matches.get(0);
    }
    var loweredBase = baseName.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
    var narrowed = matches.stream()
        .filter(d -> d.name != null && d.name.toLowerCase(java.util.Locale.ROOT).contains(loweredBase))
        .toList();
    return narrowed.size() == 1 ? narrowed.get(0) : null;
  }

  /**
   * 限定名的兜底类型（首段不是值符号时由 {@link #fieldPathType} 调用）：首段是 Verdict 值且字段为
   * outcome/role/reason 时为 Text，其余 Unknown。
   */
  private Type verdictFieldType(String dotted) {
    int dot = dotted.indexOf('.');
    if (dot <= 0) {
      return TypeSystem.unknown();
    }
    var head = symbolTable.lookup(dotted.substring(0, dot));
    var field = dotted.substring(dot + 1);
    if (head.isPresent() && isTypeNamed(head.get().type(), VerdictSignatures.TYPE_NAME)
        && VerdictSignatures.isVerdictField(field)) {
      // 与字符串字面量同用规范名 String（Text 是其内置别名）
      return createTypeName("String");
    }
    return TypeSystem.unknown();
  }

  /** 类型是否为指定名的 TypeName（如 "Decimal"/"Double"/"Int"/"Long"）。 */
  private boolean isTypeNamed(Type t, String name) {
    return t instanceof CoreModel.TypeName tn && name.equals(tn.name);
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
      // PII 包装内层同样展开别名（`@pii(L2, email) Text` → String），否则 PII 实参传给 Text 形参时
      // 剥壳后 Text 与 String 不等，在 E073 之外多报 E001（#233）
      case CoreModel.PiiType pii -> {
        var expanded = new CoreModel.PiiType();
        expanded.baseType = expandType(pii.baseType);
        expanded.sensitivity = pii.sensitivity;
        expanded.category = pii.category;
        expanded.origin = pii.origin;
        yield expanded;
      }
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
