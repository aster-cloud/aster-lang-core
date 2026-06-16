package aster.core.module;

import aster.core.ir.CoreModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ModuleGraphLinkerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void singleModuleWithoutImportsIsIdentity() {
    var rootKey = new ModuleKey("app", 1);
    var root = module("app", List.of(func("main", ret(name("x")))));
    var graph = new ModuleGraph(rootKey, Map.of(rootKey, root), List.of());

    var linked = new ModuleGraphLinker().link(graph);

    assertSame(root, linked.merged());
    assertTrue(linked.traceNames().isEmpty());
  }

  @Test
  void twoModulesRewriteDottedAliasCallAndMergeWithoutImports() {
    var rootKey = new ModuleKey("app", 1);
    var libKey = new ModuleKey("lib", 1);
    var root = module("app", List.of(importDecl("lib", 1, "L"), func("main", ret(call("L.f")))));
    var lib = module("lib", List.of(func("f", ret(intE(1)))));
    var graph = graph(rootKey, root, libKey, lib);

    var linked = new ModuleGraphLinker().link(graph);

    assertEquals("app", linked.merged().name);
    assertEquals(2, linked.merged().decls.size());
    assertEquals("lib_v1__f", ((CoreModel.Func) linked.merged().decls.get(0)).name);
    var main = (CoreModel.Func) linked.merged().decls.get(1);
    assertEquals("lib_v1__f", callTargetName(returnExpr(main)));
    assertEquals("lib.f", linked.traceNames().get("lib_v1__f"));
  }

  @Test
  void importedTopLevelConflictIsRenamedAway() {
    var rootKey = new ModuleKey("app", 1);
    var libKey = new ModuleKey("lib", 1);
    var root = module("app", List.of(func("helper", ret(intE(1)))));
    var lib = module("lib", List.of(func("helper", ret(intE(2)))));
    var graph = new ModuleGraph(
      rootKey,
      Map.of(rootKey, root, libKey, lib),
      List.of(new ModuleGraph.ImportEdge(rootKey, "L", libKey))
    );

    var linked = new ModuleGraphLinker().link(graph);

    var names = linked.merged().decls.stream()
      .map(CoreModel.Func.class::cast)
      .map(func -> func.name)
      .toList();
    assertEquals(List.of("lib_v1__helper", "helper"), names);
  }

  @Test
  void rewritesDottedAliasTypeConstructAndPatternReferences() {
    var rootKey = new ModuleKey("app", 1);
    var libKey = new ModuleKey("lib", 1);
    var user = data("User", field("name", typeName("String")));
    var make = func("make", ret(construct("L.User", fieldInit("name", stringE("Ada")))));
    make.ret = typeName("L.User");
    var match = func("matchUser", ret(intE(0)));
    match.body = block(matchStmt(name("u"), kase(patCtor("L.User", patName("x")), ret(name("x")))));
    var root = module("app", List.of(importDecl("lib", 1, "L"), make, match));
    var lib = module("lib", List.of(user));
    var graph = graph(rootKey, root, libKey, lib);

    var linked = new ModuleGraphLinker().link(graph);

    var makeOut = (CoreModel.Func) linked.merged().decls.get(1);
    assertEquals("lib_v1__User", ((CoreModel.TypeName) makeOut.ret).name);
    assertEquals("lib_v1__User", ((CoreModel.Construct) returnExpr(makeOut)).typeName);
    var matchOut = (CoreModel.Func) linked.merged().decls.get(2);
    var matchStmt = (CoreModel.Match) matchOut.body.statements.get(0);
    assertEquals("lib_v1__User", ((CoreModel.PatCtor) matchStmt.cases.get(0).pattern).typeName);
    assertEquals("x", ((CoreModel.Name) ((CoreModel.Return) matchStmt.cases.get(0).body).expr).name);
  }

  @Test
  void rewritesNestedLambdaMatchIfWorkflowAndWrapperExpressions() throws Exception {
    var rootKey = new ModuleKey("app", 1);
    var libKey = new ModuleKey("lib", 1);
    var nested = func("nested", ret(new CoreModel.NoneE()));
    nested.ret = option(typeName("L.Box"));
    nested.body = block(
      let("local", name("L.f")),
      ret(lambda(
        List.of(param("f", funcType(List.of(typeName("L.Box")), typeName("L.Box")))),
        option(typeApp("L.Box", typeName("String"))),
        block(
          ifStmt(call("L.pred"), block(ret(ok(call("L.f")))), block(ret(err(call("L.g"))))),
          matchStmt(await(some(call("L.f"))), kase(patCtor("L.Box", patName("value")), ret(name("value")))),
          start("task", call("L.f")),
          waitStmt("task"),
          workflow(block(ret(construct("L.Box", fieldInit("value", call("L.f"))))))
        )
      ))
    );
    var root = module("app", List.of(importDecl("lib", 1, "L"), nested));
    var lib = module("lib", List.of(data("Box", field("value", typeName("String"))), func("f", ret(intE(1))), func("g", ret(intE(2))), func("pred", ret(boolE(true)))));

    var linked = new ModuleGraphLinker().link(graph(rootKey, root, libKey, lib));
    var json = MAPPER.writeValueAsString(linked.merged());

    assertFalse(json.contains("L.f"));
    assertFalse(json.contains("L.g"));
    assertFalse(json.contains("L.pred"));
    assertFalse(json.contains("L.Box"));
    assertTrue(json.contains("lib_v1__f"));
    assertTrue(json.contains("lib_v1__g"));
    assertTrue(json.contains("lib_v1__pred"));
    assertTrue(json.contains("lib_v1__Box"));

    var nestedOut = (CoreModel.Func) linked.merged().decls.get(4);
    assertEquals("local", ((CoreModel.Let) nestedOut.body.statements.get(0)).name);
    var lambda = (CoreModel.Lambda) ((CoreModel.Return) nestedOut.body.statements.get(1)).expr;
    assertEquals("f", lambda.params.get(0).name);
    var matchPattern = (CoreModel.PatCtor) ((CoreModel.Match) lambda.body.statements.get(1)).cases.get(0).pattern;
    assertEquals("value", ((CoreModel.PatName) matchPattern.args.get(0)).name);
  }

  @Test
  void mangleIsCollisionFreeBetweenDotAndUnderscore() {
    // 回归 #24：朴素的 name.replace('.', '_') 会让 a.b 与 a_b 折叠到同一前缀。
    var dot = new ModuleKey("a.b", 1).mangle();
    var underscore = new ModuleKey("a_b", 1).mangle();
    assertNotEquals(dot, underscore);
    assertEquals("a_b_v1__", dot);
    assertEquals("a__b_v1__", underscore);
  }

  @Test
  void linksDotAndUnderscoreModulesToDistinctSymbols() {
    // a.b 和 a_b 各导出同名符号 f；旧 mangle 会把两者静默重写成同一名字。
    var rootKey = new ModuleKey("app", 1);
    var dotKey = new ModuleKey("a.b", 1);
    var underscoreKey = new ModuleKey("a_b", 1);
    var root = module("app", List.of(
      importDecl("a.b", 1, "D"),
      importDecl("a_b", 1, "U"),
      func("main", ret(call("D.f")))
    ));
    var dotMod = module("a.b", List.of(func("f", ret(intE(1)))));
    var underscoreMod = module("a_b", List.of(func("f", ret(intE(2)))));
    var graph = new ModuleGraph(
      rootKey,
      Map.of(rootKey, root, dotKey, dotMod, underscoreKey, underscoreMod),
      List.of(
        new ModuleGraph.ImportEdge(rootKey, "D", dotKey),
        new ModuleGraph.ImportEdge(rootKey, "U", underscoreKey)
      )
    );

    var linked = new ModuleGraphLinker().link(graph);

    var names = linked.merged().decls.stream()
      .filter(CoreModel.Func.class::isInstance)
      .map(CoreModel.Func.class::cast)
      .map(f -> f.name)
      .toList();
    assertTrue(names.contains("a_b_v1__f"), names.toString());
    assertTrue(names.contains("a__b_v1__f"), names.toString());
  }

  @Test
  void collisionGuardRejectsCollidingPrefixes() {
    // 注入一个会制造碰撞的前缀函数，验证守护断言确实 fail loud。
    var a = new ModuleKey("a.b", 1);
    var b = new ModuleKey("a_b", 1);
    var ex = assertThrows(IllegalStateException.class, () ->
      ModuleGraphLinker.assertNoManglePrefixCollisions(List.of(a, b), key -> "SAME_PREFIX_"));
    assertTrue(ex.getMessage().contains("collision"), ex.getMessage());
  }

  @Test
  void collisionGuardAcceptsDistinctPrefixes() {
    var a = new ModuleKey("a.b", 1);
    var b = new ModuleKey("a_b", 1);
    ModuleGraphLinker.assertNoManglePrefixCollisions(List.of(a, b), ModuleKey::mangle);
  }

  @Test
  void detectsImportCycles() {
    var a = new ModuleKey("a", 1);
    var b = new ModuleKey("b", 1);
    var graph = new ModuleGraph(
      a,
      Map.of(a, module("a", List.of()), b, module("b", List.of())),
      List.of(new ModuleGraph.ImportEdge(a, "B", b), new ModuleGraph.ImportEdge(b, "A", a))
    );

    assertThrows(LinkException.class, graph::topologicalOrder);
  }

  private ModuleGraph graph(ModuleKey rootKey, CoreModel.Module root, ModuleKey libKey, CoreModel.Module lib) {
    return new ModuleGraph(
      rootKey,
      Map.of(rootKey, root, libKey, lib),
      List.of(new ModuleGraph.ImportEdge(rootKey, "L", libKey))
    );
  }

  private CoreModel.Module module(String name, List<CoreModel.Decl> decls) {
    var module = new CoreModel.Module();
    module.name = name;
    module.decls = decls;
    return module;
  }

  private CoreModel.Import importDecl(String path, int version, String alias) {
    var imp = new CoreModel.Import();
    imp.path = path;
    imp.version = version;
    imp.alias = alias;
    return imp;
  }

  private CoreModel.Func func(String name, CoreModel.Stmt... statements) {
    var func = new CoreModel.Func();
    func.name = name;
    func.params = List.of();
    func.typeParams = List.of();
    func.ret = typeName("Int");
    func.effects = List.of();
    func.body = block(statements);
    return func;
  }

  private CoreModel.Data data(String name, CoreModel.Field... fields) {
    var data = new CoreModel.Data();
    data.name = name;
    data.fields = List.of(fields);
    return data;
  }

  private CoreModel.Field field(String name, CoreModel.Type type) {
    var field = new CoreModel.Field();
    field.name = name;
    field.type = type;
    return field;
  }

  private CoreModel.Param param(String name, CoreModel.Type type) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = type;
    return param;
  }

  private CoreModel.Block block(CoreModel.Stmt... statements) {
    var block = new CoreModel.Block();
    block.statements = List.of(statements);
    return block;
  }

  private CoreModel.Return ret(CoreModel.Expr expr) {
    var ret = new CoreModel.Return();
    ret.expr = expr;
    return ret;
  }

  private CoreModel.Let let(String local, CoreModel.Expr expr) {
    var let = new CoreModel.Let();
    let.name = local;
    let.expr = expr;
    return let;
  }

  private CoreModel.If ifStmt(CoreModel.Expr cond, CoreModel.Block thenBlock, CoreModel.Block elseBlock) {
    var stmt = new CoreModel.If();
    stmt.cond = cond;
    stmt.thenBlock = thenBlock;
    stmt.elseBlock = elseBlock;
    return stmt;
  }

  private CoreModel.Match matchStmt(CoreModel.Expr expr, CoreModel.Case... cases) {
    var match = new CoreModel.Match();
    match.expr = expr;
    match.cases = List.of(cases);
    return match;
  }

  private CoreModel.Case kase(CoreModel.Pattern pattern, CoreModel.Stmt body) {
    var kase = new CoreModel.Case();
    kase.pattern = pattern;
    kase.body = body;
    return kase;
  }

  private CoreModel.Start start(String task, CoreModel.Expr expr) {
    var start = new CoreModel.Start();
    start.name = task;
    start.expr = expr;
    return start;
  }

  private CoreModel.Wait waitStmt(String... tasks) {
    var wait = new CoreModel.Wait();
    wait.names = List.of(tasks);
    return wait;
  }

  private CoreModel.Workflow workflow(CoreModel.Block body) {
    var workflow = new CoreModel.Workflow();
    var step = new CoreModel.Step();
    step.name = "step";
    step.body = body;
    step.compensate = block(ret(call("L.g")));
    workflow.steps = List.of(step);
    return workflow;
  }

  private CoreModel.PatCtor patCtor(String typeName, CoreModel.Pattern... args) {
    var pat = new CoreModel.PatCtor();
    pat.typeName = typeName;
    pat.names = List.of();
    pat.args = List.of(args);
    return pat;
  }

  private CoreModel.PatName patName(String name) {
    var pat = new CoreModel.PatName();
    pat.name = name;
    return pat;
  }

  private CoreModel.Name name(String name) {
    var expr = new CoreModel.Name();
    expr.name = name;
    return expr;
  }

  private CoreModel.Call call(String target, CoreModel.Expr... args) {
    var call = new CoreModel.Call();
    call.target = name(target);
    call.args = List.of(args);
    return call;
  }

  private CoreModel.Lambda lambda(List<CoreModel.Param> params, CoreModel.Type ret, CoreModel.Block body) {
    var lambda = new CoreModel.Lambda();
    lambda.params = params;
    lambda.ret = ret;
    lambda.body = body;
    lambda.captures = List.of();
    return lambda;
  }

  private CoreModel.Await await(CoreModel.Expr expr) {
    var await = new CoreModel.Await();
    await.expr = expr;
    return await;
  }

  private CoreModel.Ok ok(CoreModel.Expr expr) {
    var ok = new CoreModel.Ok();
    ok.expr = expr;
    return ok;
  }

  private CoreModel.Err err(CoreModel.Expr expr) {
    var err = new CoreModel.Err();
    err.expr = expr;
    return err;
  }

  private CoreModel.Some some(CoreModel.Expr expr) {
    var some = new CoreModel.Some();
    some.expr = expr;
    return some;
  }

  private CoreModel.Construct construct(String typeName, CoreModel.FieldInit... fields) {
    var construct = new CoreModel.Construct();
    construct.typeName = typeName;
    construct.fields = List.of(fields);
    return construct;
  }

  private CoreModel.FieldInit fieldInit(String name, CoreModel.Expr expr) {
    var field = new CoreModel.FieldInit();
    field.name = name;
    field.expr = expr;
    return field;
  }

  private CoreModel.TypeName typeName(String name) {
    var type = new CoreModel.TypeName();
    type.name = name;
    return type;
  }

  private CoreModel.TypeApp typeApp(String base, CoreModel.Type... args) {
    var type = new CoreModel.TypeApp();
    type.base = base;
    type.args = List.of(args);
    return type;
  }

  private CoreModel.Option option(CoreModel.Type type) {
    var option = new CoreModel.Option();
    option.type = type;
    return option;
  }

  private CoreModel.FuncType funcType(List<CoreModel.Type> params, CoreModel.Type ret) {
    var type = new CoreModel.FuncType();
    type.params = params;
    type.ret = ret;
    return type;
  }

  private CoreModel.IntE intE(int value) {
    var expr = new CoreModel.IntE();
    expr.value = value;
    return expr;
  }

  private CoreModel.Bool boolE(boolean value) {
    var expr = new CoreModel.Bool();
    expr.value = value;
    return expr;
  }

  private CoreModel.StringE stringE(String value) {
    var expr = new CoreModel.StringE();
    expr.value = value;
    return expr;
  }

  private CoreModel.Expr returnExpr(CoreModel.Func func) {
    return ((CoreModel.Return) func.body.statements.get(0)).expr;
  }

  private String callTargetName(CoreModel.Expr expr) {
    return ((CoreModel.Name) ((CoreModel.Call) expr).target).name;
  }
}
