package aster.core.lowering;

import aster.core.ast.*;
import aster.core.ir.CoreModel;
import aster.core.typecheck.CapabilityKind;
import aster.core.typecheck.checkers.CapabilityChecker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Core IR 降级器
 * <p>
 * 负责将高层 AST 节点转换为 Core IR 结构，同时保留源码位置信息。
 */
public final class CoreLowering {

  /**
   * 将 AST 模块降级为 Core IR 模块。
   *
   * @param ast 模块 AST 节点
   * @return Core IR 模块节点
   */
  public CoreModel.Module lowerModule(aster.core.ast.Module ast) {
    Objects.requireNonNull(ast, "ast");
    CoreModel.Module module = new CoreModel.Module();
    module.name = ast.name();
    module.decls = new ArrayList<>();
    if (ast.decls() != null) {
      for (Decl decl : ast.decls()) {
        CoreModel.Decl lowered = lowerDeclaration(decl);
        if (lowered != null) {
          module.decls.add(lowered);
        }
      }
    }
    module.origin = spanToOrigin(ast.span());
    return module;
  }

  /**
   * 降级单个声明节点。
   *
   * @param decl AST 声明
   * @return Core IR 声明，TypeAlias 返回 null 表示跳过
   */
  private CoreModel.Decl lowerDeclaration(Decl decl) {
    if (decl == null) {
      return null;
    }
    return switch (decl) {
      case Decl.Import imp -> lowerImport(imp);
      case Decl.Data data -> lowerData(data);
      case Decl.Enum en -> lowerEnum(en);
      case Decl.Func func -> lowerFunc(func);
      case Decl.TypeAlias ignored -> null; // Core IR 暂不支持类型别名，静默跳过
    };
  }

  private CoreModel.Import lowerImport(Decl.Import imp) {
    CoreModel.Import out = new CoreModel.Import();
    out.path = imp.path();
    out.alias = imp.alias();
    out.origin = spanToOrigin(imp.span());
    return out;
  }

  private CoreModel.Data lowerData(Decl.Data data) {
    CoreModel.Data out = new CoreModel.Data();
    out.name = data.name();
    out.fields = new ArrayList<>();
    if (data.fields() != null) {
      for (Decl.Field field : data.fields()) {
        out.fields.add(lowerField(field));
      }
    }
    out.origin = spanToOrigin(data.span());
    return out;
  }

  private CoreModel.Field lowerField(Decl.Field field) {
    CoreModel.Field out = new CoreModel.Field();
    out.name = field.name();
    out.type = lowerType(field.type());
    out.annotations = lowerAnnotations(field.annotations());
    return out;
  }

  private CoreModel.Enum lowerEnum(Decl.Enum en) {
    CoreModel.Enum out = new CoreModel.Enum();
    out.name = en.name();
    out.variants = en.variants() == null ? new ArrayList<>() : new ArrayList<>(en.variants());
    out.origin = spanToOrigin(en.span());
    return out;
  }

  private CoreModel.Func lowerFunc(Decl.Func func) {
    CoreModel.Func out = new CoreModel.Func();
    out.name = func.name();
    out.typeParams = func.typeParams() == null ? new ArrayList<>() : new ArrayList<>(func.typeParams());
    out.params = new ArrayList<>();
    if (func.params() != null) {
      for (Decl.Parameter param : func.params()) {
        out.params.add(lowerParam(param));
      }
    }
    out.ret = func.retType() == null ? null : lowerType(func.retType());
    out.effects = func.effects() == null ? new ArrayList<>() : new ArrayList<>(func.effects());
    out.effectCaps = func.effectCaps() == null ? new ArrayList<>() : new ArrayList<>(func.effectCaps());
    out.effectCapsExplicit = func.effectCapsExplicit();
    out.body = func.body() == null ? emptyBlock(func.span()) : lowerBlock(func.body());
    out.origin = spanToOrigin(func.span());
    annotateFuncPii(out);
    return out;
  }

