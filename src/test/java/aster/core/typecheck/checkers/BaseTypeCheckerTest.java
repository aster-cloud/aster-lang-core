package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.SymbolTable;
import aster.core.typecheck.TypeSystem;
import aster.core.typecheck.model.VisitorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BaseTypeChecker 单元测试
 * <p>
 * 测试基础类型检查器的核心功能：
 * - Lambda 表达式类型检查
 * - 函数调用类型检查
 * - 构造器类型检查
 * - Await 表达式类型检查
 * - 字面量类型推断
 * - If/Match 语句类型检查
 */
class BaseTypeCheckerTest {

  private BaseTypeChecker checker;
  private SymbolTable symbolTable;
  private DiagnosticBuilder diagnostics;
  private GenericTypeChecker genericChecker;
  private VisitorContext ctx;

  @BeforeEach
  void setUp() {
    symbolTable = new SymbolTable();
    diagnostics = new DiagnosticBuilder();
    genericChecker = new GenericTypeChecker(diagnostics);
    checker = new BaseTypeChecker(symbolTable, diagnostics, genericChecker);
    ctx = new VisitorContext(
      symbolTable,
      diagnostics,
      new HashMap<>(),
      TypeSystem.unknown(),
      VisitorContext.Effect.PURE
    );
  }

  // ========== 字面量类型推断测试 ==========

  @Test
  void testBoolLiteral() {
    var bool = createBool(true);
    var type = checker.typeOfExpr(bool, ctx);

    assertTrue(type instanceof CoreModel.TypeName);
    assertEquals("Bool", ((CoreModel.TypeName) type).name);
  }

  @Test
  void testIntLiteral() {
    var intExpr = createInt(42);
    var type = checker.typeOfExpr(intExpr, ctx);

    assertTrue(type instanceof CoreModel.TypeName);
    assertEquals("Int", ((CoreModel.TypeName) type).name);
  }

  @Test
  void testLongLiteral() {
    var longExpr = createLong(42L);
    var type = checker.typeOfExpr(longExpr, ctx);

    assertTrue(type instanceof CoreModel.TypeName);
    assertEquals("Long", ((CoreModel.TypeName) type).name);
  }

  @Test
  void testDoubleLiteral() {
    var doubleExpr = createDouble(3.14);
    var type = checker.typeOfExpr(doubleExpr, ctx);

    assertTrue(type instanceof CoreModel.TypeName);
    assertEquals("Double", ((CoreModel.TypeName) type).name);
  }

  @Test
  void testStringLiteral() {
    var stringExpr = createString("hello");
    var type = checker.typeOfExpr(stringExpr, ctx);

    assertTrue(type instanceof CoreModel.TypeName);
    assertEquals("String", ((CoreModel.TypeName) type).name);
  }

  @Test
  void testNullLiteral() {
    var nullExpr = new CoreModel.NullE();
    var type = checker.typeOfExpr(nullExpr, ctx);

    assertTrue(type instanceof CoreModel.Maybe);
  }

  // ========== 包装类型测试 ==========

  @Test
  void testOkConstructor() {
    var ok = new CoreModel.Ok();
    ok.expr = createInt(42);

    var type = checker.typeOfExpr(ok, ctx);

    assertTrue(type instanceof CoreModel.Result);
    var result = (CoreModel.Result) type;
    assertEquals("Int", ((CoreModel.TypeName) result.ok).name);
  }

  @Test
  void testErrConstructor() {
    var err = new CoreModel.Err();
    err.expr = createString("error");

    var type = checker.typeOfExpr(err, ctx);

    assertTrue(type instanceof CoreModel.Result);
    var result = (CoreModel.Result) type;
    assertEquals("String", ((CoreModel.TypeName) result.err).name);
  }

  @Test
  void testSomeConstructor() {
    var some = new CoreModel.Some();
    some.expr = createInt(42);

    var type = checker.typeOfExpr(some, ctx);

    assertTrue(type instanceof CoreModel.Option);
    var option = (CoreModel.Option) type;
    assertEquals("Int", ((CoreModel.TypeName) option.type).name);
  }

  @Test
  void testNoneConstructor() {
    var none = new CoreModel.NoneE();

    var type = checker.typeOfExpr(none, ctx);

    assertTrue(type instanceof CoreModel.Option);
  }

  // ========== 变量查找测试 ==========

