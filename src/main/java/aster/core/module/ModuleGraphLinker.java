package aster.core.module;

import aster.core.ir.CoreModel;
import java.util.*;

/**
 * 将已解析 module graph 合并成单 Core module，并重写跨模块顶层符号引用。
 */
public final class ModuleGraphLinker {

  public LinkedProgram link(ModuleGraph graph) {
    Objects.requireNonNull(graph, "graph");
    if (graph.modules().size() == 1 && graph.imports().isEmpty()) {
      return new LinkedProgram(graph.modules().get(graph.root()), Map.of());
    }

    var topLevelNames = collectTopLevelNames(graph.modules());
    var renameMaps = buildRenameMaps(graph.root(), topLevelNames);
    var traceNames = buildTraceNames(renameMaps);

    for (var key : graph.topologicalOrder()) {
      var module = graph.modules().get(key);
      var rewriter = new SymbolRewriter(
        key,
        graph.importsFrom(key),
        topLevelNames,
        renameMaps.getOrDefault(key, Map.of())
      );
      rewriter.rewriteModule(module);
    }

    var merged = new CoreModel.Module();
    merged.name = graph.modules().get(graph.root()).name;
    merged.origin = graph.modules().get(graph.root()).origin;
    merged.decls = new ArrayList<>();
    for (var key : graph.topologicalOrder()) {
      var module = graph.modules().get(key);
      if (module.decls == null) {
        continue;
      }
      for (var decl : module.decls) {
        if (!(decl instanceof CoreModel.Import)) {
          merged.decls.add(decl);
        }
      }
    }

    return new LinkedProgram(merged, Map.copyOf(traceNames));
  }

  private Map<ModuleKey, Set<String>> collectTopLevelNames(Map<ModuleKey, CoreModel.Module> modules) {
    var result = new HashMap<ModuleKey, Set<String>>();
    for (var entry : modules.entrySet()) {
      var names = new HashSet<String>();
      var decls = entry.getValue().decls;
      if (decls != null) {
        for (var decl : decls) {
          switch (decl) {
            case CoreModel.Func func -> names.add(func.name);
            case CoreModel.Data data -> names.add(data.name);
            case CoreModel.Enum enumDecl -> names.add(enumDecl.name);
            case CoreModel.Import ignored -> {
            }
          }
        }
      }
      names.remove(null);
      result.put(entry.getKey(), Set.copyOf(names));
    }
    return Map.copyOf(result);
  }

  private Map<ModuleKey, Map<String, String>> buildRenameMaps(
    ModuleKey root,
    Map<ModuleKey, Set<String>> topLevelNames
  ) {
    var result = new HashMap<ModuleKey, Map<String, String>>();
    // 安全：不同模块绝不能产生相同的 mangle 前缀，否则跨模块符号会被静默混淆。
    // 一旦碰撞，立刻 fail loud（而非生成错误的合并程序）。
    assertNoManglePrefixCollisions(topLevelNames.keySet(), ModuleKey::mangle);
    for (var entry : topLevelNames.entrySet()) {
      var key = entry.getKey();
      if (key.equals(root)) {
        result.put(key, Map.of());
        continue;
      }
      var rename = new HashMap<String, String>();
      for (var name : entry.getValue()) {
        rename.put(name, key.mangle() + name);
      }
      result.put(key, Map.copyOf(rename));
    }
    return Map.copyOf(result);
  }

  /**
   * 防御性断言：所有模块的 mangle 前缀必须两两不同。
   * <p>
   * {@link ModuleKey#mangle()} 已是无碰撞编码；本断言守护未来对 mangle 的回归
   * （以及任何理论残余歧义），碰撞时 fail loud 而非生成错误的合并程序。
   * 前缀函数可注入以便测试。
   *
   * @param keys     待检查的模块 key
   * @param prefixFn 计算前缀的函数（生产代码用 {@link ModuleKey#mangle()}）
   */
  static void assertNoManglePrefixCollisions(
    Collection<ModuleKey> keys,
    java.util.function.Function<ModuleKey, String> prefixFn
  ) {
    var prefixOwners = new HashMap<String, ModuleKey>();
    for (var key : keys) {
      var prefix = prefixFn.apply(key);
      var existing = prefixOwners.putIfAbsent(prefix, key);
      if (existing != null && !existing.equals(key)) {
        throw new IllegalStateException(
          "Module mangle prefix collision: '" + existing + "' and '" + key
            + "' both produce prefix '" + prefix + "'");
      }
    }
  }

  private Map<String, String> buildTraceNames(Map<ModuleKey, Map<String, String>> renameMaps) {
    var trace = new LinkedHashMap<String, String>();
    for (var entry : renameMaps.entrySet()) {
      for (var rename : entry.getValue().entrySet()) {
        trace.put(rename.getValue(), entry.getKey().moduleName() + "." + rename.getKey());
      }
    }
    return trace;
  }