  private CoreModel.Param lowerParam(Decl.Parameter param) {
    CoreModel.Param out = new CoreModel.Param();
    out.name = param.name();
    out.type = param.type() == null ? null : lowerType(param.type());
    out.annotations = lowerAnnotations(param.annotations());
    return out;
  }

  private CoreModel.Block lowerBlock(Block block) {
    CoreModel.Block out = new CoreModel.Block();
    out.statements = new ArrayList<>();
    if (block.statements() != null) {
      for (Stmt stmt : block.statements()) {
        out.statements.add(lowerStmt(stmt));
      }
    }
    out.origin = spanToOrigin(block.span());
    return out;
  }

  private CoreModel.Stmt lowerStmt(Stmt stmt) {
    Objects.requireNonNull(stmt, "stmt");
    return switch (stmt) {
      case Stmt.Let let -> {
        CoreModel.Let out = new CoreModel.Let();
        out.name = let.name();
        out.expr = lowerExpr(let.expr());
        out.origin = spanToOrigin(let.span());
        yield out;
      }
      case Stmt.Set set -> {
        CoreModel.Set out = new CoreModel.Set();
        out.name = set.name();
        out.expr = lowerExpr(set.expr());
        out.origin = spanToOrigin(set.span());
        yield out;
      }
      case Stmt.Return ret -> {
        CoreModel.Return out = new CoreModel.Return();
        out.expr = lowerExpr(ret.expr());
        out.origin = spanToOrigin(ret.span());
        yield out;
      }
      case Stmt.If ifStmt -> {
        CoreModel.If out = new CoreModel.If();
        out.cond = lowerExpr(ifStmt.cond());
        out.thenBlock = lowerBlock(ifStmt.thenBlock());
        out.elseBlock = ifStmt.elseBlock() == null ? null : lowerBlock(ifStmt.elseBlock());
        out.origin = spanToOrigin(ifStmt.span());
        yield out;
      }
      case Stmt.Match match -> {
        CoreModel.Match out = new CoreModel.Match();
        out.expr = lowerExpr(match.expr());
        out.cases = new ArrayList<>();
        if (match.cases() != null) {
          for (Stmt.Case kase : match.cases()) {
            out.cases.add(lowerCase(kase));
          }
        }
        out.origin = spanToOrigin(match.span());
        yield out;
      }
      case Stmt.Start start -> {
        CoreModel.Start out = new CoreModel.Start();
        out.name = start.name();
        out.expr = lowerExpr(start.expr());
        out.origin = spanToOrigin(start.span());
        yield out;
      }
      case Stmt.Wait wait -> {
        CoreModel.Wait out = new CoreModel.Wait();
        out.names = wait.names() == null ? new ArrayList<>() : new ArrayList<>(wait.names());
        out.origin = spanToOrigin(wait.span());
        yield out;
      }
      case Stmt.Workflow workflow -> lowerWorkflow(workflow);
      case Block block -> {
        CoreModel.Scope out = new CoreModel.Scope();
        out.statements = new ArrayList<>();
        if (block.statements() != null) {
          for (Stmt inner : block.statements()) {
            out.statements.add(lowerStmt(inner));
          }
        }
        out.origin = spanToOrigin(block.span());
        yield out;
      }
    };
  }

  private CoreModel.Workflow lowerWorkflow(Stmt.Workflow workflow) {
    CoreModel.Workflow out = new CoreModel.Workflow();
    out.steps = new ArrayList<>();
    var capabilityChecker = new CapabilityChecker();
    String previousStep = null;
    if (workflow.steps() != null) {
      for (Stmt.WorkflowStep step : workflow.steps()) {
        CoreModel.Step lowered = lowerWorkflowStep(step, previousStep, capabilityChecker);
        out.steps.add(lowered);
        previousStep = step.name();
      }
    }
    out.effectCaps = mergeWorkflowCapabilities(out.steps);
    if (workflow.retry() != null) {
      CoreModel.RetryPolicy retry = new CoreModel.RetryPolicy();
      retry.maxAttempts = workflow.retry().maxAttempts();
      retry.backoff = workflow.retry().backoff();
      out.retry = retry;
    }
    if (workflow.timeout() != null) {
      CoreModel.Timeout timeout = new CoreModel.Timeout();
      timeout.milliseconds = workflow.timeout().milliseconds();
      out.timeout = timeout;
    }
    out.origin = spanToOrigin(workflow.span());
    return out;
  }