  @Test
  void testNameLookup() {
    // 定义变量
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);
    symbolTable.define(
      "x",
      createTypeName("Int"),
      aster.core.typecheck.model.SymbolInfo.SymbolKind.VARIABLE,
      new SymbolTable.DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty())
    );

    var name = createName("x");
    var type = checker.typeOfExpr(name, ctx);

    assertEquals("Int", ((CoreModel.TypeName) type).name);
    symbolTable.exitScope();
  }

  @Test
  void testUndefinedVariableDiagnostic() {
    var name = createName("undefined");
    checker.typeOfExpr(name, ctx);

    // 应该产生诊断错误
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== Lambda 类型检查测试 ==========

  @Test
  void testSimpleLambda() {
    // (x: Int) -> Int { return x }
    var lambda = new CoreModel.Lambda();
    lambda.origin = createOrigin();
    lambda.params = List.of(createParameter("x", "Int"));
    lambda.ret = createTypeName("Int");

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createName("x");

    var block = new CoreModel.Block();
    block.statements = List.of(returnStmt);
    lambda.body = block;

    var type = checker.typeOfExpr(lambda, ctx);

    assertTrue(type instanceof CoreModel.FuncType);
    var funcType = (CoreModel.FuncType) type;
    assertEquals(1, funcType.params.size());
    assertEquals("Int", ((CoreModel.TypeName) funcType.params.get(0)).name);
    assertEquals("Int", ((CoreModel.TypeName) funcType.ret).name);
  }

  @Test
  void testLambdaWithMultipleParams() {
    // (x: Int, y: Int) -> Int { return x }
    var lambda = new CoreModel.Lambda();
    lambda.origin = createOrigin();
    lambda.params = List.of(
      createParameter("x", "Int"),
      createParameter("y", "Int")
    );
    lambda.ret = createTypeName("Int");

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createName("x");

    var block = new CoreModel.Block();
    block.statements = List.of(returnStmt);
    lambda.body = block;

    var type = checker.typeOfExpr(lambda, ctx);

    assertTrue(type instanceof CoreModel.FuncType);
    var funcType = (CoreModel.FuncType) type;
    assertEquals(2, funcType.params.size());
  }

  @Test
  void testLambdaReturnTypeMismatch() {
    // (x: Int) -> String { return 42 }  // 返回类型不匹配
    var lambda = new CoreModel.Lambda();
    lambda.origin = createOrigin();
    lambda.params = List.of(createParameter("x", "Int"));
    lambda.ret = createTypeName("String");

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createInt(42);

    var block = new CoreModel.Block();
    block.statements = List.of(returnStmt);
    lambda.body = block;

    checker.typeOfExpr(lambda, ctx);

    // 应该产生返回类型不匹配的诊断
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== 函数调用测试 ==========

  @Test
  void testFunctionCall() {
    // 定义函数 f: (Int) -> String
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeName("Int"));
    funcType.ret = createTypeName("String");

    symbolTable.define(
      "f",
      funcType,
      aster.core.typecheck.model.SymbolInfo.SymbolKind.FUNCTION,
      new SymbolTable.DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty())
    );

    // 调用 f(42)
    var call = new CoreModel.Call();
    call.target = createName("f");
    call.args = List.of(createInt(42));

    var type = checker.typeOfExpr(call, ctx);

    assertEquals("String", ((CoreModel.TypeName) type).name);
    symbolTable.exitScope();
  }

  @Test
  void testFunctionCallArityMismatch() {
    // 定义函数 f: (Int) -> String
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeName("Int"));
    funcType.ret = createTypeName("String");

    symbolTable.define(
      "f",
      funcType,
      aster.core.typecheck.model.SymbolInfo.SymbolKind.FUNCTION,
      new SymbolTable.DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty())
    );

    // 调用 f(42, 43) - 参数数量不匹配
    var call = new CoreModel.Call();
    call.target = createName("f");
    call.args = List.of(createInt(42), createInt(43));

    checker.typeOfExpr(call, ctx);

    // 应该产生参数数量不匹配的诊断
    assertFalse(diagnostics.getDiagnostics().isEmpty());
    symbolTable.exitScope();
  }

  @Test
  void testNotCall() {
    // not(true)
    var call = new CoreModel.Call();
    call.target = createName("not");
    call.args = List.of(createBool(true));

    var type = checker.typeOfExpr(call, ctx);

    assertEquals("Bool", ((CoreModel.TypeName) type).name);
  }

  @Test
  void testNotCallArityError() {
    // not(true, false) - 参数数量错误
    var call = new CoreModel.Call();
    call.target = createName("not");
    call.args = List.of(createBool(true), createBool(false));

    checker.typeOfExpr(call, ctx);

    // 应该产生 not 调用参数数量错误
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== 构造器测试 ==========

  @Test
  void testConstructor() {
    var construct = new CoreModel.Construct();
    construct.typeName = "User";
    construct.fields = List.of();

    var type = checker.typeOfExpr(construct, ctx);

    assertTrue(type instanceof CoreModel.TypeName);
    assertEquals("User", ((CoreModel.TypeName) type).name);
  }

  // ========== Await 测试 ==========

  @Test
  void testAwaitMaybeType() {
    // await Maybe<Int>
    var maybe = new CoreModel.Maybe();
    maybe.type = createTypeName("Int");

    var name = createName("maybeValue");
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);
    symbolTable.define(
      "maybeValue",
      maybe,
      aster.core.typecheck.model.SymbolInfo.SymbolKind.VARIABLE,
      new SymbolTable.DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty())
    );

    var await = new CoreModel.Await();
    await.expr = name;

    var type = checker.typeOfExpr(await, ctx);

    assertEquals("Int", ((CoreModel.TypeName) type).name);
    symbolTable.exitScope();
  }

  @Test
  void testAwaitResultType() {
    // await Result<Int, String>
    var result = new CoreModel.Result();
    result.ok = createTypeName("Int");
    result.err = createTypeName("String");

    var name = createName("resultValue");
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);
    symbolTable.define(
      "resultValue",
      result,
      aster.core.typecheck.model.SymbolInfo.SymbolKind.VARIABLE,
      new SymbolTable.DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty())
    );

    var await = new CoreModel.Await();
    await.expr = name;

    var type = checker.typeOfExpr(await, ctx);

    assertEquals("Int", ((CoreModel.TypeName) type).name);
    symbolTable.exitScope();
  }

  @Test
  void testAwaitNonAwaitableType() {
    // await Int - 不能 await 非 Maybe/Result 类型
    var name = createName("intValue");
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);
    symbolTable.define(
      "intValue",
      createTypeName("Int"),
      aster.core.typecheck.model.SymbolInfo.SymbolKind.VARIABLE,
      new SymbolTable.DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty())
    );

    var await = new CoreModel.Await();
    await.expr = name;

    checker.typeOfExpr(await, ctx);

    // 应该产生警告
    assertFalse(diagnostics.getDiagnostics().isEmpty());
    symbolTable.exitScope();
  }

  // ========== If 语句测试 ==========

  @Test
  void testIfStatementWithReturn() {
    // if true { return 42 } else { return 43 }
    var ifStmt = new CoreModel.If();
    ifStmt.cond = createBool(true);

    var thenReturn = new CoreModel.Return();
    thenReturn.expr = createInt(42);
    ifStmt.thenBlock = createBlock(List.of(thenReturn));

    var elseReturn = new CoreModel.Return();
    elseReturn.expr = createInt(43);
    ifStmt.elseBlock = createBlock(List.of(elseReturn));

    var type = checker.checkStatement(ifStmt, ctx);

    assertTrue(type.isPresent());
    assertEquals("Int", ((CoreModel.TypeName) type.get()).name);
  }

  @Test
  void testIfBranchTypeMismatch() {
    // if true { return 42 } else { return "hello" }
    var ifStmt = new CoreModel.If();
    ifStmt.cond = createBool(true);

    var thenReturn = new CoreModel.Return();
    thenReturn.expr = createInt(42);
    ifStmt.thenBlock = createBlock(List.of(thenReturn));

    var elseReturn = new CoreModel.Return();
    elseReturn.expr = createString("hello");
    ifStmt.elseBlock = createBlock(List.of(elseReturn));

    checker.checkStatement(ifStmt, ctx);

    // 应该产生分支类型不匹配的诊断
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testIfConditionTypeMismatch() {
    // if 42 { return 1 }  // 条件不是 Bool
    var ifStmt = new CoreModel.If();
    ifStmt.cond = createInt(42);

    var thenReturn = new CoreModel.Return();
    thenReturn.expr = createInt(1);
    ifStmt.thenBlock = createBlock(List.of(thenReturn));

    checker.checkStatement(ifStmt, ctx);

    // 应该产生条件类型不匹配的诊断
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== Match 语句测试 ==========

  @Test
  void testMatchStatement() {
    // match x { case 1: return 10; case 2: return 20 }
    var match = new CoreModel.Match();
    match.expr = createInt(1);

    var case1 = new CoreModel.Case();
    case1.pattern = createPatternInt(1);
    var return1 = new CoreModel.Return();
    return1.expr = createInt(10);
    case1.body = return1;

    var case2 = new CoreModel.Case();
    case2.pattern = createPatternInt(2);
    var return2 = new CoreModel.Return();
    return2.expr = createInt(20);
    case2.body = return2;

    match.cases = List.of(case1, case2);

    var type = checker.checkStatement(match, ctx);

    assertTrue(type.isPresent());
    assertEquals("Int", ((CoreModel.TypeName) type.get()).name);
  }

  @Test
  void testMatchBranchTypeMismatch() {
    // match x { case 1: return 10; case 2: return "hello" }
    var match = new CoreModel.Match();
    match.expr = createInt(1);

    var case1 = new CoreModel.Case();
    case1.pattern = createPatternInt(1);
    var return1 = new CoreModel.Return();
    return1.expr = createInt(10);
    case1.body = return1;

    var case2 = new CoreModel.Case();
    case2.pattern = createPatternInt(2);
    var return2 = new CoreModel.Return();
    return2.expr = createString("hello");
    case2.body = return2;

    match.cases = List.of(case1, case2);

    checker.checkStatement(match, ctx);

    // 应该产生分支类型不匹配的诊断
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== Let/Set 语句测试 ==========

  @Test
  void testLetStatement() {
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);

    var let = new CoreModel.Let();
    let.origin = createOrigin();
    let.name = "x";
    let.expr = createInt(42);

    checker.checkStatement(let, ctx);

    // 验证符号已定义
    var symbol = symbolTable.lookup("x");
    assertTrue(symbol.isPresent());
    assertEquals("Int", ((CoreModel.TypeName) symbol.get().type()).name);

    symbolTable.exitScope();
  }

  @Test
  void testSetImmutableVariable() {
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);

    // 定义不可变变量
    symbolTable.define(
      "x",
      createTypeName("Int"),
      aster.core.typecheck.model.SymbolInfo.SymbolKind.VARIABLE,
      new SymbolTable.DefineOptions(false, Optional.empty(), false, Optional.empty(), Optional.empty())
    );

    // 尝试赋值
    var set = new CoreModel.Set();
    set.name = "x";
    set.expr = createInt(43);

    checker.checkStatement(set, ctx);

    // 应该产生不可变变量赋值错误
    assertFalse(diagnostics.getDiagnostics().isEmpty());

    symbolTable.exitScope();
  }

  // ========== Block 和 Scope 测试 ==========

  @Test
  void testBlockWithReturn() {
    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createInt(42);

    var block = createBlock(List.of(returnStmt));

    var type = checker.checkBlock(block, ctx);

    assertTrue(type.isPresent());
    assertEquals("Int", ((CoreModel.TypeName) type.get()).name);
  }

  @Test
  void testScopeStatement() {
    symbolTable.enterScope(SymbolTable.ScopeType.MODULE);

    var scope = new CoreModel.Scope();
    var let = new CoreModel.Let();
    let.origin = createOrigin();
    let.name = "x";
    let.expr = createInt(42);

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createName("x");

    scope.statements = List.of(let, returnStmt);

    var type = checker.checkStatement(scope, ctx);

    assertTrue(type.isPresent());
    assertEquals("Int", ((CoreModel.TypeName) type.get()).name);

    // 退出 scope 后，x 应该不可见
    var symbol = symbolTable.lookup("x");
    assertTrue(symbol.isEmpty());

    symbolTable.exitScope();
  }

  // ========== 辅助方法 ==========

  private CoreModel.Bool createBool(boolean value) {
    var bool = new CoreModel.Bool();
    bool.value = value;
    return bool;
  }

  private CoreModel.IntE createInt(int value) {
    var intExpr = new CoreModel.IntE();
    intExpr.value = value;
    return intExpr;
  }

  private CoreModel.LongE createLong(long value) {
    var longExpr = new CoreModel.LongE();
    longExpr.value = value;
    return longExpr;
  }

  private CoreModel.DoubleE createDouble(double value) {
    var doubleExpr = new CoreModel.DoubleE();
    doubleExpr.value = value;
    return doubleExpr;
  }

  private CoreModel.StringE createString(String value) {
    var stringExpr = new CoreModel.StringE();
    stringExpr.value = value;
    return stringExpr;
  }

  private CoreModel.Name createName(String name) {
    var nameExpr = new CoreModel.Name();
    nameExpr.name = name;
    return nameExpr;
  }

  private CoreModel.TypeName createTypeName(String name) {
    var type = new CoreModel.TypeName();
    type.name = name;
    return type;
  }

  private CoreModel.Param createParameter(String name, String typeName) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = createTypeName(typeName);
    return param;
  }

  private CoreModel.Block createBlock(List<CoreModel.Stmt> statements) {
    var block = new CoreModel.Block();
    block.statements = statements;
    return block;
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
}
