package aster.core.typecheck.pii;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PII 类型检查器，将 TypeScript 端的 @pii 传播规则移植到 Java。
 */
public final class PiiTypeChecker {

  private static final Set<String> CONSOLE_SINKS = Set.of("print", "IO.print", "IO.println", "log", "Log.info", "Log.debug", "Console.log");
  private static final Set<String> EMIT_SINKS = Set.of("emit", "workflow.emit");
  private static final List<String> NETWORK_PREFIXES = List.of("Http.", "HTTP.", "http.");
  private static final List<String> DATABASE_PREFIXES = List.of("Sql.", "SQL.", "sql.", "Db.", "DB.");

  private static final CoreModel.Origin SYNTHETIC_ORIGIN;

  static {
    var start = new CoreModel.Position();
    start.line = 0;
    start.col = 0;
    var end = new CoreModel.Position();
    end.line = 0;
    end.col = 0;
    SYNTHETIC_ORIGIN = new CoreModel.Origin();
    SYNTHETIC_ORIGIN.start = start;
    SYNTHETIC_ORIGIN.end = end;
  }

  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private final Map<String, PiiMeta> mergeCache = new HashMap<>();
  private final Map<String, AssignmentDecision> assignmentCache = new HashMap<>();

  public List<Diagnostic> checkModule(List<CoreModel.Func> funcs) {
    diagnostics.clear();
    mergeCache.clear();
    assignmentCache.clear();

    if (funcs == null) {
      return List.of();
    }

    var signatures = buildFuncSignatures(funcs);
    for (var func : funcs) {
      if (func == null || func.body == null) continue;
      var env = new HashMap<String, PiiMeta>();
      seedEnvWithParams(func, env);
      var ctx = new FunctionContext(func, signatures, extractPiiMeta(func.ret));
      traverseBlock(func.body, env, ctx);
    }

    return List.copyOf(diagnostics);
  }

  private void seedEnvWithParams(CoreModel.Func func, Map<String, PiiMeta> env) {
    if (func.params == null) return;
    for (var param : func.params) {
      var meta = extractPiiMeta(param.type);
      env.put(param.name, meta);
    }
  }

  private void seedLambdaParams(CoreModel.Lambda lambda, Map<String, PiiMeta> env) {
    if (lambda.params == null) return;
    for (var param : lambda.params) {
      env.put(param.name, extractPiiMeta(param.type));
    }
  }

  private void traverseBlock(CoreModel.Block block, Map<String, PiiMeta> env, FunctionContext ctx) {
    if (block == null || block.statements == null) {
      return;
    }

    for (var stmt : block.statements) {
      if (stmt instanceof CoreModel.Let letStmt) {
        var rhsMeta = inferExprPii(letStmt.expr, env, ctx);
        if (env.containsKey(letStmt.name)) {
          handleAssignment(env.get(letStmt.name), rhsMeta, letStmt.origin, ctx, TargetLabel.VARIABLE);
        }
        env.put(letStmt.name, cloneMeta(rhsMeta));
      } else if (stmt instanceof CoreModel.Set setStmt) {
        var rhsMeta = inferExprPii(setStmt.expr, env, ctx);
        if (env.containsKey(setStmt.name)) {
          handleAssignment(env.get(setStmt.name), rhsMeta, setStmt.origin, ctx, TargetLabel.VARIABLE);
        }
        var merged = mergePiiMeta(env.get(setStmt.name), rhsMeta);
        env.put(setStmt.name, cloneMeta(merged));
      } else if (stmt instanceof CoreModel.Return retStmt) {
        var valueMeta = inferExprPii(retStmt.expr, env, ctx);
        handleAssignment(ctx.returnMeta, valueMeta, retStmt.origin, ctx, TargetLabel.RETURN);
      } else if (stmt instanceof CoreModel.If ifStmt) {
        inferExprPii(ifStmt.cond, env, ctx);
        var thenEnv = cloneEnv(env);
        if (ifStmt.thenBlock != null) {
          traverseBlock(ifStmt.thenBlock, thenEnv, ctx);
        }
        var elseEnv = cloneEnv(env);
        if (ifStmt.elseBlock != null) {
          traverseBlock(ifStmt.elseBlock, elseEnv, ctx);
        }
        replaceEnv(env, mergeEnv(thenEnv, elseEnv));
      } else if (stmt instanceof CoreModel.Match matchStmt) {
        var matchedMeta = inferExprPii(matchStmt.expr, env, ctx);
        Map<String, PiiMeta> accumulated = null;
        if (matchStmt.cases != null) {
          for (var kase : matchStmt.cases) {
            var branchEnv = cloneEnv(env);
            bindPattern(kase.pattern, matchedMeta, branchEnv);
            if (kase.body instanceof CoreModel.Return returnBody) {
              var meta = inferExprPii(returnBody.expr, branchEnv, ctx);
              handleAssignment(ctx.returnMeta, meta, returnBody.origin, ctx, TargetLabel.RETURN);
            } else if (kase.body instanceof CoreModel.Block blockBody) {
              traverseBlock(blockBody, branchEnv, ctx);
            } else if (kase.body != null) {
              var synthetic = new CoreModel.Block();
              synthetic.statements = List.of(kase.body);
              synthetic.origin = stmtOrigin(kase.body);
              traverseBlock(synthetic, branchEnv, ctx);
            }
            accumulated = accumulated == null ? branchEnv : mergeEnv(accumulated, branchEnv);
          }
        }
        if (accumulated != null) {
          replaceEnv(env, accumulated);
        }
      } else if (stmt instanceof CoreModel.Scope scope) {
        var scopeBlock = new CoreModel.Block();
        scopeBlock.statements = scope.statements;
        scopeBlock.origin = ensureOrigin(scope.origin);
        traverseBlock(scopeBlock, env, ctx);
      } else if (stmt instanceof CoreModel.Block innerBlock) {
        traverseBlock(innerBlock, env, ctx);
      } else if (stmt instanceof CoreModel.Start start) {
        inferExprPii(start.expr, env, ctx);
      } else if (stmt instanceof CoreModel.Wait) {
        // no-op
      }
    }
  }