  private CoreModel.Step lowerWorkflowStep(
      Stmt.WorkflowStep step,
      String previousStep,
      CapabilityChecker capabilityChecker
  ) {
    CoreModel.Step lowered = new CoreModel.Step();
    lowered.name = step.name();
    lowered.body = step.body() == null ? emptyBlock(step.span()) : lowerBlock(step.body());
    lowered.compensate = step.compensate() == null ? null : lowerBlock(step.compensate());
    if (step.dependencies() != null && !step.dependencies().isEmpty()) {
      lowered.dependencies = new ArrayList<>(step.dependencies());
    } else if (previousStep != null) {
      lowered.dependencies = new ArrayList<>(List.of(previousStep));
    } else {
      lowered.dependencies = new ArrayList<>();
    }
    lowered.effectCaps = collectStepCapabilities(capabilityChecker, lowered.body, lowered.compensate);
    lowered.origin = spanToOrigin(step.span());
    return lowered;
  }

  private List<String> collectStepCapabilities(
      CapabilityChecker capabilityChecker,
      CoreModel.Block body,
      CoreModel.Block compensate
  ) {
    var merged = new LinkedHashSet<String>();
    mergeCapabilityMap(capabilityChecker.collectCapabilities(body), merged);
    if (compensate != null) {
      mergeCapabilityMap(capabilityChecker.collectCapabilities(compensate), merged);
    }
    return new ArrayList<>(merged);
  }

  private void mergeCapabilityMap(Map<CapabilityKind, List<String>> source, LinkedHashSet<String> accumulator) {
    if (source == null || source.isEmpty()) {
      return;
    }
    for (var entry : source.keySet()) {
      accumulator.add(entry.displayName());
    }
  }

  private List<String> mergeWorkflowCapabilities(List<CoreModel.Step> steps) {
    if (steps == null || steps.isEmpty()) {
      return List.of();
    }
    var merged = new LinkedHashSet<String>();
    for (var step : steps) {
      if (step.effectCaps == null) {
        continue;
      }
      for (var cap : step.effectCaps) {
        if (cap != null) {
          merged.add(cap);
        }
      }
    }
    return new ArrayList<>(merged);
  }

  private CoreModel.Case lowerCase(Stmt.Case kase) {
    CoreModel.Case out = new CoreModel.Case();
    out.pattern = lowerPattern(kase.pattern());
    out.body = lowerCaseBody(kase.body());
    out.origin = spanToOrigin(kase.span());
    return out;
  }

  private CoreModel.Stmt lowerCaseBody(Stmt.Case.CaseBody body) {
    if (body instanceof Stmt.Return ret) {
      CoreModel.Return out = new CoreModel.Return();
      out.expr = lowerExpr(ret.expr());
      out.origin = spanToOrigin(ret.span());
      return out;
    }
    if (body instanceof Block block) {
      return lowerBlock(block);
    }
    throw new IllegalStateException("未知的 CaseBody 类型：" + body);
  }