  private static final class SymbolRewriter {
    private final ModuleKey currentKey;
    private final Map<String, ModuleKey> importsByAlias;
    private final Map<ModuleKey, Set<String>> topLevelNames;
    private final Map<String, String> currentRenames;
    private final Deque<Set<String>> localScopes = new ArrayDeque<>();

    SymbolRewriter(
      ModuleKey currentKey,
      List<ModuleGraph.ImportEdge> imports,
      Map<ModuleKey, Set<String>> topLevelNames,
      Map<String, String> currentRenames
    ) {
      this.currentKey = currentKey;
      this.topLevelNames = topLevelNames;
      this.currentRenames = currentRenames;
      var aliases = new HashMap<String, ModuleKey>();
      for (var edge : imports) {
        aliases.put(edge.importAlias(), edge.toKey());
      }
      this.importsByAlias = Map.copyOf(aliases);
    }

    void rewriteModule(CoreModel.Module module) {
      if (module == null || module.decls == null) {
        return;
      }
      for (var decl : module.decls) {
        rewriteDecl(decl);
      }
    }

    private void rewriteDecl(CoreModel.Decl decl) {
      switch (decl) {
        case CoreModel.Func func -> {
          func.name = rewriteCurrentTopLevelName(func.name);
          withScope(paramNames(func.params), () -> {
            rewriteParams(func.params);
            rewriteType(func.ret);
            rewriteBlock(func.body);
          });
        }
        case CoreModel.Data data -> {
          data.name = rewriteCurrentTopLevelName(data.name);
          if (data.fields != null) {
            for (var field : data.fields) {
              rewriteType(field.type);
            }
          }
        }
        case CoreModel.Enum enumDecl -> enumDecl.name = rewriteCurrentTopLevelName(enumDecl.name);
        case CoreModel.Import ignored -> {
        }
      }
    }

    private void rewriteParams(List<CoreModel.Param> params) {
      if (params == null) {
        return;
      }
      for (var param : params) {
        rewriteType(param.type);
      }
    }

    private void rewriteType(CoreModel.Type type) {
      if (type == null) {
        return;
      }
      switch (type) {
        case CoreModel.TypeName typeName -> typeName.name = rewriteSymbolName(typeName.name, false);
        case CoreModel.TypeVar ignored -> {
        }
        case CoreModel.TypeApp typeApp -> {
          typeApp.base = rewriteSymbolName(typeApp.base, false);
          rewriteTypes(typeApp.args);
        }
        case CoreModel.Result result -> {
          rewriteType(result.ok);
          rewriteType(result.err);
        }
        case CoreModel.Maybe maybe -> rewriteType(maybe.type);
        case CoreModel.Option option -> rewriteType(option.type);
        case CoreModel.ListT list -> rewriteType(list.type);
        case CoreModel.MapT map -> {
          rewriteType(map.key);
          rewriteType(map.val);
        }
        case CoreModel.FuncType funcType -> {
          rewriteTypes(funcType.params);
          rewriteType(funcType.ret);
        }
        case CoreModel.PiiType pii -> rewriteType(pii.baseType);
      }
    }

    private void rewriteTypes(List<CoreModel.Type> types) {
      if (types == null) {
        return;
      }
      for (var type : types) {
        rewriteType(type);
      }
    }

    private void rewriteStmt(CoreModel.Stmt stmt) {
      if (stmt == null) {
        return;
      }
      switch (stmt) {
        case CoreModel.Let let -> {
          rewriteExpr(let.expr);
          addLocal(let.name);
        }
        case CoreModel.Set set -> rewriteExpr(set.expr);
        case CoreModel.Return ret -> rewriteExpr(ret.expr);
        case CoreModel.If ifStmt -> {
          rewriteExpr(ifStmt.cond);
          rewriteBlock(ifStmt.thenBlock);
          rewriteBlock(ifStmt.elseBlock);
        }
        case CoreModel.Match match -> {
          rewriteExpr(match.expr);
          if (match.cases != null) {
            for (var kase : match.cases) {
              rewriteCase(kase);
            }
          }
        }
        case CoreModel.Scope scope -> withScope(Set.of(), () -> rewriteStatements(scope.statements));
        case CoreModel.Block block -> rewriteBlock(block);
        case CoreModel.Start start -> rewriteExpr(start.expr);
        case CoreModel.Wait ignored -> {
          // wait names 是本地任务名，不参与顶层符号重命名。
        }
        case CoreModel.Workflow workflow -> {
          if (workflow.steps != null) {
            for (var step : workflow.steps) {
              rewriteBlock(step.body);
              rewriteBlock(step.compensate);
            }
          }
        }
      }
    }

    private void rewriteCase(CoreModel.Case kase) {
      if (kase == null) {
        return;
      }
      var binders = new HashSet<String>();
      rewritePattern(kase.pattern, binders);
      withScope(binders, () -> rewriteStmt(kase.body));
    }

