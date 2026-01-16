package aster.core.typecheck;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StaticAnalyzer 单元测试
 * <p>
 * 测试静态分析器的核心功能：
 * - 语句计数
 * - 返回路径计数
 * - 分支计数
 * - 嵌套深度计算
 * - 复杂度评分
 */
class StaticAnalyzerTest {

  private StaticAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer = new StaticAnalyzer();
  }

  // ========== 基础计数测试 ==========

  @Test
  void testSimpleFunctionWithNoBody() {
    // func external(): Int  // 无函数体
    var func = new CoreModel.Func();
    func.name = "external";
    func.body = null;

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals("external", metrics.functionName());
    assertEquals(0, metrics.statementCount());
    assertEquals(0, metrics.returnCount());
    assertEquals(0, metrics.branchCount());
    assertEquals(0, metrics.maxNestingDepth());
  }

  @Test
  void testSimpleFunctionWithEmptyBody() {
    // func test(): Int { }
    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of());

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals("test", metrics.functionName());
    assertEquals(0, metrics.statementCount());
    assertEquals(0, metrics.returnCount());
    assertEquals(0, metrics.branchCount());
    assertEquals(0, metrics.maxNestingDepth());
  }

  @Test
  void testSimpleFunctionWithSingleReturn() {
    // func test(): Int { return 42 }
    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of(createReturn(createInt(42))));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals(1, metrics.statementCount());
    assertEquals(1, metrics.returnCount());
    assertEquals(0, metrics.branchCount());
    assertEquals(1, metrics.maxNestingDepth());  // 函数体本身算 1 层
  }

  @Test
  void testFunctionWithMultipleStatements() {
    // func test(): Int {
    //   let x = 1
    //   let y = 2
    //   return x + y
    // }
    var let1 = createLet("x", createInt(1));
    var let2 = createLet("y", createInt(2));
    var ret = createReturn(createInt(3));

    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of(let1, let2, ret));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals(3, metrics.statementCount());
    assertEquals(1, metrics.returnCount());
    assertEquals(0, metrics.branchCount());
    assertEquals(1, metrics.maxNestingDepth());  // 函数体本身算 1 层
  }

  // ========== 分支计数测试 ==========

  @Test
  void testFunctionWithIfStatement() {
    // func test(x: Bool): Int {
    //   if x {
    //     return 1
    //   } else {
    //     return 2
    //   }
    // }
    var ifStmt = new CoreModel.If();
    ifStmt.cond = createBool(true);
    ifStmt.thenBlock = createBlock(List.of(createReturn(createInt(1))));
    ifStmt.elseBlock = createBlock(List.of(createReturn(createInt(2))));

    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of(ifStmt));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals(3, metrics.statementCount());  // if + 2 returns
    assertEquals(2, metrics.returnCount());
    assertEquals(1, metrics.branchCount());     // 1 if
    assertEquals(2, metrics.maxNestingDepth()); // 函数体 1 层 + if 1 层 = 2
  }

  @Test
  void testFunctionWithNestedIf() {
    // func test(): Int {
    //   if true {
    //     if false {
    //       return 1
    //     }
    //   }
    //   return 2
    // }
    var innerIf = new CoreModel.If();
    innerIf.cond = createBool(false);
    innerIf.thenBlock = createBlock(List.of(createReturn(createInt(1))));

    var outerIf = new CoreModel.If();
    outerIf.cond = createBool(true);
    outerIf.thenBlock = createBlock(List.of(innerIf));

    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of(outerIf, createReturn(createInt(2))));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals(4, metrics.statementCount());  // outer if + inner if + 2 returns
    assertEquals(2, metrics.returnCount());
    assertEquals(2, metrics.branchCount());     // 2 ifs
    assertEquals(3, metrics.maxNestingDepth()); // 函数体 1 + outer if 1 + inner if 1 = 3
  }

  @Test
  void testFunctionWithMatch() {
    // func test(x: Int): String {
    //   match x {
    //     case 1: return "one"
    //     case 2: return "two"
    //     case 3: return "three"
    //   }
    // }
    var case1 = createCase(createPatternInt(1), createReturn(createString("one")));
    var case2 = createCase(createPatternInt(2), createReturn(createString("two")));
    var case3 = createCase(createPatternInt(3), createReturn(createString("three")));

    var match = new CoreModel.Match();
    match.expr = createInt(0);
    match.cases = List.of(case1, case2, case3);

    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of(match));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals(4, metrics.statementCount());  // match + 3 returns
    assertEquals(3, metrics.returnCount());
    assertEquals(3, metrics.branchCount());     // 3 cases
    assertEquals(1, metrics.maxNestingDepth()); // match 嵌套 1 层
  }

  // ========== 嵌套深度测试 ==========

  @Test
  void testDeepNesting() {
    // func test(): Int {
    //   if true {           // depth 1
    //     if true {         // depth 2
    //       if true {       // depth 3
    //         return 1
    //       }
    //     }
    //   }
    //   return 0
    // }
    var innermost = new CoreModel.If();
    innermost.cond = createBool(true);
    innermost.thenBlock = createBlock(List.of(createReturn(createInt(1))));

    var middle = new CoreModel.If();
    middle.cond = createBool(true);
    middle.thenBlock = createBlock(List.of(innermost));

    var outer = new CoreModel.If();
    outer.cond = createBool(true);
    outer.thenBlock = createBlock(List.of(middle));

    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of(outer, createReturn(createInt(0))));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals(5, metrics.statementCount());  // 3 ifs + 2 returns
    assertEquals(2, metrics.returnCount());
    assertEquals(3, metrics.branchCount());
    assertEquals(4, metrics.maxNestingDepth()); // 函数体 1 + 3 层 if = 4
  }

  // ========== 复杂度评分测试 ==========

  @Test
  void testSimpleFunctionComplexityScore() {
    // func simple(): Int { return 42 }
    var func = new CoreModel.Func();
    func.name = "simple";
    func.body = createBlock(List.of(createReturn(createInt(42))));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertTrue(metrics.complexityScore() <= 30, "Simple function should have low complexity");
    assertEquals("简单", metrics.complexityLevel());
    assertFalse(metrics.isComplex());
  }

  @Test
  void testComplexFunctionComplexityScore() {
    // func complex(): Int {
    //   if true {
    //     if true {
    //       if true {
    //         if true {
    //           return 1
    //         }
    //       }
    //     }
    //   }
    //   return 0
    // }
    var innermost = new CoreModel.If();
    innermost.cond = createBool(true);
    innermost.thenBlock = createBlock(List.of(createReturn(createInt(1))));

    var level3 = new CoreModel.If();
    level3.cond = createBool(true);
    level3.thenBlock = createBlock(List.of(innermost));

    var level2 = new CoreModel.If();
    level2.cond = createBool(true);
    level2.thenBlock = createBlock(List.of(level3));

    var level1 = new CoreModel.If();
    level1.cond = createBool(true);
    level1.thenBlock = createBlock(List.of(level2));

    var func = new CoreModel.Func();
    func.name = "complex";
    func.body = createBlock(List.of(level1, createReturn(createInt(0))));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertTrue(metrics.maxNestingDepth() > 3);
    assertTrue(metrics.complexityScore() > 60, "Deeply nested function should have high complexity");
    assertEquals("复杂", metrics.complexityLevel());
    assertTrue(metrics.isComplex());
  }

  @Test
  void testManyBranchesIncreaseComplexity() {
    // func test(x: Int): String {
    //   match x {
    //     case 1: return "one"
    //     case 2: return "two"
    //     ...
    //     case 15: return "fifteen"
    //   }
    // }
    var cases = java.util.stream.IntStream.range(1, 16)
      .mapToObj(i -> createCase(createPatternInt(i), createReturn(createString("num"))))
      .toList();

    var match = new CoreModel.Match();
    match.expr = createInt(0);
    match.cases = cases;

    var func = new CoreModel.Func();
    func.name = "test";
    func.body = createBlock(List.of(match));

    var metrics = analyzer.analyzeFunctionMetrics(func);

    assertEquals(15, metrics.branchCount());
    assertTrue(metrics.isComplex(), "Many branches should mark function as complex");
  }

  // ========== 辅助方法 ==========

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

  private CoreModel.Let createLet(String name, CoreModel.Expr expr) {
    var let = new CoreModel.Let();
    let.name = name;
    let.expr = expr;
    let.origin = createOrigin();
    return let;
  }

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

  private CoreModel.Case createCase(CoreModel.Pattern pattern, CoreModel.Stmt body) {
    var caseStmt = new CoreModel.Case();
    caseStmt.pattern = pattern;
    caseStmt.body = body;
    return caseStmt;
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