  private CoreModel.Expr lowerExpr(Expr expr) {
    Objects.requireNonNull(expr, "expr");
    return switch (expr) {
      case Expr.Name name -> {
        CoreModel.Name out = new CoreModel.Name();
        out.name = name.name();
        out.origin = spanToOrigin(name.span());
        yield out;
      }
      case Expr.Bool bool -> {
        CoreModel.Bool out = new CoreModel.Bool();
        out.value = bool.value();
        out.origin = spanToOrigin(bool.span());
        yield out;
      }
      case Expr.Int in -> {
        CoreModel.IntE out = new CoreModel.IntE();
        out.value = in.value();
        out.origin = spanToOrigin(in.span());
        yield out;
      }
      case Expr.Long lo -> {
        CoreModel.LongE out = new CoreModel.LongE();
        out.value = lo.value();
        out.origin = spanToOrigin(lo.span());
        yield out;
      }
      case Expr.Double dbl -> {
        CoreModel.DoubleE out = new CoreModel.DoubleE();
        out.value = dbl.value();
        out.origin = spanToOrigin(dbl.span());
        yield out;
      }
      case Expr.String str -> {
        CoreModel.StringE out = new CoreModel.StringE();
        out.value = str.value();
        out.origin = spanToOrigin(str.span());
        yield out;
      }
      case Expr.Null nul -> {
        CoreModel.NullE out = new CoreModel.NullE();
        out.origin = spanToOrigin(nul.span());
        yield out;
      }
      case Expr.Call call -> {
        CoreModel.Call out = new CoreModel.Call();
        out.target = lowerExpr(call.target());
        out.args = new ArrayList<>();
        if (call.args() != null) {
          for (Expr arg : call.args()) {
            out.args.add(lowerExpr(arg));
          }
        }
        out.origin = spanToOrigin(call.span());
        yield out;
      }
      case Expr.Construct construct -> {
        CoreModel.Construct out = new CoreModel.Construct();
        out.typeName = construct.typeName();
        out.fields = new ArrayList<>();
        if (construct.fields() != null) {
          for (Expr.Construct.ConstructField field : construct.fields()) {
            CoreModel.FieldInit init = new CoreModel.FieldInit();
            init.name = field.name();
            init.expr = lowerExpr(field.expr());
            out.fields.add(init);
          }
        }
        out.origin = spanToOrigin(construct.span());
        yield out;
      }
      case Expr.Ok ok -> {
        CoreModel.Ok out = new CoreModel.Ok();
        out.expr = lowerExpr(ok.expr());
        out.origin = spanToOrigin(ok.span());
        yield out;
      }
      case Expr.Err err -> {
        CoreModel.Err out = new CoreModel.Err();
        out.expr = lowerExpr(err.expr());
        out.origin = spanToOrigin(err.span());
        yield out;
      }
      case Expr.Some some -> {
        CoreModel.Some out = new CoreModel.Some();
        out.expr = lowerExpr(some.expr());
        out.origin = spanToOrigin(some.span());
        yield out;
      }
      case Expr.None none -> {
        CoreModel.NoneE out = new CoreModel.NoneE();
        out.origin = spanToOrigin(none.span());
        yield out;
      }
      case Expr.Lambda lambda -> lowerLambda(lambda);
      case Expr.Await await -> {
        CoreModel.Await out = new CoreModel.Await();
        out.expr = lowerExpr(await.expr());
        out.origin = spanToOrigin(await.span());
        yield out;
      }
      case Expr.ListLiteral list -> {
        CoreModel.Construct out = new CoreModel.Construct();
        out.typeName = "List";
        out.fields = new ArrayList<>();
        if (list.items() != null) {
          int index = 0;
          for (Expr item : list.items()) {
            CoreModel.FieldInit init = new CoreModel.FieldInit();
            init.name = Integer.toString(index++);
            init.expr = lowerExpr(item);
            out.fields.add(init);
          }
        }
        out.origin = spanToOrigin(list.span());
        yield out;
      }
    };
  }

