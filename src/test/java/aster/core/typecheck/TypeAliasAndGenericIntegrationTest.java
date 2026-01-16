package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类型别名和泛型参数集成测试
 * <p>
 * 验证类型别名展开和泛型参数替换在统一流程中正常工作。
 * <p>
 * 测试场景：
 * - 泛型函数调用的类型推断
 * - 类型别名在泛型上下文中的展开
 * - 复杂嵌套泛型类型的处理
 */
class TypeAliasAndGenericIntegrationTest {

  private TypeChecker typeChecker;

  @BeforeEach
  void setUp() {
    typeChecker = new TypeChecker();
  }

  // ========== 基础泛型函数调用测试 ==========

  @Test
  void testGenericIdentityFunction() {
    // func identity<T>(x: T): T { return x }
    // func test(): Int { return identity(42) }
    var identityParam = createParameter("x", createTypeVar("T"));
    var identityFunc = createFunction(
      "identity",
      List.of(identityParam),
      createTypeVar("T"),
      createBlock(List.of(createReturn(createName("x"))))
    );

    var testCallIdentity = createCall(
      createName("identity"),
      List.of(createInt(42))
    );
    var testFunc = createFunction(
      "test",
      List.of(),
      createTypeName("Int"),
      createBlock(List.of(createReturn(testCallIdentity)))
    );

    var module = createModule(List.of(identityFunc, testFunc));
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors, but got: " + errors);
  }

  @Test
  void testGenericListFunction() {
    // func identity<T>(x: T): T { return x }
    // func wrap_option<T>(x: T): Option<T> { return Some(x) }
    //
    // func test(): Option<Int> { return wrap_option(42) }
    var optionType = new CoreModel.Option();
    optionType.type = createTypeVar("T");

    var wrapParam = createParameter("x", createTypeVar("T"));
    var wrapFunc = createFunction(
      "wrap_option",
      List.of(wrapParam),
      optionType,
      createBlock(List.of(createReturn(createSome(createName("x"))))) // Some(x)
    );

    var testCallWrap = createCall(
      createName("wrap_option"),
      List.of(createInt(42))
    );

    var expectedReturnType = new CoreModel.Option();
    expectedReturnType.type = createTypeName("Int");

    var testFunc = createFunction(
      "test",
      List.of(),
      expectedReturnType,
      createBlock(List.of(createReturn(testCallWrap)))
    );

    var module = createModule(List.of(wrapFunc, testFunc));
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors, but got: " + errors);
  }

  // ========== 类型别名集成测试 ==========

  @Test
  void testTypeAliasInGenericContext() {
    // type UserId = Int
    // func identity<T>(x: T): T { return x }
    // func test(): UserId { return identity(42) }
    
    // 定义泛型 identity 函数
    var identityParam = createParameter("x", createTypeVar("T"));
    var identityFunc = createFunction(
      "identity",
      List.of(identityParam),
      createTypeVar("T"),
      createBlock(List.of(createReturn(createName("x"))))
    );

    // 调用 identity(42)
    var testCallIdentity = createCall(
      createName("identity"),
      List.of(createInt(42))
    );
    
    // test 函数返回 UserId（别名指向 Int）
    var testFunc = createFunction(
      "test",
      List.of(),
      createTypeName("UserId"),  // 使用别名作为返回类型
      createBlock(List.of(createReturn(testCallIdentity)))
    );

    var module = createModule(List.of(identityFunc, testFunc));
    
    // 【关键】在类型检查前定义别名
    // 直接访问 TypeChecker 内部的 symbolTable
    typeChecker.getSymbolTable().defineTypeAlias("UserId", createTypeName("Int"), java.util.Optional.empty());
    
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors, but got: " + errors);
  }
  
