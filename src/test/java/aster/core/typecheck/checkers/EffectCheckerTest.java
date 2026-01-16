package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.EffectConfig;
import aster.core.typecheck.SymbolTable;
import aster.core.typecheck.TypeSystem;
import aster.core.typecheck.checkers.EffectChecker.Effect;
import aster.core.typecheck.model.VisitorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EffectChecker 单元测试
 * <p>
 * 测试效果检查器的核心功能：
 * - 效果推断
 * - 效果Join运算（格论最小上界）
 * - 效果子类型关系
 * - 效果兼容性检查
 */
class EffectCheckerTest {

  private EffectChecker checker;
  private DiagnosticBuilder diagnostics;
  private VisitorContext ctx;

  @BeforeEach
  void setUp() {
    var symbolTable = new SymbolTable();
    var effectConfig = EffectConfig.getInstance();
    diagnostics = new DiagnosticBuilder();
    checker = new EffectChecker(symbolTable, effectConfig, diagnostics);

    ctx = new VisitorContext(
      symbolTable,
      diagnostics,
      new HashMap<>(),
      TypeSystem.unknown(),
      VisitorContext.Effect.PURE
    );
  }

  // ========== 效果字符串解析测试 ==========

  @Test
  void testEffectFromString() {
    assertEquals(Effect.PURE, Effect.fromString("pure"));
    assertEquals(Effect.PURE, Effect.fromString(""));
    assertEquals(Effect.CPU, Effect.fromString("cpu"));
    assertEquals(Effect.CPU, Effect.fromString("CPU"));
    assertEquals(Effect.IO, Effect.fromString("io"));
    assertEquals(Effect.IO, Effect.fromString("IO"));
    assertEquals(Effect.PURE, Effect.fromString("unknown")); // 默认为PURE
  }

  @Test
  void testEffectToString() {
    assertEquals("pure", Effect.PURE.toString());
    assertEquals("cpu", Effect.CPU.toString());
    assertEquals("io", Effect.IO.toString());
    assertEquals("async", Effect.ASYNC.toString());
  }

  // ========== 效果Join测试 ==========

  @Test
  void testJoinPure() {
    assertEquals(Effect.PURE, checker.join(Effect.PURE, Effect.PURE));
  }

  @Test
  void testJoinPureWithCPU() {
    assertEquals(Effect.CPU, checker.join(Effect.PURE, Effect.CPU));
    assertEquals(Effect.CPU, checker.join(Effect.CPU, Effect.PURE));
  }

  @Test
  void testJoinPureWithIO() {
    assertEquals(Effect.IO, checker.join(Effect.PURE, Effect.IO));
    assertEquals(Effect.IO, checker.join(Effect.IO, Effect.PURE));
  }

  @Test
  void testJoinCPU() {
    assertEquals(Effect.CPU, checker.join(Effect.CPU, Effect.CPU));
  }

  @Test
  void testJoinCPUWithIO() {
    assertEquals(Effect.IO, checker.join(Effect.CPU, Effect.IO));
    assertEquals(Effect.IO, checker.join(Effect.IO, Effect.CPU));
  }

  @Test
  void testJoinIO() {
    assertEquals(Effect.IO, checker.join(Effect.IO, Effect.IO));
  }

  // ========== 效果子类型关系测试 ==========

  @Test
  void testIsSubEffectPure() {
    // PURE 是 PURE, CPU, IO 的子效果
    assertTrue(checker.isSubEffect(Effect.PURE, Effect.PURE));
    assertTrue(checker.isSubEffect(Effect.PURE, Effect.CPU));
    assertTrue(checker.isSubEffect(Effect.PURE, Effect.IO));
  }

  @Test
  void testIsSubEffectCPU() {
    // CPU 不是 PURE 的子效果，是 CPU 和 IO 的子效果
    assertFalse(checker.isSubEffect(Effect.CPU, Effect.PURE));
    assertTrue(checker.isSubEffect(Effect.CPU, Effect.CPU));
    assertTrue(checker.isSubEffect(Effect.CPU, Effect.IO));
  }