  private CoreModel.Lambda lowerLambda(Expr.Lambda lambda) {
    CoreModel.Lambda out = new CoreModel.Lambda();
    out.params = new ArrayList<>();
    if (lambda.params() != null) {
      for (Decl.Parameter param : lambda.params()) {
        CoreModel.Param lowered = lowerParam(param);
        out.params.add(lowered);
      }
    }
    out.ret = lambda.retType() == null ? null : lowerType(lambda.retType());
    out.body = lambda.body() == null ? emptyBlock(lambda.span()) : lowerBlock(lambda.body());
    out.captures = new ArrayList<>(collectCaptures(lambda));
    out.origin = spanToOrigin(lambda.span());
    return out;
  }

  private CoreModel.Pattern lowerPattern(Pattern pattern) {
    Objects.requireNonNull(pattern, "pattern");
    return switch (pattern) {
      case Pattern.PatternNull nul -> {
        CoreModel.PatNull out = new CoreModel.PatNull();
        out.origin = spanToOrigin(nul.span());
        yield out;
      }
      case Pattern.PatternCtor ctor -> {
        CoreModel.PatCtor out = new CoreModel.PatCtor();
        out.typeName = ctor.typeName();
        out.names = ctor.names() == null ? new ArrayList<>() : new ArrayList<>(ctor.names());
        if (ctor.args() != null) {
          out.args = new ArrayList<>();
          for (Pattern arg : ctor.args()) {
            out.args.add(lowerPattern(arg));
          }
        } else {
          out.args = null;
        }
        out.origin = spanToOrigin(ctor.span());
        yield out;
      }
      case Pattern.PatternName name -> {
        CoreModel.PatName out = new CoreModel.PatName();
        out.name = name.name();
        out.origin = spanToOrigin(name.span());
        yield out;
      }
      case Pattern.PatternInt in -> {
        CoreModel.PatInt out = new CoreModel.PatInt();
        out.value = in.value();
        out.origin = spanToOrigin(in.span());
        yield out;
      }
    };
  }

  private CoreModel.Type lowerType(Type type) {
    if (type == null) {
      return null;
    }
    CoreModel.Type lowered = switch (type) {
      case Type.TypeName name -> {
        CoreModel.TypeName out = new CoreModel.TypeName();
        out.name = name.name();
        out.origin = spanToOrigin(name.span());
        yield out;
      }
      case Type.TypeVar var -> {
        CoreModel.TypeVar out = new CoreModel.TypeVar();
        out.name = var.name();
        out.origin = spanToOrigin(var.span());
        yield out;
      }
      case Type.TypeApp app -> {
        CoreModel.TypeApp out = new CoreModel.TypeApp();
        out.base = app.base();
        out.args = new ArrayList<>();
        if (app.args() != null) {
          for (Type arg : app.args()) {
            out.args.add(lowerType(arg));
          }
        }
        out.origin = spanToOrigin(app.span());
        yield out;
      }
      case Type.Result result -> {
        CoreModel.Result out = new CoreModel.Result();
        out.ok = lowerType(result.ok());
        out.err = lowerType(result.err());
        out.origin = spanToOrigin(result.span());
        yield out;
      }
      case Type.Maybe maybe -> {
        CoreModel.Maybe out = new CoreModel.Maybe();
        out.type = lowerType(maybe.type());
        out.origin = spanToOrigin(maybe.span());
        yield out;
      }
      case Type.Option option -> {
        CoreModel.Option out = new CoreModel.Option();
        out.type = lowerType(option.type());
        out.origin = spanToOrigin(option.span());
        yield out;
      }
      case Type.List list -> {
        CoreModel.ListT out = new CoreModel.ListT();
        out.type = lowerType(list.type());
        out.origin = spanToOrigin(list.span());
        yield out;
      }
      case Type.Map map -> {
        CoreModel.MapT out = new CoreModel.MapT();
        out.key = lowerType(map.key());
        out.val = lowerType(map.val());
        out.origin = spanToOrigin(map.span());
        yield out;
      }
      case Type.FuncType func -> {
        CoreModel.FuncType out = new CoreModel.FuncType();
        out.params = new ArrayList<>();
        if (func.params() != null) {
          for (Type param : func.params()) {
            out.params.add(lowerType(param));
          }
        }
        out.ret = lowerType(func.ret());
        out.origin = spanToOrigin(func.span());
        yield out;
      }
    };
    return applyTypeAnnotations(lowered, type, type.annotations());
  }