  @Test
  void testTypeAliasWithNestedGenerics() {
    // type UserId = Int
    // type UserResult = Result<UserId, String>
    // func wrap<T>(x: T): Result<T, String> { return Ok(x) }
    // func test(): UserResult { return wrap(42) }
    
    // 定义泛型 wrap 函数
    var resultType = new CoreModel.Result();
    resultType.ok = createTypeVar("T");
    resultType.err = createTypeName("String");
    
    var wrapParam = createParameter("x", createTypeVar("T"));
    var wrapFunc = createFunction(
      "wrap",
      List.of(wrapParam),
      resultType,
      createBlock(List.of(createReturn(createOk(createName("x")))))
    );

    // 调用 wrap(42)
    var testCallWrap = createCall(
      createName("wrap"),
      List.of(createInt(42))
    );
    
    // test 函数返回 UserResult（别名指向 Result<UserId, String>）
    var testFunc = createFunction(
      "test",
      List.of(),
      createTypeName("UserResult"),  // 使用嵌套别名作为返回类型
      createBlock(List.of(createReturn(testCallWrap)))
    );

    var module = createModule(List.of(wrapFunc, testFunc));
    
    // 【关键】定义嵌套别名
    typeChecker.getSymbolTable().defineTypeAlias("UserId", createTypeName("Int"), java.util.Optional.empty());
    
    var userResultType = new CoreModel.Result();
    userResultType.ok = createTypeName("UserId");
    userResultType.err = createTypeName("String");
    typeChecker.getSymbolTable().defineTypeAlias("UserResult", userResultType, java.util.Optional.empty());
    
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors, but got: " + errors);
  }
  
  @Test
  void testTypeAliasAsParameter() {
    // type UserId = Int
    // func process(id: UserId): Int { return id }
    // func test(): Int { return process(42) }
    
    // 定义 process 函数，接受 UserId 参数
    var processParam = createParameter("id", createTypeName("UserId"));
    var processFunc = createFunction(
      "process",
      List.of(processParam),
      createTypeName("Int"),
      createBlock(List.of(createReturn(createName("id"))))
    );

    // 调用 process(42)
    var testCallProcess = createCall(
      createName("process"),
      List.of(createInt(42))
    );
    
    var testFunc = createFunction(
      "test",
      List.of(),
      createTypeName("Int"),
      createBlock(List.of(createReturn(testCallProcess)))
    );

    var module = createModule(List.of(processFunc, testFunc));
    
    // 定义别名
    typeChecker.getSymbolTable().defineTypeAlias("UserId", createTypeName("Int"), java.util.Optional.empty());
    
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors, but got: " + errors);
  }
  
  @Test
  void testLambdaReturnsAlias() {
    // type UserId = Int
    // func test(): UserId {
    //   let f = () => 42
    //   return f()
    // }
    
    // 创建 Lambda: () => 42
    var lambda = new CoreModel.Lambda();
    lambda.params = List.of();
    lambda.ret = createTypeName("UserId");  // Lambda 返回别名
    lambda.body = createBlock(List.of(createReturn(createInt(42))));
    lambda.origin = createOrigin();
    
    // let f = lambda
    var letF = new CoreModel.Let();
    letF.name = "f";
    letF.expr = lambda;
    letF.origin = createOrigin();
    
    // return f()
    var callF = createCall(createName("f"), List.of());
    var returnStmt = createReturn(callF);
    
    var testFunc = createFunction(
      "test",
      List.of(),
      createTypeName("UserId"),
      createBlock(List.of(letF, returnStmt))
    );

    var module = createModule(List.of(testFunc));
    
    // 定义别名
    typeChecker.getSymbolTable().defineTypeAlias("UserId", createTypeName("Int"), java.util.Optional.empty());
    
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors, but got: " + errors);
  }