    private void rewritePattern(CoreModel.Pattern pattern, Set<String> binders) {
      if (pattern == null) {
        return;
      }
      switch (pattern) {
        case CoreModel.PatNull ignored -> {
        }
        case CoreModel.PatCtor ctor -> {
          ctor.typeName = rewriteSymbolName(ctor.typeName, false);
          if (ctor.args != null) {
            for (var arg : ctor.args) {
              rewritePattern(arg, binders);
            }
          }
          if (ctor.names != null) {
            binders.addAll(ctor.names);
          }
        }
        case CoreModel.PatName name -> {
          if (name.name != null) {
            binders.add(name.name);
          }
        }
        case CoreModel.PatInt ignored -> {
        }
      }
    }

    private void rewriteBlock(CoreModel.Block block) {
      if (block == null) {
        return;
      }
      withScope(Set.of(), () -> rewriteStatements(block.statements));
    }

    private void rewriteStatements(List<CoreModel.Stmt> statements) {
      if (statements == null) {
        return;
      }
      for (var stmt : statements) {
        rewriteStmt(stmt);
      }
    }

    private void rewriteExpr(CoreModel.Expr expr) {
      if (expr == null) {
        return;
      }
      switch (expr) {
        case CoreModel.Name name -> {
          if (!isLocal(name.name)) {
            name.name = rewriteSymbolName(name.name, true);
          }
        }
        case CoreModel.Bool ignored -> {
        }
        case CoreModel.IntE ignored -> {
        }
        case CoreModel.LongE ignored -> {
        }
        case CoreModel.DoubleE ignored -> {
        }
        case CoreModel.StringE ignored -> {
        }
        case CoreModel.NullE ignored -> {
        }
        case CoreModel.Ok ok -> rewriteExpr(ok.expr);
        case CoreModel.Err err -> rewriteExpr(err.expr);
        case CoreModel.Some some -> rewriteExpr(some.expr);
        case CoreModel.NoneE ignored -> {
        }
        case CoreModel.Construct construct -> {
          construct.typeName = rewriteSymbolName(construct.typeName, false);
          if (construct.fields != null) {
            for (var field : construct.fields) {
              rewriteExpr(field.expr);
            }
          }
        }
        case CoreModel.Call call -> {
          rewriteExpr(call.target);
          if (call.args != null) {
            for (var arg : call.args) {
              rewriteExpr(arg);
            }
          }
        }
        case CoreModel.Lambda lambda -> withScope(paramNames(lambda.params), () -> {
          rewriteParams(lambda.params);
          rewriteType(lambda.ret);
          rewriteBlock(lambda.body);
          // captures 是局部闭包变量名，不参与顶层符号重命名。
        });
        case CoreModel.Await await -> rewriteExpr(await.expr);
      }
    }

    private String rewriteSymbolName(String name, boolean allowUnqualifiedImport) {
      if (name == null) {
        return null;
      }
      var dot = name.indexOf('.');
      if (dot > 0 && dot < name.length() - 1) {
        var alias = name.substring(0, dot);
        var symbol = name.substring(dot + 1);
        var toKey = importsByAlias.get(alias);
        if (toKey != null) {
          return toKey.mangle() + symbol;
        }
      }

      var localRename = currentRenames.get(name);
      if (localRename != null) {
        return localRename;
      }

      if (allowUnqualifiedImport) {
        var imported = findImportedTopLevel(name);
        if (imported != null) {
          return imported.mangle() + name;
        }
      }

      return name;
    }

    private ModuleKey findImportedTopLevel(String name) {
      if (topLevelNames.getOrDefault(currentKey, Set.of()).contains(name)) {
        return null;
      }
      ModuleKey found = null;
      for (var toKey : importsByAlias.values()) {
        if (topLevelNames.getOrDefault(toKey, Set.of()).contains(name)) {
          if (found != null && !found.equals(toKey)) {
            return null;
          }
          found = toKey;
        }
      }
      return found;
    }

    private String rewriteCurrentTopLevelName(String name) {
      return currentRenames.getOrDefault(name, name);
    }

    private Set<String> paramNames(List<CoreModel.Param> params) {
      if (params == null || params.isEmpty()) {
        return Set.of();
      }
      var names = new HashSet<String>();
      for (var param : params) {
        if (param.name != null) {
          names.add(param.name);
        }
      }
      return names;
    }

    private void withScope(Set<String> initialNames, Runnable action) {
      localScopes.push(new HashSet<>(initialNames));
      try {
        action.run();
      } finally {
        localScopes.pop();
      }
    }

    private void addLocal(String name) {
      if (name != null && !localScopes.isEmpty()) {
        localScopes.peek().add(name);
      }
    }

    private boolean isLocal(String name) {
      if (name == null) {
        return false;
      }
      for (var scope : localScopes) {
        if (scope.contains(name)) {
          return true;
        }
      }
      return false;
    }
  }
}