  private PiiMeta inferExprPii(CoreModel.Expr expr, Map<String, PiiMeta> env, FunctionContext ctx) {
    if (expr == null) {
      return null;
    }

    if (expr instanceof CoreModel.Name name) {
      return cloneMeta(env.get(name.name));
    }
    if (expr instanceof CoreModel.Bool || expr instanceof CoreModel.IntE || expr instanceof CoreModel.LongE
      || expr instanceof CoreModel.DoubleE || expr instanceof CoreModel.StringE || expr instanceof CoreModel.NullE
      || expr instanceof CoreModel.NoneE) {
      return null;
    }
    if (expr instanceof CoreModel.Call call) {
      var argMetas = new ArrayList<PiiMeta>();
      if (call.args != null) {
        for (var arg : call.args) {
          argMetas.add(inferExprPii(arg, env, ctx));
        }
      }
      var targetName = resolveCallName(call.target);
      if (targetName != null) {
        var sink = classifySink(targetName);
        if (sink != null) {
          var indices = sink.argIndices;
          if (indices == null && call.args != null) {
            indices = new int[call.args.size()];
            for (int i = 0; i < call.args.size(); i++) {
              indices[i] = i;
            }
          }
          if (indices != null && call.args != null) {
            for (int index : indices) {
              if (index < 0 || index >= call.args.size()) continue;
              var argMeta = index < argMetas.size() ? argMetas.get(index) : null;
              var argExpr = call.args.get(index);
              checkSinkAllowed(sink, argMeta, argExpr, env, ctx);
            }
          }
        }

        var signature = ctx.signatures.get(targetName);
        if (signature != null && call.args != null) {
          validateCallArgs(targetName, signature, argMetas, call.args, ctx);
          return cloneMeta(signature.ret);
        }

        var sanitized = handleSanitizer(targetName, argMetas, call);
        if (sanitized != null) {
          return sanitized;
        }
      }
      PiiMeta merged = null;
      for (var meta : argMetas) {
        merged = mergePiiMeta(merged, meta);
      }
      return merged;
    }
    if (expr instanceof CoreModel.Construct construct) {
      PiiMeta merged = null;
      if (construct.fields != null) {
        for (var field : construct.fields) {
          merged = mergePiiMeta(merged, inferExprPii(field.expr, env, ctx));
        }
      }
      return merged;
    }
    if (expr instanceof CoreModel.Ok ok) {
      return inferExprPii(ok.expr, env, ctx);
    }
    if (expr instanceof CoreModel.Err err) {
      return inferExprPii(err.expr, env, ctx);
    }
    if (expr instanceof CoreModel.Some some) {
      return inferExprPii(some.expr, env, ctx);
    }
    if (expr instanceof CoreModel.Await await) {
      return inferExprPii(await.expr, env, ctx);
    }
    if (expr instanceof CoreModel.Lambda lambda) {
      var lambdaEnv = new HashMap<String, PiiMeta>();
      seedLambdaParams(lambda, lambdaEnv);
      var lambdaCtx = new FunctionContext(ctx.func, ctx.signatures, extractPiiMeta(lambda.ret));
      traverseBlock(lambda.body, lambdaEnv, lambdaCtx);
      return null;
    }
    return null;
  }