  @Test
  void testGenericTypeAlias() {
    // type Box<T> = Result<T, String>
    // func wrap<T>(x: T): Box<T> { return Ok(x) }
    // func test(): Result<Int, String> { return wrap(42) }

    // 定义泛型 wrap 函数，返回 Box<T>
    var wrapParam = createParameter("x", createTypeVar("T"));

    var boxType = createTypeName("Box");
    var boxTypeApp = new CoreModel.TypeApp();
    boxTypeApp.base = "Box";
    boxTypeApp.args = List.of(createTypeVar("T"));
    boxTypeApp.origin = createOrigin();

    var wrapFunc = createFunction(
      "wrap",
      List.of(wrapParam),
      boxTypeApp,  // 返回 Box<T>
      createBlock(List.of(createReturn(createOk(createName("x")))))
    );

    // 调用 wrap(42)
    var testCallWrap = createCall(
      createName("wrap"),
      List.of(createInt(42))
    );

    // test 函数期望返回 Result<Int, String>
    var expectedReturnType = new CoreModel.Result();
    expectedReturnType.ok = createTypeName("Int");
    expectedReturnType.err = createTypeName("String");

    var testFunc = createFunction(
      "test",
      List.of(),
      expectedReturnType,
      createBlock(List.of(createReturn(testCallWrap)))
    );

    var module = createModule(List.of(wrapFunc, testFunc));

    // 【关键】定义泛型别名 Box<T> = Result<T, String>
    var boxAliasType = new CoreModel.Result();
    boxAliasType.ok = createTypeVar("T");
    boxAliasType.err = createTypeName("String");

    // 使用新的 API 传递类型参数列表
    typeChecker.getSymbolTable().defineTypeAlias("Box", boxAliasType, java.util.Optional.empty(), List.of("T"));

    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors for generic type alias, but got: " + errors);
  }

  @Test
  void testNestedTypeAlias() {
    // type ErrorResult = Result<Int, String>
    // type MyResult = ErrorResult
    // func makeError(): MyResult { return Err("error") }
    // func test(): Result<Int, String> { return makeError() }

    // 定义 makeError 函数，返回 MyResult
    var makeErrorFunc = createFunction(
      "makeError",
      List.of(),
      createTypeName("MyResult"),
      createBlock(List.of(createReturn(createErr(createStringLiteral("error")))))
    );

    // 调用 makeError()
    var testCallMakeError = createCall(
      createName("makeError"),
      List.of()
    );

    // test 函数期望返回 Result<Int, String>
    var expectedReturnType = new CoreModel.Result();
    expectedReturnType.ok = createTypeName("Int");
    expectedReturnType.err = createTypeName("String");

    var testFunc = createFunction(
      "test",
      List.of(),
      expectedReturnType,
      createBlock(List.of(createReturn(testCallMakeError)))
    );

    var module = createModule(List.of(makeErrorFunc, testFunc));

    // 【关键】定义嵌套别名
    // ErrorResult = Result<Int, String>
    var errorResultType = new CoreModel.Result();
    errorResultType.ok = createTypeName("Int");
    errorResultType.err = createTypeName("String");
    typeChecker.getSymbolTable().defineTypeAlias("ErrorResult", errorResultType, java.util.Optional.empty());

    // MyResult = ErrorResult
    typeChecker.getSymbolTable().defineTypeAlias("MyResult", createTypeName("ErrorResult"), java.util.Optional.empty());

    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors for nested type alias, but got: " + errors);
  }

  // ========== 嵌套泛型测试 ==========

  @Test
  void testNestedGenericTypes() {
    // func wrap<T>(x: T): Result<T, String> { return Ok(x) }
    // func test(): Result<Int, String> { return wrap(42) }
    var resultType = new CoreModel.Result();
    resultType.ok = createTypeVar("T");
    resultType.err = createTypeName("String");

    var wrapParam = createParameter("x", createTypeVar("T"));
    var wrapFunc = createFunction(
      "wrap",
      List.of(wrapParam),
      resultType,
      createBlock(List.of(createReturn(createOk(createName("x")))))
    );

    var testCallWrap = createCall(
      createName("wrap"),
      List.of(createInt(42))
    );

    var expectedReturnType = new CoreModel.Result();
    expectedReturnType.ok = createTypeName("Int");
    expectedReturnType.err = createTypeName("String");

    var testFunc = createFunction(
      "test",
      List.of(),
      expectedReturnType,
      createBlock(List.of(createReturn(testCallWrap)))
    );

    var module = createModule(List.of(wrapFunc, testFunc));
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证没有类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertTrue(errors.isEmpty(), "Should have no type errors, but got: " + errors);
  }

