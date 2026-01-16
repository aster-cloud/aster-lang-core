package aster.core.typecheck.checkers;

import aster.core.ir.CoreModel;
import aster.core.typecheck.DiagnosticBuilder;
import aster.core.typecheck.TypeSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GenericTypeChecker 单元测试
 * <p>
 * 测试泛型类型检查器的核心功能：
 * - 类型参数数量验证
 * - 类型统一（Hindley-Milner）
 * - 类型变量替换
 * - 泛型类型实例化
 * - 类型应用验证
 */
class GenericTypeCheckerTest {

  private GenericTypeChecker checker;
  private DiagnosticBuilder diagnostics;

  @BeforeEach
  void setUp() {
    diagnostics = new DiagnosticBuilder();
    checker = new GenericTypeChecker(diagnostics);
  }

  // ========== 类型参数数量检查测试 ==========

  @Test
  void testCheckTypeParameterCountCorrect() {
    // List<Int> - 1个参数,正确
    List<CoreModel.Type> typeArgs = List.of(createTypeName("Int"));

    checker.checkTypeParameterCount(1, typeArgs, "List", Optional.empty());

    // 不应该产生错误
    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testCheckTypeParameterCountMismatch() {
    // List<Int, String> - 2个参数,应该是1个
    List<CoreModel.Type> typeArgs = List.of(createTypeName("Int"), createTypeName("String"));

    checker.checkTypeParameterCount(1, typeArgs, "List", Optional.empty());

    // 应该产生错误
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testCheckTypeParameterCountTooFew() {
    // Map<Int> - 1个参数,应该是2个
    List<CoreModel.Type> typeArgs = List.of(createTypeName("Int"));

    checker.checkTypeParameterCount(2, typeArgs, "Map", Optional.empty());

    // 应该产生错误
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== 类型变量替换测试 ==========

  @Test
  void testSubstituteTypeVarSimple() {
    // T -> Int
    var typeVar = createTypeVar("T");
    Map<String, CoreModel.Type> bindings = Map.of("T", createTypeName("Int"));

    var result = checker.substituteTypeVars(typeVar, bindings);

    assertTrue(result instanceof CoreModel.TypeName);
    assertEquals("Int", ((CoreModel.TypeName) result).name);
  }

  @Test
  void testSubstituteTypeVarNotBound() {
    // T (无绑定) -> T
    var typeVar = createTypeVar("T");
    var bindings = new HashMap<String, CoreModel.Type>();

    var result = checker.substituteTypeVars(typeVar, bindings);

    assertTrue(result instanceof CoreModel.TypeVar);
    assertEquals("T", ((CoreModel.TypeVar) result).name);
  }

  @Test
  void testSubstituteTypeVarInTypeApp() {
    // List<T> with T -> Int => List<Int>
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "List";
    typeApp.args = List.of(createTypeVar("T"));

    Map<String, CoreModel.Type> bindings = Map.of("T", createTypeName("Int"));

    var result = checker.substituteTypeVars(typeApp, bindings);

    assertTrue(result instanceof CoreModel.TypeApp);
    var resultApp = (CoreModel.TypeApp) result;
    assertEquals("List", resultApp.base);
    assertEquals(1, resultApp.args.size());
    assertTrue(resultApp.args.get(0) instanceof CoreModel.TypeName);
    assertEquals("Int", ((CoreModel.TypeName) resultApp.args.get(0)).name);
  }

  @Test
  void testSubstituteTypeVarInFuncType() {
    // (T) -> T with T -> Int => (Int) -> Int
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeVar("T"));
    funcType.ret = createTypeVar("T");

    Map<String, CoreModel.Type> bindings = Map.of("T", createTypeName("Int"));

    var result = checker.substituteTypeVars(funcType, bindings);

    assertTrue(result instanceof CoreModel.FuncType);
    var resultFunc = (CoreModel.FuncType) result;
    assertEquals(1, resultFunc.params.size());
    assertEquals("Int", ((CoreModel.TypeName) resultFunc.params.get(0)).name);
    assertEquals("Int", ((CoreModel.TypeName) resultFunc.ret).name);
  }

  @Test
  void testSubstituteTypeVarInResult() {
    // Result<T, E> with T -> Int, E -> String => Result<Int, String>
    var result = new CoreModel.Result();
    result.ok = createTypeVar("T");
    result.err = createTypeVar("E");

    Map<String, CoreModel.Type> bindings = Map.of(
      "T", createTypeName("Int"),
      "E", createTypeName("String")
    );

    var substituted = checker.substituteTypeVars(result, bindings);

    assertTrue(substituted instanceof CoreModel.Result);
    var resultType = (CoreModel.Result) substituted;
    assertEquals("Int", ((CoreModel.TypeName) resultType.ok).name);
    assertEquals("String", ((CoreModel.TypeName) resultType.err).name);
  }

  @Test
  void testSubstituteTypeVarInMaybe() {
    // Maybe<T> with T -> Int => Maybe<Int>
    var maybe = new CoreModel.Maybe();
    maybe.type = createTypeVar("T");

    Map<String, CoreModel.Type> bindings = Map.of("T", createTypeName("Int"));

    var result = checker.substituteTypeVars(maybe, bindings);

    assertTrue(result instanceof CoreModel.Maybe);
    var resultMaybe = (CoreModel.Maybe) result;
    assertEquals("Int", ((CoreModel.TypeName) resultMaybe.type).name);
  }

  @Test
  void testSubstituteTypeVarInOption() {
    // Option<T> with T -> Int => Option<Int>
    var option = new CoreModel.Option();
    option.type = createTypeVar("T");

    Map<String, CoreModel.Type> bindings = Map.of("T", createTypeName("Int"));

    var result = checker.substituteTypeVars(option, bindings);

    assertTrue(result instanceof CoreModel.Option);
    var resultOption = (CoreModel.Option) result;
    assertEquals("Int", ((CoreModel.TypeName) resultOption.type).name);
  }

  @Test
  void testSubstituteTypeVarInList() {
    // List<T> with T -> Int => List<Int>
    var list = new CoreModel.ListT();
    list.type = createTypeVar("T");

    Map<String, CoreModel.Type> bindings = Map.of("T", createTypeName("Int"));

    var result = checker.substituteTypeVars(list, bindings);

    assertTrue(result instanceof CoreModel.ListT);
    var resultList = (CoreModel.ListT) result;
    assertEquals("Int", ((CoreModel.TypeName) resultList.type).name);
  }

  @Test
  void testSubstituteTypeVarInMap() {
    // Map<K, V> with K -> String, V -> Int => Map<String, Int>
    var map = new CoreModel.MapT();
    map.key = createTypeVar("K");
    map.val = createTypeVar("V");

    Map<String, CoreModel.Type> bindings = Map.of(
      "K", createTypeName("String"),
      "V", createTypeName("Int")
    );

    var result = checker.substituteTypeVars(map, bindings);

    assertTrue(result instanceof CoreModel.MapT);
    var resultMap = (CoreModel.MapT) result;
    assertEquals("String", ((CoreModel.TypeName) resultMap.key).name);
    assertEquals("Int", ((CoreModel.TypeName) resultMap.val).name);
  }

  @Test
  void testSubstituteTypeVarNested() {
    // List<Result<T, E>> with T -> Int, E -> String => List<Result<Int, String>>
    var resultType = new CoreModel.Result();
    resultType.ok = createTypeVar("T");
    resultType.err = createTypeVar("E");

    var list = new CoreModel.ListT();
    list.type = resultType;

    Map<String, CoreModel.Type> bindings = Map.of(
      "T", createTypeName("Int"),
      "E", createTypeName("String")
    );

    var substituted = checker.substituteTypeVars(list, bindings);

    assertTrue(substituted instanceof CoreModel.ListT);
    var listResult = (CoreModel.ListT) substituted;
    assertTrue(listResult.type instanceof CoreModel.Result);
    var innerResult = (CoreModel.Result) listResult.type;
    assertEquals("Int", ((CoreModel.TypeName) innerResult.ok).name);
    assertEquals("String", ((CoreModel.TypeName) innerResult.err).name);
  }

  // ========== 类型统一测试 ==========

  @Test
  void testUnifyGenericCallSuccess() {
    // 函数 (T) -> T, 调用参数 Int
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeVar("T"));
    funcType.ret = createTypeVar("T");

    List<CoreModel.Type> argTypes = List.of(createTypeName("Int"));

    var bindings = checker.unifyGenericCall(funcType, argTypes, Optional.empty());

    // 应该绑定 T -> Int
    assertTrue(bindings.containsKey("T"));
    assertEquals("Int", ((CoreModel.TypeName) bindings.get("T")).name);
    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testUnifyGenericCallMultipleParams() {
    // 函数 (T, T) -> T, 调用参数 (Int, Int)
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeVar("T"), createTypeVar("T"));
    funcType.ret = createTypeVar("T");

    List<CoreModel.Type> argTypes = List.of(createTypeName("Int"), createTypeName("Int"));

    var bindings = checker.unifyGenericCall(funcType, argTypes, Optional.empty());

    // 应该绑定 T -> Int
    assertTrue(bindings.containsKey("T"));
    assertEquals("Int", ((CoreModel.TypeName) bindings.get("T")).name);
    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testUnifyGenericCallArityMismatch() {
    // 函数 (T) -> T, 调用参数 (Int, String) - 参数数量不匹配
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeVar("T"));
    funcType.ret = createTypeVar("T");

    List<CoreModel.Type> argTypes = List.of(createTypeName("Int"), createTypeName("String"));

    checker.unifyGenericCall(funcType, argTypes, Optional.empty());

    // 应该产生错误
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testUnifyGenericCallTypeMismatch() {
    // 函数 (Int) -> String, 调用参数 Bool - 类型不匹配
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeName("Int"));
    funcType.ret = createTypeName("String");

    List<CoreModel.Type> argTypes = List.of(createTypeName("Bool"));

    checker.unifyGenericCall(funcType, argTypes, Optional.empty());

    // 应该产生错误
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== 类型应用验证测试 ==========

  @Test
  void testValidateTypeApplicationListCorrect() {
    // List<Int> - 正确
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "List";
    typeApp.args = List.of(createTypeName("Int"));

    checker.validateTypeApplication(typeApp, Optional.empty());

    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testValidateTypeApplicationListWrong() {
    // List<Int, String> - 参数数量错误
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "List";
    typeApp.args = List.of(createTypeName("Int"), createTypeName("String"));

    checker.validateTypeApplication(typeApp, Optional.empty());

    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testValidateTypeApplicationMapCorrect() {
    // Map<String, Int> - 正确
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "Map";
    typeApp.args = List.of(createTypeName("String"), createTypeName("Int"));

    checker.validateTypeApplication(typeApp, Optional.empty());

    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testValidateTypeApplicationMapWrong() {
    // Map<Int> - 参数数量错误
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "Map";
    typeApp.args = List.of(createTypeName("Int"));

    checker.validateTypeApplication(typeApp, Optional.empty());

    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testValidateTypeApplicationResultCorrect() {
    // Result<Int, String> - 正确
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "Result";
    typeApp.args = List.of(createTypeName("Int"), createTypeName("String"));

    checker.validateTypeApplication(typeApp, Optional.empty());

    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testValidateTypeApplicationMaybeCorrect() {
    // Maybe<Int> - 正确
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "Maybe";
    typeApp.args = List.of(createTypeName("Int"));

    checker.validateTypeApplication(typeApp, Optional.empty());

    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testValidateTypeApplicationOptionCorrect() {
    // Option<Int> - 正确
    var typeApp = new CoreModel.TypeApp();
    typeApp.base = "Option";
    typeApp.args = List.of(createTypeName("Int"));

    checker.validateTypeApplication(typeApp, Optional.empty());

    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== 类型变量一致性检查测试 ==========

  @Test
  void testCheckTypeVarConsistencyFirstBinding() {
    // 第一次绑定 T -> Int
    var bindings = new HashMap<String, CoreModel.Type>();

    boolean result = checker.checkTypeVarConsistency(
      bindings,
      "T",
      createTypeName("Int"),
      Optional.empty()
    );

    assertTrue(result);
    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testCheckTypeVarConsistencyMatching() {
    // T 已绑定为 Int, 再次绑定 T -> Int (一致)
    var bindings = new HashMap<String, CoreModel.Type>();
    bindings.put("T", createTypeName("Int"));

    boolean result = checker.checkTypeVarConsistency(
      bindings,
      "T",
      createTypeName("Int"),
      Optional.empty()
    );

    assertTrue(result);
    assertTrue(diagnostics.getDiagnostics().isEmpty());
  }

  @Test
  void testCheckTypeVarConsistencyConflict() {
    // T 已绑定为 Int, 再次绑定 T -> String (冲突)
    var bindings = new HashMap<String, CoreModel.Type>();
    bindings.put("T", createTypeName("Int"));

    boolean result = checker.checkTypeVarConsistency(
      bindings,
      "T",
      createTypeName("String"),
      Optional.empty()
    );

    assertFalse(result);
    assertFalse(diagnostics.getDiagnostics().isEmpty());
  }

  // ========== 泛型类型实例化测试 ==========

  @Test
  void testInstantiateGenericTypeSimple() {
    // List<T> with [Int] => List<Int>
    var list = new CoreModel.ListT();
    list.type = createTypeVar("T");

    List<CoreModel.Type> typeArgs = List.of(createTypeName("Int"));

    var result = checker.instantiateGenericType(list, typeArgs);

    assertTrue(result instanceof CoreModel.ListT);
    var resultList = (CoreModel.ListT) result;
    assertTrue(resultList.type instanceof CoreModel.TypeName);
    assertEquals("Int", ((CoreModel.TypeName) resultList.type).name);
  }

  @Test
  void testInstantiateGenericTypeFunction() {
    // (T) -> T with [Int] => (Int) -> Int
    var funcType = new CoreModel.FuncType();
    funcType.params = List.of(createTypeVar("T"));
    funcType.ret = createTypeVar("T");

    List<CoreModel.Type> typeArgs = List.of(createTypeName("Int"));

    var result = checker.instantiateGenericType(funcType, typeArgs);

    assertTrue(result instanceof CoreModel.FuncType);
    var resultFunc = (CoreModel.FuncType) result;
    assertEquals("Int", ((CoreModel.TypeName) resultFunc.params.get(0)).name);
    assertEquals("Int", ((CoreModel.TypeName) resultFunc.ret).name);
  }

  @Test
  void testInstantiateGenericTypeMultipleVars() {
    // Map<K, V> with [String, Int] => Map<String, Int>
    var map = new CoreModel.MapT();
    map.key = createTypeVar("K");
    map.val = createTypeVar("V");

    List<CoreModel.Type> typeArgs = List.of(createTypeName("String"), createTypeName("Int"));

    var result = checker.instantiateGenericType(map, typeArgs);

    assertTrue(result instanceof CoreModel.MapT);
    var resultMap = (CoreModel.MapT) result;
    assertEquals("String", ((CoreModel.TypeName) resultMap.key).name);
    assertEquals("Int", ((CoreModel.TypeName) resultMap.val).name);
  }

  @Test
  void testInstantiateGenericTypeArgCountMismatch() {
    // Map<K, V> with [Int] - 参数数量不匹配
    var map = new CoreModel.MapT();
    map.key = createTypeVar("K");
    map.val = createTypeVar("V");

    List<CoreModel.Type> typeArgs = List.of(createTypeName("Int"));

    var result = checker.instantiateGenericType(map, typeArgs);

    // 应该保持 K 和 V 未替换
    assertTrue(result instanceof CoreModel.MapT);
    var resultMap = (CoreModel.MapT) result;
    assertTrue(resultMap.key instanceof CoreModel.TypeVar);
    assertTrue(resultMap.val instanceof CoreModel.TypeVar);
  }

  // ========== 辅助方法 ==========

  private CoreModel.TypeName createTypeName(String name) {
    var type = new CoreModel.TypeName();
    type.name = name;
    return type;
  }

  private CoreModel.TypeVar createTypeVar(String name) {
    var typeVar = new CoreModel.TypeVar();
    typeVar.name = name;
    return typeVar;
  }
}