  private void validateCallArgs(String callee, FuncSignature signature, List<PiiMeta> argMetas, List<CoreModel.Expr> argNodes, FunctionContext ctx) {
    int count = Math.min(signature.params().size(), argMetas.size());
    for (int i = 0; i < count; i++) {
      var expected = signature.params().get(i);
      var actual = argMetas.get(i);
      if (violatesAssignment(expected, actual) || (expected != null && actual == null)) {
        var data = Map.<String, Object>of(
          "expected", describeMeta(expected),
          "actual", describeMeta(actual),
          "func", callee
        );
        var origin = argNodes.get(i) != null ? exprOrigin(argNodes.get(i)) : null;
        emitDiagnostic(ErrorCode.PII_ARG_VIOLATION, origin, data, describeMeta(expected), describeMeta(actual));
      }
    }
  }

  private PiiMeta handleSanitizer(String callee, List<PiiMeta> argMetas, CoreModel.Call expr) {
    if (("redact".equals(callee) || "tokenize".equals(callee)) && !argMetas.isEmpty()) {
      var source = argMetas.get(0);
      if (source == null) {
        return null;
      }
      return new PiiMeta(PiiMeta.Level.L1, source.getCategories());
    }
    return null;
  }

  private void handleAssignment(PiiMeta targetMeta, PiiMeta valueMeta, CoreModel.Origin origin, FunctionContext ctx, TargetLabel label) {
    var decision = evaluateAssignment(targetMeta, valueMeta);
    if (decision.violation) {
      var data = Map.<String, Object>of(
        "source", describeMeta(valueMeta),
        "target", describeMeta(targetMeta)
      );
      emitDiagnostic(ErrorCode.PII_ASSIGN_DOWNGRADE, origin, data, describeMeta(valueMeta), describeMeta(targetMeta));
    } else if (decision.warning) {
      var data = Map.<String, Object>of(
        "source", describeMeta(valueMeta),
        "target", describeMeta(targetMeta)
      );
      emitDiagnostic(ErrorCode.PII_IMPLICIT_UPLEVEL, origin, data, describeMeta(valueMeta), describeMeta(targetMeta));
    }
  }

  private AssignmentDecision evaluateAssignment(PiiMeta targetMeta, PiiMeta valueMeta) {
    var key = metaKey(targetMeta, true) + "->" + metaKey(valueMeta, false);
    var cached = assignmentCache.get(key);
    if (cached != null) {
      return cached;
    }

    var decision = new AssignmentDecision();
    if (targetMeta == null && valueMeta != null) {
      decision.violation = true;
    } else if (targetMeta != null && valueMeta == null) {
      decision.warning = true;
    } else if (targetMeta != null) {
      var normalizedTarget = normalizeCategories(targetMeta.getCategories());
      var normalizedValue = normalizeCategories(valueMeta.getCategories());
      if (!normalizedTarget.equals(normalizedValue)) {
        decision.violation = true;
      } else {
        var lhsRank = targetMeta.getLevel().getOrder();
        var rhsRank = valueMeta.getLevel().getOrder();
        if (rhsRank > lhsRank) {
          decision.violation = true;
        } else if (rhsRank < lhsRank) {
          decision.warning = true;
        }
      }
    }

    assignmentCache.put(key, decision);
    return decision;
  }

  private boolean violatesAssignment(PiiMeta lhs, PiiMeta rhs) {
    return evaluateAssignment(lhs, rhs).violation;
  }

  private void checkSinkAllowed(SinkDescriptor sink, PiiMeta meta, CoreModel.Expr argExpr, Map<String, PiiMeta> env, FunctionContext ctx) {
    if (meta == null) {
      if (argExpr instanceof CoreModel.Name name && !env.containsKey(name.name)) {
        emitDiagnostic(ErrorCode.PII_SINK_UNKNOWN, exprOrigin(argExpr), Map.<String, Object>of("sinkKind", sink.label), sink.label);
      }
      return;
    }
    if (meta.getLevel() == PiiMeta.Level.L3) {
      emitDiagnostic(ErrorCode.PII_SINK_UNSANITIZED, exprOrigin(argExpr), Map.<String, Object>of(
        "level", meta.getLevel().name(),
        "sinkKind", sink.label
      ), meta.getLevel().name(), sink.label);
      return;
    }
    if (sink.kind == SinkKind.CONSOLE && meta.getLevel() == PiiMeta.Level.L2) {
      emitDiagnostic(ErrorCode.PII_SINK_UNSANITIZED, exprOrigin(argExpr), Map.<String, Object>of(
        "level", meta.getLevel().name(),
        "sinkKind", sink.label
      ), meta.getLevel().name(), sink.label);
    }
  }