  private CoreModel.Type applyTypeAnnotations(CoreModel.Type lowered, Type source, List<Annotation> annotations) {
    if (lowered == null || annotations == null || annotations.isEmpty()) {
      return lowered;
    }
    for (Annotation annotation : annotations) {
      if ("pii".equalsIgnoreCase(annotation.name())) {
        return wrapWithPiiType(lowered, annotation, source);
      }
    }
    return lowered;
  }

  private CoreModel.Type wrapWithPiiType(CoreModel.Type baseType, Annotation annotation, Type source) {
    CoreModel.PiiType pii = new CoreModel.PiiType();
    pii.baseType = baseType;
    pii.sensitivity = annotationValue(annotation, "level", "$0");
    pii.category = annotationValue(annotation, "category", "$1");
    pii.origin = source == null ? null : spanToOrigin(source.span());
    return pii;
  }

  private String annotationValue(Annotation annotation, String... keys) {
    if (annotation == null || annotation.params() == null || annotation.params().isEmpty()) {
      return "";
    }
    Map<String, Object> params = annotation.params();
    for (String key : keys) {
      Object value = params.get(key);
      if (value != null) {
        return value.toString();
      }
    }
    return "";
  }

  private void annotateFuncPii(CoreModel.Func func) {
    if (func == null) {
      return;
    }
    PiiMetadata summary = null;
    if (func.params != null) {
      for (CoreModel.Param param : func.params) {
        summary = mergePiiMeta(summary, extractPiiFromType(param.type));
      }
    }
    summary = mergePiiMeta(summary, extractPiiFromType(func.ret));
    if (summary == null || summary.level == null || summary.level.isEmpty()) {
      func.piiLevel = "";
      func.piiCategories = Collections.emptyList();
      return;
    }
    func.piiLevel = summary.level;
    func.piiCategories = new ArrayList<>(summary.categories());
  }

  private PiiMetadata extractPiiFromType(CoreModel.Type type) {
    if (type == null) {
      return null;
    }
    if (type instanceof CoreModel.PiiType pii) {
      LinkedHashSet<String> categories = new LinkedHashSet<>();
      if (pii.category != null && !pii.category.isEmpty()) {
        categories.add(pii.category);
      }
      PiiMetadata nested = extractPiiFromType(pii.baseType);
      PiiMetadata current = new PiiMetadata(pii.sensitivity == null ? "" : pii.sensitivity, categories);
      return mergePiiMeta(current, nested);
    }
    if (type instanceof CoreModel.Result result) {
      return mergePiiMeta(extractPiiFromType(result.ok), extractPiiFromType(result.err));
    }
    if (type instanceof CoreModel.Maybe maybe) {
      return extractPiiFromType(maybe.type);
    }
    if (type instanceof CoreModel.Option option) {
      return extractPiiFromType(option.type);
    }
    if (type instanceof CoreModel.ListT list) {
      return extractPiiFromType(list.type);
    }
    if (type instanceof CoreModel.MapT map) {
      return mergePiiMeta(extractPiiFromType(map.key), extractPiiFromType(map.val));
    }
    if (type instanceof CoreModel.FuncType funcType) {
      PiiMetadata meta = null;
      if (funcType.params != null) {
        for (CoreModel.Type param : funcType.params) {
          meta = mergePiiMeta(meta, extractPiiFromType(param));
        }
      }
      return mergePiiMeta(meta, extractPiiFromType(funcType.ret));
    }
    if (type instanceof CoreModel.TypeApp app && app.args != null) {
      PiiMetadata meta = null;
      for (CoreModel.Type arg : app.args) {
        meta = mergePiiMeta(meta, extractPiiFromType(arg));
      }
      return meta;
    }
    return null;
  }