  // ========== 类型不匹配检测测试 ==========

  @Test
  void testGenericTypeMismatchDetection() {
    // func identity<T>(x: T): T { return x }
    // func test(): String { return identity(42) }  // 错误：返回 Int 但期望 String
    var identityParam = createParameter("x", createTypeVar("T"));
    var identityFunc = createFunction(
      "identity",
      List.of(identityParam),
      createTypeVar("T"),
      createBlock(List.of(createReturn(createName("x"))))
    );

    var testCallIdentity = createCall(
      createName("identity"),
      List.of(createInt(42))
    );
    var testFunc = createFunction(
      "test",
      List.of(),
      createTypeName("String"),  // 期望 String 但实际推断为 Int
      createBlock(List.of(createReturn(testCallIdentity)))
    );

    var module = createModule(List.of(identityFunc, testFunc));
    var diagnostics = typeChecker.typecheckModule(module);

    // 验证检测到类型错误
    var errors = diagnostics.stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .toList();
    assertFalse(errors.isEmpty(), "Should detect type mismatch error");

    // 验证错误消息包含类型信息
    var hasTypeMismatch = errors.stream()
      .anyMatch(d -> d.code() == ErrorCode.TYPE_MISMATCH || d.code() == ErrorCode.RETURN_TYPE_MISMATCH);
    assertTrue(hasTypeMismatch, "Should report type mismatch error");
  }

  // ========== 辅助方法 ==========

  private CoreModel.Module createModule(List<CoreModel.Decl> decls) {
    var module = new CoreModel.Module();
    module.decls = decls;
    return module;
  }

  private CoreModel.Func createFunction(
    String name,
    List<CoreModel.Param> params,
    CoreModel.Type returnType,
    CoreModel.Block body
  ) {
    var func = new CoreModel.Func();
    func.name = name;
    func.params = params;
    func.ret = returnType;
    func.body = body;
    func.effects = List.of(); // 默认无副作用
    func.origin = createOrigin();
    return func;
  }

  private CoreModel.Param createParameter(String name, CoreModel.Type type) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = type;
    return param;
  }

  private CoreModel.Block createBlock(List<CoreModel.Stmt> statements) {
    var block = new CoreModel.Block();
    block.statements = statements;
    return block;
  }

  private CoreModel.Return createReturn(CoreModel.Expr expr) {
    var ret = new CoreModel.Return();
    ret.expr = expr;
    return ret;
  }

  private CoreModel.Call createCall(CoreModel.Expr target, List<CoreModel.Expr> args) {
    var call = new CoreModel.Call();
    call.target = target;
    call.args = args;
    call.origin = createOrigin();
    return call;
  }

  private CoreModel.Name createName(String name) {
    var nameExpr = new CoreModel.Name();
    nameExpr.name = name;
    return nameExpr;
  }

  private CoreModel.IntE createInt(int value) {
    var intExpr = new CoreModel.IntE();
    intExpr.value = value;
    return intExpr;
  }

  private CoreModel.TypeName createTypeName(String name) {
    var typeName = new CoreModel.TypeName();
    typeName.name = name;
    return typeName;
  }

  private CoreModel.TypeVar createTypeVar(String name) {
    var typeVar = new CoreModel.TypeVar();
    typeVar.name = name;
    return typeVar;
  }

  private CoreModel.NoneE createNone() {
    return new CoreModel.NoneE();
  }

  private CoreModel.Some createSome(CoreModel.Expr expr) {
    var some = new CoreModel.Some();
    some.expr = expr;
    return some;
  }

  private CoreModel.Ok createOk(CoreModel.Expr expr) {
    var ok = new CoreModel.Ok();
    ok.expr = expr;
    return ok;
  }

  private CoreModel.Err createErr(CoreModel.Expr expr) {
    var err = new CoreModel.Err();
    err.expr = expr;
    return err;
  }

  private CoreModel.StringE createStringLiteral(String value) {
    var str = new CoreModel.StringE();
    str.value = value;
    return str;
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
}