  private PiiMeta mergePiiMeta(PiiMeta left, PiiMeta right) {
    if (left == null) return right;
    if (right == null) return left;
    var key = metaKey(left, false) + "|" + metaKey(right, false);
    var cached = mergeCache.get(key);
    if (cached != null) {
      return cached;
    }
    var merged = PiiMeta.merge(left, right);
    mergeCache.put(key, merged);
    return merged;
  }

  private void bindPattern(CoreModel.Pattern pattern, PiiMeta meta, Map<String, PiiMeta> env) {
    if (pattern instanceof CoreModel.PatName patName) {
      env.put(patName.name, cloneMeta(meta));
    } else if (pattern instanceof CoreModel.PatCtor patCtor) {
      if (patCtor.names != null) {
        for (var name : patCtor.names) {
          env.put(name, cloneMeta(meta));
        }
      }
      if (patCtor.args != null) {
        for (var arg : patCtor.args) {
          bindPattern(arg, meta, env);
        }
      }
    }
  }

  private SinkDescriptor classifySink(String targetName) {
    if (CONSOLE_SINKS.contains(targetName)) {
      return new SinkDescriptor(targetName, SinkKind.CONSOLE, null);
    }
    if (EMIT_SINKS.contains(targetName)) {
      return new SinkDescriptor(targetName, SinkKind.EMIT, null);
    }
    for (var prefix : NETWORK_PREFIXES) {
      if (targetName.startsWith(prefix)) {
        int[] indices = targetName.startsWith("Http.") ? new int[]{1} : null;
        return new SinkDescriptor(targetName, SinkKind.NETWORK, indices);
      }
    }
    for (var prefix : DATABASE_PREFIXES) {
      if (targetName.startsWith(prefix)) {
        return new SinkDescriptor(targetName, SinkKind.DATABASE, null);
      }
    }
    return null;
  }

  private String resolveCallName(CoreModel.Expr target) {
    if (target instanceof CoreModel.Name name) {
      return name.name;
    }
    return null;
  }

  private Map<String, FuncSignature> buildFuncSignatures(List<CoreModel.Func> funcs) {
    var map = new HashMap<String, FuncSignature>();
    for (var func : funcs) {
      if (func == null) continue;
      List<PiiMeta> params = Collections.emptyList();
      if (func.params != null) {
        params = func.params.stream()
          .map(param -> extractPiiMeta(param.type))
          .toList();
      }
      map.put(func.name, new FuncSignature(params, extractPiiMeta(func.ret)));
    }
    return map;
  }

  private Map<String, PiiMeta> cloneEnv(Map<String, PiiMeta> env) {
    var clone = new HashMap<String, PiiMeta>();
    for (var entry : env.entrySet()) {
      clone.put(entry.getKey(), cloneMeta(entry.getValue()));
    }
    return clone;
  }

  private Map<String, PiiMeta> mergeEnv(Map<String, PiiMeta> left, Map<String, PiiMeta> right) {
    var merged = new HashMap<String, PiiMeta>();
    var keys = new HashSet<>(left.keySet());
    keys.addAll(right.keySet());
    for (var key : keys) {
      merged.put(key, cloneMeta(mergePiiMeta(left.get(key), right.get(key))));
    }
    return merged;
  }

  private void replaceEnv(Map<String, PiiMeta> target, Map<String, PiiMeta> source) {
    target.clear();
    target.putAll(source);
  }

  private PiiMeta cloneMeta(PiiMeta meta) {
    return meta;
  }

  private PiiMeta extractPiiMeta(CoreModel.Type type) {
    if (type instanceof CoreModel.PiiType piiType) {
      var baseMeta = extractPiiMeta(piiType.baseType);
      var categories = new HashSet<String>();
      if (piiType.category != null) {
        categories.add(piiType.category);
      } else if (baseMeta != null) {
        categories.addAll(baseMeta.getCategories());
      }
      var level = parseLevel(piiType.sensitivity);
      return new PiiMeta(level, categories);
    }
    return null;
  }

  private PiiMeta.Level parseLevel(String sensitivity) {
    if (sensitivity == null) {
      return PiiMeta.Level.L1;
    }
    try {
      return PiiMeta.Level.valueOf(sensitivity);
    } catch (IllegalArgumentException ex) {
      return PiiMeta.Level.L1;
    }
  }

