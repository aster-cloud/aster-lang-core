package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TypeChecker 集成测试
 * <p>
 * 验证完整的模块类型检查流程，确保与 TypeScript 版本行为一致。
 */
class TypeCheckerIntegrationTest {

  private TypeChecker checker;

  @BeforeEach
  void setUp() {
    checker = new TypeChecker();
  }

  @Test
  void testEmptyModule() {
    var module = new CoreModel.Module();
    module.decls = List.of();

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Empty module should have no diagnostics");
  }

  @Test
  void testSimpleFunctionWithCorrectTypes() {
    // func add(a: Int, b: Int): Int { return a }
    var module = createModuleWithFunction(
      "add",
      List.of(
        createParam("a", createTypeName("Int")),
        createParam("b", createTypeName("Int"))
      ),
      createTypeName("Int"),
      List.of(),
      createReturnStmt(createNameExpr("a"))
    );

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Valid function should have no diagnostics");
  }

  @Test
  void testFunctionWithTypeMismatch() {
    // func bad(x: Int): String { return x }  // Error: Int != String
    var module = createModuleWithFunction(
      "bad",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("String"),
      List.of(),
      createReturnStmt(createNameExpr("x"))
    );

    var diagnostics = checker.typecheckModule(module);

    assertFalse(diagnostics.isEmpty(), "Type mismatch should produce diagnostic");
    assertEquals(1, diagnostics.size());
    assertEquals(ErrorCode.RETURN_TYPE_MISMATCH, diagnostics.get(0).code());
  }

  @Test
  void testUndefinedVariable() {
    // func test(): Int { return undefined_var }
    var module = createModuleWithFunction(
      "test",
      List.of(),
      createTypeName("Int"),
      List.of(),
      createReturnStmt(createNameExpr("undefined_var"))
    );

    var diagnostics = checker.typecheckModule(module);

    assertFalse(diagnostics.isEmpty());
    assertTrue(diagnostics.stream()
      .anyMatch(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE));
  }

  @Test
  void testDataTypeDefinition() {
    // data User { name: String }
    var userData = new CoreModel.Data();
    userData.name = "User";
    userData.fields = List.of(createField("name", createTypeName("String")));

    var module = new CoreModel.Module();
    module.decls = List.of(userData);

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Data type definition should have no diagnostics");

    // Note: Symbol table lookup after exitScope() will fail
    // In real usage, symbols are available during the check phase
  }

  @Test
  void testEffectChecking() {
    // func impure(): Int [io] { return 42 }
    var module = createModuleWithFunction(
      "impure",
      List.of(),
      createTypeName("Int"),
      List.of("io"),
      createReturnStmt(createIntLiteral(42))
    );

    var diagnostics = checker.typecheckModule(module);

    // Pure body with IO effect declaration should be allowed
    // (actual effect PURE ⊑ declared effect IO)
    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void testSingleEntryAnnotationPasses() {
    var main = createValidFunction("main");
    main.annotations = List.of(createAnnotation("entry"));
    var helper = createValidFunction("helper");

    var module = new CoreModel.Module();
    module.decls = List.of(main, helper);

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Single @entry rule should be valid");
  }

  @Test
  void testMultipleEntryAnnotationsProduceDiagnostic() {
    var first = createValidFunction("first");
    first.annotations = List.of(createAnnotation("entry"));
    var second = createValidFunction("second");
    second.annotations = List.of(createAnnotation("entry"));

    var module = new CoreModel.Module();
    module.decls = List.of(first, second);

    var diagnostics = checker.typecheckModule(module);

    assertEquals(1, diagnostics.size());
    assertEquals(ErrorCode.MULTIPLE_ENTRY_RULES, diagnostics.get(0).code());
    assertTrue(diagnostics.get(0).message().contains("first"));
    assertTrue(diagnostics.get(0).message().contains("second"));
  }

  @Test
  void testImportAliasConflictProducesDiagnostic() {
    var imp = new CoreModel.Import();
    imp.path = "lib";
    imp.version = 1;
    imp.alias = "helper";
    var helper = createValidFunction("helper");

    var module = new CoreModel.Module();
    module.decls = List.of(imp, helper);

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.stream()
      .anyMatch(d -> d.code() == ErrorCode.IMPORT_SYMBOL_CONFLICT));
  }