  private PiiMetadata mergePiiMeta(PiiMetadata left, PiiMetadata right) {
    if (left == null) return right;
    if (right == null) return left;
    String level = maxPiiLevel(left.level, right.level);
    LinkedHashSet<String> categories = new LinkedHashSet<>(left.categories());
    categories.addAll(right.categories());
    return new PiiMetadata(level, categories);
  }

  private String maxPiiLevel(String a, String b) {
    String left = a == null ? "" : a;
    String right = b == null ? "" : b;
    int leftRank = piiLevelRank(left);
    int rightRank = piiLevelRank(right);
    return leftRank >= rightRank ? left : right;
  }

  private int piiLevelRank(String level) {
    return switch (level) {
      case "L1" -> 1;
      case "L2" -> 2;
      case "L3" -> 3;
      default -> 0;
    };
  }

  private record PiiMetadata(String level, LinkedHashSet<String> categories) {}

  private CoreModel.Block emptyBlock(Span source) {
    CoreModel.Block block = new CoreModel.Block();
    block.statements = new ArrayList<>();
    block.origin = spanToOrigin(source);
    return block;
  }

  private List<CoreModel.Annotation> lowerAnnotations(List<Annotation> annotations) {
    if (annotations == null || annotations.isEmpty()) {
      return new ArrayList<>();
    }
    List<CoreModel.Annotation> out = new ArrayList<>(annotations.size());
    for (Annotation annotation : annotations) {
      CoreModel.Annotation lowered = new CoreModel.Annotation();
      lowered.name = annotation.name();
      Map<String, Object> params = annotation.params();
      LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
      if (params != null) {
        copied.putAll(params);
      }
      lowered.params = copied;
      out.add(lowered);
    }
    return out;
  }

  private CoreModel.Origin spanToOrigin(Span span) {
    if (span == null) {
      return null;
    }
    CoreModel.Origin origin = new CoreModel.Origin();
    origin.start = toPosition(span.start());
    origin.end = toPosition(span.end());
    origin.file = null;
    return origin;
  }

  private CoreModel.Position toPosition(Span.Position pos) {
    if (pos == null) {
      return null;
    }
    CoreModel.Position place = new CoreModel.Position();
    place.line = pos.line();
    place.col = pos.col();
    return place;
  }

  private Set<String> collectCaptures(Expr.Lambda lambda) {
    if (lambda.body() == null) {
      return Set.of();
    }
    Set<String> params = new HashSet<>();
    if (lambda.params() != null) {
      for (Decl.Parameter param : lambda.params()) {
        params.add(param.name());
      }
    }
    LinkedHashSet<String> captures = new LinkedHashSet<>();
    ArrayDeque<Set<String>> scopes = new ArrayDeque<>();
    scopes.push(new HashSet<>(params));
    traverseBlock(lambda.body(), scopes, captures);
    return captures;
  }

  private void traverseBlock(Block block, ArrayDeque<Set<String>> scopes, Set<String> captures) {
    if (block == null || block.statements() == null) {
      return;
    }
    scopes.push(new HashSet<>(scopes.peek()));
    try {
      for (Stmt stmt : block.statements()) {
        traverseStmt(stmt, scopes, captures);
      }
    } finally {
      scopes.pop();
    }
  }