  private String metaKey(PiiMeta meta, boolean allowUnset) {
    if (meta == null) {
      return allowUnset ? "unset" : "plain";
    }
    return meta.getLevel().name() + "[" + String.join("|", normalizeCategories(meta.getCategories())) + "]";
  }

  private List<String> normalizeCategories(Set<String> categories) {
    if (categories == null || categories.isEmpty()) {
      return List.of();
    }
    return categories.stream()
      .filter(Objects::nonNull)
      .sorted()
      .collect(Collectors.toList());
  }

  private String describeMeta(PiiMeta meta) {
    if (meta == null) {
      return "Plain";
    }
    var cats = normalizeCategories(meta.getCategories());
    if (cats.isEmpty()) {
      return meta.getLevel().name();
    }
    return meta.getLevel().name() + "[" + String.join(", ", cats) + "]";
  }

  private CoreModel.Origin ensureOrigin(CoreModel.Origin origin) {
    return origin != null ? origin : SYNTHETIC_ORIGIN;
  }

  private CoreModel.Origin stmtOrigin(CoreModel.Stmt stmt) {
    if (stmt == null) {
      return null;
    }
    if (stmt instanceof CoreModel.Let let) return let.origin;
    if (stmt instanceof CoreModel.Set set) return set.origin;
    if (stmt instanceof CoreModel.Return ret) return ret.origin;
    if (stmt instanceof CoreModel.If ifStmt) return ifStmt.origin;
    if (stmt instanceof CoreModel.Match match) return match.origin;
    if (stmt instanceof CoreModel.Scope scope) return scope.origin;
    if (stmt instanceof CoreModel.Block block) return block.origin;
    if (stmt instanceof CoreModel.Start start) return start.origin;
    if (stmt instanceof CoreModel.Wait wait) return wait.origin;
    return null;
  }

  private CoreModel.Origin exprOrigin(CoreModel.Expr expr) {
    if (expr == null) {
      return null;
    }
    if (expr instanceof CoreModel.Name name) return name.origin;
    if (expr instanceof CoreModel.Bool bool) return bool.origin;
    if (expr instanceof CoreModel.IntE i) return i.origin;
    if (expr instanceof CoreModel.LongE l) return l.origin;
    if (expr instanceof CoreModel.DoubleE d) return d.origin;
    if (expr instanceof CoreModel.StringE s) return s.origin;
    if (expr instanceof CoreModel.NullE n) return n.origin;
    if (expr instanceof CoreModel.Ok ok) return ok.origin;
    if (expr instanceof CoreModel.Err err) return err.origin;
    if (expr instanceof CoreModel.Some some) return some.origin;
    if (expr instanceof CoreModel.NoneE none) return none.origin;
    if (expr instanceof CoreModel.Construct construct) return construct.origin;
    if (expr instanceof CoreModel.Call call) return call.origin;
    if (expr instanceof CoreModel.Lambda lambda) return lambda.origin;
    if (expr instanceof CoreModel.Await await) return await.origin;
    return null;
  }

  private void emitDiagnostic(ErrorCode code, CoreModel.Origin origin, Map<String, Object> data, Object... args) {
    var severity = switch (code.severity()) {
      case ERROR -> Diagnostic.Severity.ERROR;
      case WARNING -> Diagnostic.Severity.WARNING;
      case INFO -> Diagnostic.Severity.INFO;
    };
    var message = args == null || args.length == 0
      ? code.messageTemplate()
      : String.format(Locale.ROOT, code.messageTemplate(), args);
    var diagnostic = new Diagnostic(
      severity,
      code,
      message,
      Optional.ofNullable(origin),
      Optional.ofNullable(code.help()),
      data == null ? Map.of() : data
    );
    diagnostics.add(diagnostic);
  }

  private enum SinkKind {
    CONSOLE,
    NETWORK,
    DATABASE,
    EMIT
  }

  private enum TargetLabel {
    VARIABLE,
    RETURN
  }

  private static final class SinkDescriptor {
    final String label;
    final SinkKind kind;
    final int[] argIndices;

    SinkDescriptor(String label, SinkKind kind, int[] argIndices) {
      this.label = label;
      this.kind = kind;
      this.argIndices = argIndices;
    }
  }

  private static final class AssignmentDecision {
    boolean violation;
    boolean warning;
  }

  private record FuncSignature(List<PiiMeta> params, PiiMeta ret) {}

  private record FunctionContext(
    CoreModel.Func func,
    Map<String, FuncSignature> signatures,
    PiiMeta returnMeta
  ) {}
}