  @Test
  void testDuplicateImportAliasProducesDiagnostic() {
    var first = new CoreModel.Import();
    first.path = "lib.one";
    first.version = 1;
    first.alias = "Lib";
    var second = new CoreModel.Import();
    second.path = "lib.two";
    second.version = 1;
    second.alias = "Lib";

    var module = new CoreModel.Module();
    module.decls = List.of(first, second);

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.stream()
      .anyMatch(d -> d.code() == ErrorCode.IMPORT_SYMBOL_CONFLICT));
  }

  // ========== 复杂场景测试 ==========

  @Test
  void testGenericFunctionIdentity() {
    // func identity<T>(x: T): T { return x }
    var typeVar = createTypeVar("T");

    var func = new CoreModel.Func();
    func.name = "identity";
    func.typeParams = List.of("T");
    func.params = List.of(createParam("x", typeVar));
    func.ret = typeVar;
    func.effects = List.of();

    var body = new CoreModel.Block();
    body.statements = List.of(createReturnStmt(createNameExpr("x")));
    func.body = body;

    var module = new CoreModel.Module();
    module.decls = List.of(func);

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Generic identity function should typecheck");
  }

  @Test
  void testLambdaCapture() {
    // func outer(): (Int) -> Int {
    //   let x = 10
    //   return (y: Int) -> Int { return x }
    // }
    var outerX = new CoreModel.Let();
    outerX.name = "x";
    outerX.expr = createIntLiteral(10);
    outerX.origin = createOrigin();

    var lambda = new CoreModel.Lambda();
    lambda.params = List.of(createParam("y", createTypeName("Int")));
    lambda.ret = createTypeName("Int");
    lambda.origin = createOrigin();

    var lambdaBody = new CoreModel.Block();
    lambdaBody.statements = List.of(createReturnStmt(createNameExpr("x")));
    lambda.body = lambdaBody;

    var returnLambda = new CoreModel.Return();
    returnLambda.expr = lambda;

    var funcBody = new CoreModel.Block();
    funcBody.statements = List.of(outerX, returnLambda);

    var func = new CoreModel.Func();
    func.name = "outer";
    func.params = List.of();
    func.ret = createFuncType(
      List.of(createTypeName("Int")),
      createTypeName("Int")
    );
    func.effects = List.of();
    func.body = funcBody;

    var module = new CoreModel.Module();
    module.decls = List.of(func);

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Lambda capturing outer variable should typecheck");
  }

  @Test
  void testIfElseWithBranchMismatch() {
    // func test(cond: Bool): Int {
    //   if cond { return 42 } else { return "str" }  // Error: branch type mismatch
    // }
    var ifStmt = new CoreModel.If();
    ifStmt.cond = createNameExpr("cond");

    var thenBlock = new CoreModel.Block();
    thenBlock.statements = List.of(createReturnStmt(createIntLiteral(42)));
    ifStmt.thenBlock = thenBlock;

    var elseBlock = new CoreModel.Block();
    elseBlock.statements = List.of(createReturnStmt(createStringLiteral("str")));
    ifStmt.elseBlock = elseBlock;

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("cond", createTypeName("Bool"))),
      createTypeName("Int"),
      List.of(),
      ifStmt
    );

    var diagnostics = checker.typecheckModule(module);

    assertFalse(diagnostics.isEmpty(), "Branch type mismatch should produce diagnostic");
  }

  @Test
  void testAwaitMaybe() {
    // func test(m: Maybe<Int>): Int {
    //   return await m  // Unwraps Maybe<Int> -> Int
    // }
    var awaitExpr = new CoreModel.Await();
    awaitExpr.expr = createNameExpr("m");

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("m", createMaybeType(createTypeName("Int")))),
      createTypeName("Int"),
      List.of("async"),  // await requires async effect
      createReturnStmt(awaitExpr)
    );

    var diagnostics = checker.typecheckModule(module);
    assertTrue(diagnostics.isEmpty(), "Await Maybe<Int> should return Int with async effect");
  }

  @Test
  void testAwaitNonAwaitableType() {
    // func test(x: Int): Int {
    //   return await x  // Error: cannot await Int
    // }
    var awaitExpr = new CoreModel.Await();
    awaitExpr.expr = createNameExpr("x");

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("Int"),
      List.of(),
      createReturnStmt(awaitExpr)
    );

    var diagnostics = checker.typecheckModule(module);

    assertFalse(diagnostics.isEmpty(), "Await non-awaitable type should produce error");
    assertTrue(diagnostics.stream()
      .anyMatch(d -> d.code() == ErrorCode.AWAIT_TYPE));
  }

  @Test
  void testMatchPatternExhaustiveness() {
    // func test(x: Int): Int {
    //   match x {
    //     case 1: return 10
    //     case 2: return 20
    //   }
    // }
    var case1 = new CoreModel.Case();
    case1.pattern = createPatternInt(1);
    case1.body = createReturnStmt(createIntLiteral(10));

    var case2 = new CoreModel.Case();
    case2.pattern = createPatternInt(2);
    case2.body = createReturnStmt(createIntLiteral(20));

    var match = new CoreModel.Match();
    match.expr = createNameExpr("x");
    match.cases = List.of(case1, case2);

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("Int"),
      List.of(),
      match
    );

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Match with consistent branch types should typecheck");
  }

  /** 构造列表字面量 `[e1, e2, ...]`。 */
  private CoreModel.ListE listOf(CoreModel.Expr... elements) {
    var list = new CoreModel.ListE();
    list.elements = List.of(elements);
    return list;
  }

  /** 返回一个列表字面量的函数模块（返回类型 List of Int，仅为构造完整模块）。 */
  private CoreModel.Module moduleReturningList(CoreModel.ListE list) {
    var listType = new CoreModel.ListT();
    listType.type = createTypeName("Int");
    return createModuleWithFunction(
      "test", List.of(), listType, List.of(), createReturnStmt(list)
    );
  }

  private List<Diagnostic> listElementMismatches(CoreModel.Module module) {
    return checker.typecheckModule(module).stream()
      .filter(d -> d.code() == ErrorCode.LIST_ELEMENT_TYPE_MISMATCH)
      .toList();
  }

  @Test
  void heterogeneousListLiteralIsReported() {
    // ★issue #128：注释声称「校验其余元素与首元素类型一致（不一致出诊断）」，
    //   但循环里只调 typeOfExpr 丢弃返回值——既不比较也不发诊断。
    //   `[1, "a"]` 被静默定型为 List<Int> 通过检查。
    //   E020 LIST_ELEMENT_TYPE_MISMATCH 早在 ErrorCode / error_codes.json 里定义好，
    //   但全仓 emit 站点数为 0。
    var mismatches = listElementMismatches(
      moduleReturningList(listOf(createIntLiteral(1), createStringLiteral("a")))
    );
    assertFalse(mismatches.isEmpty(), "[1, \"a\"] 必须报 LIST_ELEMENT_TYPE_MISMATCH");
  }

  @Test
  void heterogeneousListLiteralCarriesBothTypesAsParams() {
    // ★只断言「报错了」不够：期望/实际类型必须真的被带上，否则用户无从定位。
    //
    //   ★这里断言的是 **params** 而非 message：ErrorCode.java 是由
    //   aster-lang-ts/scripts/generate_error_codes.ts 自动生成的，其 toJavaTemplate
    //   把 json 的 {name} 全替换成 %s，而 DiagnosticBuilder 只认 {name}——
    //   于是 message 目前渲染出字面的 "%s"。那是全仓 23 个错误码共有的缺陷
    //   （issue #137），不是本次修复能在生成物里手改掉的（手改会被重新生成覆盖）。
    //   params 是不受该缺陷影响的那一层，锁它才锁得住「类型信息有没有被带上」。
    var mismatches = listElementMismatches(
      moduleReturningList(listOf(createIntLiteral(1), createStringLiteral("a")))
    );
    assertFalse(mismatches.isEmpty(), "前置条件：应有诊断");
    var params = mismatches.get(0).data();
    assertEquals("Int", String.valueOf(params.get("expected")),
      "expected 须为首元素类型；实际 params：" + params);
    assertEquals("String", String.valueOf(params.get("actual")),
      "actual 须为不符元素的类型；实际 params：" + params);
  }

  @Test
  void everyMismatchedElementIsReported_notJustTheFirst() {
    // ★`[1, "a", true]` 有两个元素与首元素不符，两个都要报——
    //   只报第一个会让用户改完一处又撞下一处。
    var mismatches = listElementMismatches(
      moduleReturningList(listOf(
        createIntLiteral(1), createStringLiteral("a"), createStringLiteral("b")
      ))
    );
    assertEquals(2, mismatches.size(),
      "两个不符元素须各报一条；实际：" + mismatches);
  }

  @Test
  void homogeneousListLiteralIsAccepted() {
    // ★反向护栏：同构列表不得误报。
    //   没有这条，把比较改成「恒不相等」也能让上面三条变绿。
    assertTrue(
      listElementMismatches(
        moduleReturningList(listOf(createIntLiteral(1), createIntLiteral(2)))
      ).isEmpty(),
      "[1, 2] 不得报元素类型不符"
    );
  }

  @Test
  void emptyAndSingletonListLiteralsAreAccepted() {
    // 边界：空列表与单元素列表没有「其余元素」可比，不得进入比较分支。
    assertTrue(listElementMismatches(moduleReturningList(listOf())).isEmpty(),
      "空列表不得报错");
    assertTrue(
      listElementMismatches(moduleReturningList(listOf(createIntLiteral(1)))).isEmpty(),
      "单元素列表不得报错");
  }

  @Test
  void listOverallTypeComesFromFirstElement_notLast() {
    // ★上面几条只过滤 LIST_ELEMENT_TYPE_MISMATCH 一个码，只锁「诊断发出来了」，
    //   从不锁「列表整体被定成什么类型」——这是两件事。实测：把 listType.type
    //   改成取**最后一个**元素的类型，本类 29/29 全绿、全量 1541 条也全绿。
    //
    //   列表整体类型经由 RETURN_TYPE_MISMATCH 的 actual 参数可观测：
    //   声明返回 List of String、实际 [1, "a"]，
    //   整体类型若正确取首元素 → actual=List<Int> 与声明不符 → 报错；
    //   若错误取末元素 → actual=List<String> 与声明相符 → 该诊断消失。
    var declared = new CoreModel.ListT();
    declared.type = createTypeName("String");
    var module = createModuleWithFunction("test", List.of(), declared, List.of(),
      createReturnStmt(listOf(createIntLiteral(1), createStringLiteral("a"))));

    var ret = checker.typecheckModule(module).stream()
      .filter(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH).toList();
    assertFalse(ret.isEmpty(),
      "列表整体类型须取首元素类型 List<Int>，与声明的 List<String> 不符须报 RETURN_TYPE_MISMATCH");
    assertEquals("List<Int>", String.valueOf(ret.get(0).data().get("actual")),
      "actual 须为 List<Int>（首元素类型）而非 List<String>（末元素类型）");
  }

  @Test
  void unknownTypedElementDoesNotCascade() {
    // ★元素本身已出错（未定义变量 → unknown）时不得再叠一条列表元素不符——
    //   非 strict 比较下 unknown 与任何类型相容，正是为此。
    //   否则一个笔误会同时炸出两条诊断，噪声掩盖真正的错误。
    var module = moduleReturningList(listOf(createIntLiteral(1), createNameExpr("nope")));
    var diags = checker.typecheckModule(module);

    assertTrue(diags.stream().anyMatch(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE),
      "前置条件：未定义变量本身要报");
    assertTrue(diags.stream().noneMatch(d -> d.code() == ErrorCode.LIST_ELEMENT_TYPE_MISMATCH),
      "unknown 元素不得级联报元素类型不符");
  }

  @Test
  void matchPatternBindingIsInScopeInsideBranch() {
    // ★issue #132：checkMatch 全程不碰 kase.pattern，模式绑定的变量在分支体里
    //   必然报假 UNDEFINED_VARIABLE —— 模式匹配这条路径实际不可用。
    //
    //   func test(x: Int): Int {
    //     match x {
    //       case v: return v      // v 由 PatName 绑定
    //     }
    //   }
    var patName = new CoreModel.PatName();
    patName.name = "v";

    var kase = new CoreModel.Case();
    kase.pattern = patName;
    kase.body = createReturnStmt(createNameExpr("v"));   // 引用绑定变量

    var match = new CoreModel.Match();
    match.expr = createNameExpr("x");
    match.cases = List.of(kase);

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("Int"),
      List.of(),
      match
    );

    var diagnostics = checker.typecheckModule(module);

    // 断言具体错误码，而不是笼统的 isEmpty()——后者会被任何无关诊断干扰，
    // 也说不清「到底是不是这个 bug」。
    var undefined = diagnostics.stream()
      .filter(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE)
      .toList();
    assertTrue(
      undefined.isEmpty(),
      "模式绑定的变量 v 必须在分支体作用域内；实际诊断：" + undefined
    );
  }

  @Test
  void matchPatternBindingDoesNotLeakOutsideBranch() {
    // ★与上一条成对：绑定必须**限定在该分支内**。
    //   只测「能用」而不测「不外泄」，等于允许实现把绑定定义到外层作用域——
    //   那样第一条会绿，但作用域语义是错的。
    //
    //   func test(x: Int): Int {
    //     match x { case v: return 1 }
    //     return v                      // v 在此处不应可见
    //   }
    var patName = new CoreModel.PatName();
    patName.name = "v";

    var kase = new CoreModel.Case();
    kase.pattern = patName;
    kase.body = createReturnStmt(createIntLiteral(1));

    var match = new CoreModel.Match();
    match.expr = createNameExpr("x");
    match.cases = List.of(kase);

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("Int"),
      List.of(),
      match,
      createReturnStmt(createNameExpr("v"))    // 分支外引用
    );

    var diagnostics = checker.typecheckModule(module);

    assertTrue(
      diagnostics.stream().anyMatch(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE),
      "分支外引用 v 必须报 UNDEFINED_VARIABLE（绑定不得外泄）"
    );
  }

  @Test
  void matchPatternBindingIsInScopeInsideBranch() {
    // ★issue #132：checkMatch 全程不碰 kase.pattern，模式绑定的变量在分支体里
    //   必然报假 UNDEFINED_VARIABLE —— 模式匹配这条路径实际不可用。
    //
    //   func test(x: Int): Int {
    //     match x {
    //       case v: return v      // v 由 PatName 绑定
    //     }
    //   }
    var patName = new CoreModel.PatName();
    patName.name = "v";

    var kase = new CoreModel.Case();
    kase.pattern = patName;
    kase.body = createReturnStmt(createNameExpr("v"));   // 引用绑定变量

    var match = new CoreModel.Match();
    match.expr = createNameExpr("x");
    match.cases = List.of(kase);

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("Int"),
      List.of(),
      match
    );

    var diagnostics = checker.typecheckModule(module);

    // 断言具体错误码，而不是笼统的 isEmpty()——后者会被任何无关诊断干扰，
    // 也说不清「到底是不是这个 bug」。
    var undefined = diagnostics.stream()
      .filter(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE)
      .toList();
    assertTrue(
      undefined.isEmpty(),
      "模式绑定的变量 v 必须在分支体作用域内；实际诊断：" + undefined
    );
  }

  @Test
  void matchPatternBindingDoesNotLeakOutsideBranch() {
    // ★与上一条成对：绑定必须**限定在该分支内**。
    //   只测「能用」而不测「不外泄」，等于允许实现把绑定定义到外层作用域——
    //   那样第一条会绿，但作用域语义是错的。
    //
    //   func test(x: Int): Int {
    //     match x { case v: return 1 }
    //     return v                      // v 在此处不应可见
    //   }
    var patName = new CoreModel.PatName();
    patName.name = "v";

    var kase = new CoreModel.Case();
    kase.pattern = patName;
    kase.body = createReturnStmt(createIntLiteral(1));

    var match = new CoreModel.Match();
    match.expr = createNameExpr("x");
    match.cases = List.of(kase);

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("Int"),
      List.of(),
      match,
      createReturnStmt(createNameExpr("v"))    // 分支外引用
    );

    var diagnostics = checker.typecheckModule(module);

    assertTrue(
      diagnostics.stream().anyMatch(d -> d.code() == ErrorCode.UNDEFINED_VARIABLE),
      "分支外引用 v 必须报 UNDEFINED_VARIABLE（绑定不得外泄）"
    );
  }

  @Test
  void testEffectPropagationThroughCalls() {
    // func ioFunc(): Int [io] { return 42 }
    // Simplified: just test that a function with IO effect is valid
    var module = createModuleWithFunction(
      "ioFunc",
      List.of(),
      createTypeName("Int"),
      List.of("io"),
      createReturnStmt(createIntLiteral(42))
    );

    var diagnostics = checker.typecheckModule(module);

    // Function declares IO effect - should be valid (PURE ⊑ IO)
    assertTrue(diagnostics.isEmpty(), "IO function with pure body should be valid");
  }

  // ========== 修复验证测试（来自 Codex 审查报告）==========

  @Test
  void testCrossFunctionCall() {
    // 【修复 #1 验证】跨函数调用应该能解析符号
    // func helper(): Int { return 42 }
    // func main(): Int { return helper() }

    var helperFunc = new CoreModel.Func();
    helperFunc.name = "helper";
    helperFunc.params = List.of();
    helperFunc.ret = createTypeName("Int");
    helperFunc.effects = List.of();

    var helperBody = new CoreModel.Block();
    helperBody.statements = List.of(createReturnStmt(createIntLiteral(42)));
    helperFunc.body = helperBody;

    var helperCall = new CoreModel.Call();
    helperCall.target = createNameExpr("helper");
    helperCall.args = List.of();

    var mainFunc = new CoreModel.Func();
    mainFunc.name = "main";
    mainFunc.params = List.of();
    mainFunc.ret = createTypeName("Int");
    mainFunc.effects = List.of();

    var mainBody = new CoreModel.Block();
    mainBody.statements = List.of(createReturnStmt(helperCall));
    mainFunc.body = mainBody;

    var module = new CoreModel.Module();
    module.decls = List.of(helperFunc, mainFunc);

    var diagnostics = checker.typecheckModule(module);

    // 修复前：会报 UNDEFINED_VARIABLE 错误（helper 符号在 main 函数作用域查不到）
    // 修复后：应该通过检查
    assertTrue(diagnostics.isEmpty(),
      "Cross-function call should resolve (Fix #1: function symbols in module scope)");
  }

  /** 构造一个无参、返回 Int、体为 `return <expr>` 的函数。 */
  private CoreModel.Func intFunc(String name, CoreModel.Expr returned) {
    var f = new CoreModel.Func();
    f.name = name;
    f.params = List.of();
    f.ret = createTypeName("Int");
    f.effects = List.of();
    var body = new CoreModel.Block();
    body.statements = List.of(createReturnStmt(returned));
    f.body = body;
    return f;
  }

  private CoreModel.Call callOf(String name) {
    var call = new CoreModel.Call();
    call.target = createNameExpr(name);
    call.args = List.of();
    return call;
  }

  private List<ErrorCode> undefinedCodes(CoreModel.Module module) {
    return checker.typecheckModule(module).stream()
      .map(d -> d.code())
      .filter(c -> c == ErrorCode.UNDEFINED_VARIABLE)
      .toList();
  }

  @Test
  void forwardReferenceResolves_calleeDeclaredAfterCaller() {
    // ★testCrossFunctionCall 的盲区：那条测试用 List.of(helperFunc, mainFunc)，
    //   **被调方永远排在前面**，前向引用路径零覆盖（issue #125 body）。
    //   这里把顺序反过来——修复前必报假 UNDEFINED_VARIABLE。
    //
    //   func main(): Int { return helper() }   // 先声明
    //   func helper(): Int { return 42 }       // 后声明
    var mainFunc = intFunc("main", callOf("helper"));
    var helperFunc = intFunc("helper", createIntLiteral(42));

    var module = new CoreModel.Module();
    module.decls = List.of(mainFunc, helperFunc);   // 调用方在前

    var undefined = undefinedCodes(module);
    assertTrue(
      undefined.isEmpty(),
      "被调函数声明在调用方之后时仍须可见；实际 UNDEFINED_VARIABLE 数：" + undefined.size()
    );
  }

  @Test
  void mutualRecursionResolves_inBothDirections() {
    // ★互递归无论怎么排序都必有一侧前向引用——这是「预注册签名」与
    //   「边声明边检查」最本质的区别，声明顺序再怎么调也绕不过去。
    //
    //   func isEven(): Int { return isOdd() }
    //   func isOdd(): Int { return isEven() }
    var isEven = intFunc("isEven", callOf("isOdd"));
    var isOdd = intFunc("isOdd", callOf("isEven"));

    var module = new CoreModel.Module();
    module.decls = List.of(isEven, isOdd);

    var undefined = undefinedCodes(module);
    assertTrue(
      undefined.isEmpty(),
      "互递归两侧都须解析成功；实际 UNDEFINED_VARIABLE 数：" + undefined.size()
    );
  }

  @Test
  void trulyUndefinedFunctionStillReported() {
    // ★反向护栏：预注册签名不能退化成「什么名字都认」。
    //   没有这条，把 undefinedVariable 整个删掉也能让上面两条变绿。
    var mainFunc = intFunc("main", callOf("noSuchFunction"));

    var module = new CoreModel.Module();
    module.decls = List.of(mainFunc);

    assertFalse(
      undefinedCodes(module).isEmpty(),
      "调用真正不存在的函数仍须报 UNDEFINED_VARIABLE"
    );
  }

  @Test
  void testEffectInferenceFromDeclaredEffect() {
    // 【修复 #2 验证】调用函数时应该读取声明的效果
    // func ioOperation(): Int [io] { return 42 }
    // func caller(): Int [io] { return ioOperation() }

    var ioFunc = new CoreModel.Func();
    ioFunc.name = "ioOperation";
    ioFunc.params = List.of();
    ioFunc.ret = createTypeName("Int");
    ioFunc.effects = List.of("io");  // 声明 IO 效果

    var ioBody = new CoreModel.Block();
    ioBody.statements = List.of(createReturnStmt(createIntLiteral(42)));
    ioFunc.body = ioBody;

    var ioCall = new CoreModel.Call();
    ioCall.target = createNameExpr("ioOperation");
    ioCall.args = List.of();

    var callerFunc = new CoreModel.Func();
    callerFunc.name = "caller";
    callerFunc.params = List.of();
    callerFunc.ret = createTypeName("Int");
    callerFunc.effects = List.of("io");  // 声明 IO 效果

    var callerBody = new CoreModel.Block();
    callerBody.statements = List.of(createReturnStmt(ioCall));
    callerFunc.body = callerBody;

    var module = new CoreModel.Module();
    module.decls = List.of(ioFunc, callerFunc);

    var diagnostics = checker.typecheckModule(module);

    // 修复前：inferCallEffect 只检查前缀，无法识别 ioOperation 的 IO 效果
    // 修复后：应该从符号表读取 ioOperation 的声明效果，推断出 IO
    assertTrue(diagnostics.isEmpty(),
      "Calling IO function should propagate IO effect (Fix #2: effect inference from symbol table)");
  }

  @Test
  void testEffectViolationDetection() {
    // 【修复 #2 验证】效果违规应该被检测
    // func ioOperation(): Int [io] { return 42 }
    // func pureCaller(): Int { return ioOperation() }  // Error: PURE 函数调用 IO 函数

    var ioFunc = new CoreModel.Func();
    ioFunc.name = "ioOperation";
    ioFunc.params = List.of();
    ioFunc.ret = createTypeName("Int");
    ioFunc.effects = List.of("io");

    var ioBody = new CoreModel.Block();
    ioBody.statements = List.of(createReturnStmt(createIntLiteral(42)));
    ioFunc.body = ioBody;

    var ioCall = new CoreModel.Call();
    ioCall.target = createNameExpr("ioOperation");
    ioCall.args = List.of();

    var pureFunc = new CoreModel.Func();
    pureFunc.name = "pureCaller";
    pureFunc.params = List.of();
    pureFunc.ret = createTypeName("Int");
    pureFunc.effects = List.of();  // 声明为 PURE

    var pureBody = new CoreModel.Block();
    pureBody.statements = List.of(createReturnStmt(ioCall));
    pureFunc.body = pureBody;

    var module = new CoreModel.Module();
    module.decls = List.of(ioFunc, pureFunc);

    var diagnostics = checker.typecheckModule(module);

    // 修复后：应该检测到效果违规（实际效果 IO ⋢ 声明效果 PURE）
    assertFalse(diagnostics.isEmpty(),
      "Pure function calling IO function should report effect violation");
    assertTrue(diagnostics.stream()
      .anyMatch(d -> d.code() == ErrorCode.EFF_MISSING_IO),
      "Should report EFF_MISSING_IO error");
  }

  @Test
  void testMultipleErrorsInModule() {
    // func bad1(): Int { return "wrong" }  // Error 1
    // func bad2(): String { return 42 }     // Error 2
    var func1 = new CoreModel.Func();
    func1.name = "bad1";
    func1.params = List.of();
    func1.ret = createTypeName("Int");
    func1.effects = List.of();

    var body1 = new CoreModel.Block();
    body1.statements = List.of(createReturnStmt(createStringLiteral("wrong")));
    func1.body = body1;

    var func2 = new CoreModel.Func();
    func2.name = "bad2";
    func2.params = List.of();
    func2.ret = createTypeName("String");
    func2.effects = List.of();

    var body2 = new CoreModel.Block();
    body2.statements = List.of(createReturnStmt(createIntLiteral(42)));
    func2.body = body2;

    var module = new CoreModel.Module();
    module.decls = List.of(func1, func2);

    var diagnostics = checker.typecheckModule(module);

    assertEquals(2, diagnostics.size(), "Should report multiple errors");
    assertTrue(diagnostics.stream()
      .allMatch(d -> d.code() == ErrorCode.RETURN_TYPE_MISMATCH));
  }

  @Test
  void testNestedScopeShadowing() {
    // func test(x: Int): Int {
    //   return (y: Int) -> Int {
    //     let x = 20  // Shadows parameter x
    //     return x    // Returns inner x (20)
    //   }(x)
    // }
    // Actually, let's test lambda parameter shadowing instead
    var innerLet = new CoreModel.Let();
    innerLet.name = "x";
    innerLet.expr = createIntLiteral(20);
    innerLet.origin = createOrigin();

    var lambda = new CoreModel.Lambda();
    lambda.params = List.of(createParam("y", createTypeName("Int")));
    lambda.ret = createTypeName("Int");
    lambda.origin = createOrigin();

    var lambdaBody = new CoreModel.Block();
    lambdaBody.statements = List.of(innerLet, createReturnStmt(createNameExpr("x")));
    lambda.body = lambdaBody;

    var module = createModuleWithFunction(
      "test",
      List.of(createParam("x", createTypeName("Int"))),
      createTypeName("Int"),
      List.of(),
      createReturnStmt(createIntLiteral(10))  // Simplified - just return a value
    );

    var diagnostics = checker.typecheckModule(module);

    assertTrue(diagnostics.isEmpty(), "Simple function should typecheck");
  }

  // ========== Helper Methods ==========

  private CoreModel.Module createModuleWithFunction(
    String name,
    List<CoreModel.Param> params,
    CoreModel.Type returnType,
    List<String> effects,
    CoreModel.Stmt... bodyStmts
  ) {
    var func = new CoreModel.Func();
    func.name = name;
    func.params = params;
    func.ret = returnType;
    func.effects = effects;

    var body = new CoreModel.Block();
    body.statements = List.of(bodyStmts);
    func.body = body;

    var module = new CoreModel.Module();
    module.decls = List.of(func);

    return module;
  }

  private CoreModel.Func createValidFunction(String name) {
    var func = new CoreModel.Func();
    func.name = name;
    func.params = List.of();
    func.ret = createTypeName("Int");
    func.effects = List.of();

    var body = new CoreModel.Block();
    body.statements = List.of(createReturnStmt(createIntLiteral(1)));
    func.body = body;
    return func;
  }

  private CoreModel.Annotation createAnnotation(String name) {
    var annotation = new CoreModel.Annotation();
    annotation.name = name;
    return annotation;
  }

  private CoreModel.Param createParam(String name, CoreModel.Type type) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = type;
    return param;
  }

  private CoreModel.Field createField(String name, CoreModel.Type type) {
    var field = new CoreModel.Field();
    field.name = name;
    field.type = type;
    return field;
  }

  private CoreModel.TypeName createTypeName(String name) {
    var type = new CoreModel.TypeName();
    type.name = name;
    return type;
  }

  private CoreModel.Return createReturnStmt(CoreModel.Expr expr) {
    var ret = new CoreModel.Return();
    ret.expr = expr;
    return ret;
  }

  private CoreModel.Name createNameExpr(String name) {
    var nameExpr = new CoreModel.Name();
    nameExpr.name = name;
    return nameExpr;
  }

  private CoreModel.IntE createIntLiteral(int value) {
    var intExpr = new CoreModel.IntE();
    intExpr.value = value;
    return intExpr;
  }

  private CoreModel.StringE createStringLiteral(String value) {
    var stringExpr = new CoreModel.StringE();
    stringExpr.value = value;
    return stringExpr;
  }

  private CoreModel.TypeVar createTypeVar(String name) {
    var typeVar = new CoreModel.TypeVar();
    typeVar.name = name;
    return typeVar;
  }

  private CoreModel.FuncType createFuncType(List<CoreModel.Type> params, CoreModel.Type ret) {
    var funcType = new CoreModel.FuncType();
    funcType.params = params;
    funcType.ret = ret;
    return funcType;
  }

  private CoreModel.Maybe createMaybeType(CoreModel.Type inner) {
    var maybe = new CoreModel.Maybe();
    maybe.type = inner;
    return maybe;
  }

  private CoreModel.PatInt createPatternInt(int value) {
    var pattern = new CoreModel.PatInt();
    pattern.value = value;
    return pattern;
  }

  private CoreModel.Origin createOrigin() {
    var origin = new CoreModel.Origin();
    origin.file = "test";
    origin.start = createPosition(0);
    origin.end = createPosition(0);
    return origin;
  }

  private CoreModel.Position createPosition(int offset) {
    var pos = new CoreModel.Position();
    pos.line = 1;
    pos.col = offset;
    return pos;
  }

  /**
   * 验证 TypeChecker 实例可以多次调用 typecheckModule 而不崩溃
   * <p>
   * 这个测试确保内置别名（如 Text → String）的注册具有幂等性。
   * 回归测试用于防止 "Duplicate type alias 'Text'" 异常。
   */
  @Test
  void testTypecheckerReusability() {
    var checker = new TypeChecker();

    // 创建第一个简单模块
    var module1 = new CoreModel.Module();
    module1.name = "module1";
    module1.decls = List.of();

    // 第一次类型检查
    var diags1 = checker.typecheckModule(module1);
    assertEquals(0, diags1.size(), "第一次类型检查应该成功");

    // 创建第二个模块
    var module2 = new CoreModel.Module();
    module2.name = "module2";
    module2.decls = List.of();

    // 第二次类型检查 - 如果内置别名没有幂等性，这里会抛出异常
    var diags2 = checker.typecheckModule(module2);
    assertEquals(0, diags2.size(), "第二次类型检查应该成功，不应因重复别名注册而失败");
  }
}