  @Test
  void testIsSubEffectIO() {
    // IO 只是 IO 的子效果
    assertFalse(checker.isSubEffect(Effect.IO, Effect.PURE));
    assertFalse(checker.isSubEffect(Effect.IO, Effect.CPU));
    assertTrue(checker.isSubEffect(Effect.IO, Effect.IO));
  }

  // ========== 效果比较测试 ==========

  @Test
  void testCompareEffects() {
    assertTrue(checker.compareEffects(Effect.PURE, Effect.CPU) < 0);
    assertTrue(checker.compareEffects(Effect.CPU, Effect.IO) < 0);
    assertTrue(checker.compareEffects(Effect.PURE, Effect.IO) < 0);
    assertEquals(0, checker.compareEffects(Effect.PURE, Effect.PURE));
    assertEquals(0, checker.compareEffects(Effect.CPU, Effect.CPU));
    assertEquals(0, checker.compareEffects(Effect.IO, Effect.IO));
  }

  // ========== 表达式效果推断测试 ==========

  @Test
  void testInferEffectLiteral() {
    // 字面量是纯的
    var intExpr = createInt(42);
    assertEquals(Effect.PURE, checker.inferEffect(intExpr, ctx));

    var stringExpr = createString("hello");
    assertEquals(Effect.PURE, checker.inferEffect(stringExpr, ctx));

    var boolExpr = createBool(true);
    assertEquals(Effect.PURE, checker.inferEffect(boolExpr, ctx));
  }

  @Test
  void testInferEffectName() {
    // 名称引用是纯的
    var name = createName("x");
    assertEquals(Effect.PURE, checker.inferEffect(name, ctx));
  }

  @Test
  void testInferEffectOk() {
    // Ok(42) - 纯的
    var ok = new CoreModel.Ok();
    ok.expr = createInt(42);

    assertEquals(Effect.PURE, checker.inferEffect(ok, ctx));
  }

  @Test
  void testInferEffectSome() {
    // Some(42) - 纯的
    var some = new CoreModel.Some();
    some.expr = createInt(42);

    assertEquals(Effect.PURE, checker.inferEffect(some, ctx));
  }

  @Test
  void testInferEffectConstruct() {
    // Construct { field: 42 } - 纯的
    var field = new CoreModel.FieldInit();
    field.name = "value";
    field.expr = createInt(42);

    var construct = new CoreModel.Construct();
    construct.typeName = "User";
    construct.fields = List.of(field);

    assertEquals(Effect.PURE, checker.inferEffect(construct, ctx));
  }

  @Test
  void testInferEffectCall() {
    // 函数调用 - 根据前缀推断
    var call = new CoreModel.Call();
    call.target = createName("pureFunc");
    call.args = List.of();

    // 无IO或CPU前缀,默认PURE
    assertEquals(Effect.PURE, checker.inferEffect(call, ctx));
  }

  @Test
  void testInferEffectCallWithIOPrefix() {
    // IO函数调用
    var call = new CoreModel.Call();
    call.target = createName("IO.readFile");
    call.args = List.of();

    // IO.readFile 以 "IO." 开头（IO前缀）
    assertEquals(Effect.IO, checker.inferEffect(call, ctx));
  }

  @Test
  void testInferEffectCallWithCPUPrefix() {
    // CPU函数调用 - 由于默认 CPU 前缀列表为空，此测试验证传播逻辑
    var call = new CoreModel.Call();
    call.target = createName("calculate");
    call.args = List.of();

    // 无 CPU 前缀配置，默认为 PURE
    assertEquals(Effect.PURE, checker.inferEffect(call, ctx));
  }

  @Test
  void testInferEffectLambda() {
    // Lambda { return 42 } - 纯的
    var lambda = new CoreModel.Lambda();
    lambda.params = List.of();
    lambda.ret = createTypeName("Int");

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createInt(42);

    lambda.body = createBlock(List.of(returnStmt));

    assertEquals(Effect.PURE, checker.inferEffect(lambda, ctx));
  }

  // ========== 语句效果推断测试 ==========

  @Test
  void testInferStatementEffectLet() {
    // let x = 42
    var let = new CoreModel.Let();
    let.name = "x";
    let.expr = createInt(42);

    assertEquals(Effect.PURE, checker.inferStatementEffect(let, ctx));
  }