  private void traverseStmt(Stmt stmt, ArrayDeque<Set<String>> scopes, Set<String> captures) {
    if (stmt == null) {
      return;
    }
    switch (stmt) {
      case Stmt.Let let -> {
        visitExpr(let.expr(), scopes, captures);
        scopes.peek().add(let.name());
      }
      case Stmt.Set set -> visitExpr(set.expr(), scopes, captures);
      case Stmt.Return ret -> visitExpr(ret.expr(), scopes, captures);
      case Stmt.If ifStmt -> {
        visitExpr(ifStmt.cond(), scopes, captures);
        traverseBlock(ifStmt.thenBlock(), scopes, captures);
        if (ifStmt.elseBlock() != null) {
          traverseBlock(ifStmt.elseBlock(), scopes, captures);
        }
      }
      case Stmt.Match match -> {
        visitExpr(match.expr(), scopes, captures);
        if (match.cases() != null) {
          for (Stmt.Case kase : match.cases()) {
            Set<String> next = new HashSet<>(scopes.peek());
            next.addAll(patternBindings(kase.pattern()));
            scopes.push(next);
            try {
              if (kase.body() instanceof Stmt.Return ret) {
                visitExpr(ret.expr(), scopes, captures);
              } else if (kase.body() instanceof Block block) {
                traverseBlock(block, scopes, captures);
              }
            } finally {
              scopes.pop();
            }
          }
        }
      }
      case Stmt.Start start -> visitExpr(start.expr(), scopes, captures);
      case Stmt.Wait ignored -> { }
      case Stmt.Workflow workflow -> {
        if (workflow.steps() != null) {
          for (Stmt.WorkflowStep step : workflow.steps()) {
            traverseBlock(step.body(), scopes, captures);
            if (step.compensate() != null) {
              traverseBlock(step.compensate(), scopes, captures);
            }
          }
        }
      }
      case Block block -> traverseBlock(block, scopes, captures);
    }
  }

  private void visitExpr(Expr expr, ArrayDeque<Set<String>> scopes, Set<String> captures) {
    if (expr == null) {
      return;
    }
    if (expr instanceof Expr.Name name) {
      if (name.name() != null && !name.name().contains(".") && !isBound(name.name(), scopes)) {
        captures.add(name.name());
      }
      return;
    }
    if (expr instanceof Expr.Call call) {
      visitExpr(call.target(), scopes, captures);
      if (call.args() != null) {
        for (Expr arg : call.args()) {
          visitExpr(arg, scopes, captures);
        }
      }
      return;
    }
    if (expr instanceof Expr.Construct construct) {
      if (construct.fields() != null) {
        for (Expr.Construct.ConstructField field : construct.fields()) {
          visitExpr(field.expr(), scopes, captures);
        }
      }
      return;
    }
    if (expr instanceof Expr.Ok ok) {
      visitExpr(ok.expr(), scopes, captures);
      return;
    }
    if (expr instanceof Expr.Err err) {
      visitExpr(err.expr(), scopes, captures);
      return;
    }
    if (expr instanceof Expr.Some some) {
      visitExpr(some.expr(), scopes, captures);
      return;
    }
    if (expr instanceof Expr.Await await) {
      visitExpr(await.expr(), scopes, captures);
      return;
    }
    if (expr instanceof Expr.Lambda) {
      return;
    }
    if (expr instanceof Expr.ListLiteral list) {
      if (list.items() != null) {
        for (Expr item : list.items()) {
          visitExpr(item, scopes, captures);
        }
      }
    }
  }

  private boolean isBound(String name, ArrayDeque<Set<String>> scopes) {
    for (Set<String> scope : scopes) {
      if (scope.contains(name)) {
        return true;
      }
    }
    return false;
  }

  private Set<String> patternBindings(Pattern pattern) {
    if (pattern == null) {
      return Set.of();
    }
    Set<String> names = new HashSet<>();
    if (pattern instanceof Pattern.PatternName name) {
      if (name.name() != null) {
        names.add(name.name());
      }
      return names;
    }
    if (pattern instanceof Pattern.PatternCtor ctor) {
      if (ctor.names() != null) {
        names.addAll(ctor.names());
      }
      if (ctor.args() != null) {
        for (Pattern arg : ctor.args()) {
          names.addAll(patternBindings(arg));
        }
      }
      return names;
    }
    // PatternNull / PatternInt 不引入绑定
    return names;
  }
}