  @Test
  void testInferStatementEffectReturn() {
    // return 42
    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createInt(42);

    assertEquals(Effect.PURE, checker.inferStatementEffect(returnStmt, ctx));
  }

  @Test
  void testInferStatementEffectIf() {
    // if true { return 1 } else { return 2 }
    var ifStmt = new CoreModel.If();
    ifStmt.cond = createBool(true);

    var thenReturn = new CoreModel.Return();
    thenReturn.expr = createInt(1);
    ifStmt.thenBlock = createBlock(List.of(thenReturn));

    var elseReturn = new CoreModel.Return();
    elseReturn.expr = createInt(2);
    ifStmt.elseBlock = createBlock(List.of(elseReturn));

    assertEquals(Effect.PURE, checker.inferStatementEffect(ifStmt, ctx));
  }

  @Test
  void testInferStatementEffectIfWithIO() {
    // if true { return IO.readFile() }
    var call = new CoreModel.Call();
    call.target = createName("IO.readFile");
    call.args = List.of();

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = call;

    var ifStmt = new CoreModel.If();
    ifStmt.cond = createBool(true);
    ifStmt.thenBlock = createBlock(List.of(returnStmt));

    assertEquals(Effect.IO, checker.inferStatementEffect(ifStmt, ctx));
  }

  @Test
  void testInferStatementEffectMatch() {
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

    assertEquals(Effect.PURE, checker.inferStatementEffect(match, ctx));
  }

  @Test
  void testInferStatementEffectBlock() {
    // { let x = 1; return x }
    var let = new CoreModel.Let();
    let.name = "x";
    let.expr = createInt(1);

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createName("x");

    var block = createBlock(List.of(let, returnStmt));

    assertEquals(Effect.PURE, checker.inferStatementEffect(block, ctx));
  }

  @Test
  void testInferBlockEffect() {
    // { let x = 1; let y = 2; return x }
    var let1 = new CoreModel.Let();
    let1.name = "x";
    let1.expr = createInt(1);

    var let2 = new CoreModel.Let();
    let2.name = "y";
    let2.expr = createInt(2);

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createName("x");

    var block = createBlock(List.of(let1, let2, returnStmt));

    assertEquals(Effect.PURE, checker.inferBlockEffect(block, ctx));
  }

  @Test
  void testInferBlockEffectWithIO() {
    // { let x = IO.readFile(); return x }
    var call = new CoreModel.Call();
    call.target = createName("IO.readFile");
    call.args = List.of();

    var let = new CoreModel.Let();
    let.name = "x";
    let.expr = call;

    var returnStmt = new CoreModel.Return();
    returnStmt.expr = createName("x");

    var block = createBlock(List.of(let, returnStmt));

    assertEquals(Effect.IO, checker.inferBlockEffect(block, ctx));
  }

  // ========== 效果兼容性检查测试 ==========

  @Test
  void testCheckEffectCompatibilityValid() {
    // PURE ⊑ IO - 有效
    checker.checkEffectCompatibility(Effect.IO, Effect.PURE, Optional.empty());

    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testCheckEffectCompatibilityInvalid() {
    // IO ⋢ PURE - 无效
    checker.checkEffectCompatibility(Effect.PURE, Effect.IO, Optional.empty());

    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testCheckEffectCompatibilityExact() {
    // CPU ⊑ CPU - 有效
    checker.checkEffectCompatibility(Effect.CPU, Effect.CPU, Optional.empty());

    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== 辅助方法 ==========

  private CoreModel.IntE createInt(int value) {
    var intExpr = new CoreModel.IntE();
    intExpr.value = value;
    return intExpr;
  }

  private CoreModel.StringE createString(String value) {
    var stringExpr = new CoreModel.StringE();
    stringExpr.value = value;
    return stringExpr;
  }

  private CoreModel.Bool createBool(boolean value) {
    var bool = new CoreModel.Bool();
    bool.value = value;
    return bool;
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

  // ========== ASYNC 效果测试 ==========

  @Test
  void testEffectFromStringAsync() {
    // 测试 ASYNC 字符串解析
    assertEquals(Effect.ASYNC, Effect.fromString("async"));
    assertEquals(Effect.ASYNC, Effect.fromString("ASYNC"));
    assertEquals(Effect.ASYNC, Effect.fromString("Async"));
  }

  @Test
  void testJoinPureWithAsync() {
    // PURE ⊔ ASYNC = ASYNC
    assertEquals(Effect.ASYNC, checker.join(Effect.PURE, Effect.ASYNC));
    assertEquals(Effect.ASYNC, checker.join(Effect.ASYNC, Effect.PURE));
  }

  @Test
  void testJoinCPUWithAsync() {
    // CPU ⊔ ASYNC = ASYNC
    assertEquals(Effect.ASYNC, checker.join(Effect.CPU, Effect.ASYNC));
    assertEquals(Effect.ASYNC, checker.join(Effect.ASYNC, Effect.CPU));
  }

  @Test
  void testJoinIOWithAsync() {
    // IO ⊔ ASYNC = ASYNC
    assertEquals(Effect.ASYNC, checker.join(Effect.IO, Effect.ASYNC));
    assertEquals(Effect.ASYNC, checker.join(Effect.ASYNC, Effect.IO));
  }

  @Test
  void testJoinAsync() {
    // ASYNC ⊔ ASYNC = ASYNC
    assertEquals(Effect.ASYNC, checker.join(Effect.ASYNC, Effect.ASYNC));
  }

  @Test
  void testIsSubEffectAsync() {
    // 所有效果都是 ASYNC 的子效果
    assertTrue(checker.isSubEffect(Effect.PURE, Effect.ASYNC));
    assertTrue(checker.isSubEffect(Effect.CPU, Effect.ASYNC));
    assertTrue(checker.isSubEffect(Effect.IO, Effect.ASYNC));
    assertTrue(checker.isSubEffect(Effect.ASYNC, Effect.ASYNC));

    // ASYNC 不是 IO/CPU/PURE 的子效果
    assertFalse(checker.isSubEffect(Effect.ASYNC, Effect.IO));
    assertFalse(checker.isSubEffect(Effect.ASYNC, Effect.CPU));
    assertFalse(checker.isSubEffect(Effect.ASYNC, Effect.PURE));
  }

  @Test
  void testInferEffectAwait() {
    // await(42) - ASYNC
    var await = new CoreModel.Await();
    await.expr = createInt(42);

    assertEquals(Effect.ASYNC, checker.inferEffect(await, ctx));
  }

  @Test
  void testInferEffectAwaitWithIO() {
    // await(IO.readFile()) - ASYNC（await 的效果总是 ASYNC）
    var call = new CoreModel.Call();
    call.target = createName("IO.readFile");
    call.args = List.of();

    var await = new CoreModel.Await();
    await.expr = call;

    assertEquals(Effect.ASYNC, checker.inferEffect(await, ctx));
  }

  @Test
  void testCompareEffectsAsync() {
    // ASYNC 的 ordinal 最大
    assertTrue(checker.compareEffects(Effect.PURE, Effect.ASYNC) < 0);
    assertTrue(checker.compareEffects(Effect.CPU, Effect.ASYNC) < 0);
    assertTrue(checker.compareEffects(Effect.IO, Effect.ASYNC) < 0);
    assertEquals(0, checker.compareEffects(Effect.ASYNC, Effect.ASYNC));
  }

  @Test
  void testCheckEffectCompatibilityAsyncValid() {
    // PURE ⊑ ASYNC - 有效
    checker.checkEffectCompatibility(Effect.ASYNC, Effect.PURE, Optional.empty());
    assertTrue(diagnostics.getDiagnostics().isEmpty());

    // IO ⊑ ASYNC - 有效
    checker.checkEffectCompatibility(Effect.ASYNC, Effect.IO, Optional.empty());
    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testCheckEffectCompatibilityAsyncInvalid() {
    // ASYNC ⋢ IO - 无效
    checker.checkEffectCompatibility(Effect.IO, Effect.ASYNC, Optional.empty());
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }
}
