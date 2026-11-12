package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import aster.core.resolve.ResolutionChoice;
import aster.core.typecheck.CallTargetBuiltins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 规则输入引用分析（ADR 0046 §11 D2 传播规则表与 §11.6 实施澄清）：对返回 Verdict 的规则，静态收集它对
 * <b>第一个参数</b>读取的源码字段路径。纯函数，不读注册表；作用于名称解析 R2 之后的 Core（只含规范名）。
 * TS 镜像：aster-lang-ts/src/governance/input-refs.ts，两引擎对同一 Core 必须得到相同结果
 * （共享夹具 governance_input_refs_parity.json）。
 *
 * <p>抽象值是「来源」：路径集合、聚合（记录构造）按字段的来源，以及顶元 {@code *}。表达式求值得到来源，
 * 「消费」处（运算符与内置函数的实参、条件、Match 主语、规则体内的 Return）把来源记入结果；
 * Let / Set 只绑定（同名多次赋值取并），字段访问与字面量 Map 键只扩展路径。同模块调用把形参绑定到实参来源后内联被调函数体，
 * 被调函数 Return 的来源作为调用结果。
 *
 * <p>产生 {@code *} 的构造（construct 闭集）：{@code dynamic-map-key}、{@code map-iteration}、
 * {@code whole-input-serialized}、{@code cross-module-call}、{@code function-value}、{@code inline-depth}、
 * {@code recursion}、{@code path-too-long}、{@code unrepresentable-key}。{@code *} 独占：一旦产生，结果只含
 * {@code ["*"]}；E708 报告先序遍历中第一个产生 {@code *} 的节点（父节点先于子节点；同模块调用的被调函数体排在实参之后）。
 */
public final class InputRefsAnalyzer {

  /** 引擎路径最多段数（ADR 0046 §11.6 第 1 条）。 */
  public static final int MAX_SEGMENTS = 8;
  /** 同模块调用内联的最大嵌套层数（§11.2：恰好 8 层可定位，第 9 层为 {@code *}）。 */
  public static final int MAX_INLINE_DEPTH = 8;
  /**
   * 单条规则分析的节点访问预算（共用先序编号计数）：超出即按 inline-depth 产生 {@code *}，不再展开。
   * 同模块调用按（调用栈，实参来源）记忆化，正常规则的访问数与源码节点数同阶（语料中最大的规则不足 2 千次）；
   * 20 万次留出两个数量级余量，同时把恶意扇出（如 64×64 调用链每层重遍历大函数体）的分析耗时限制在毫秒级。
   */
  public static final int MAX_NODE_VISITS = 200_000;
  public static final String STAR = "*";

  /** 运算符与 await：消费全部实参（与 TS BUILTIN_OPS / BUILTIN_AWAIT 同表）。 */
  private static final Set<String> OPERATORS = Set.of(
      "+", "-", "*", "/", "//", "%", ">", "<", ">=", "<=", "==", "!=", "and", "or", "not");
  /** 按字面量键选取 Map 值：第二个实参为字符串字面量时扩展路径，否则为动态键。 */
  private static final Set<String> MAP_KEY_ACCESS = Set.of("Map.get", "Map.contains", "Map.containsKey");
  /** 迭代 Map 的键或值。 */
  private static final Set<String> MAP_ITERATION = Set.of("Map.keys", "Map.values");
  /** 把值整体转为文本或摘要。 */
  private static final Set<String> SERIALIZERS = Set.of("Bool.toString", "Crypto.hash", "Int.toString");
  /**
   * 内置函数命名空间：只有这些限定名按「内置函数，实参被消费」处理，其余未知限定名一律按跨模块调用（{@code *}）。
   * 来源：truffle Builtins 与 TS 类型系统（Bool … Verdict）；两引擎的效果 / 能力 / PII 表（Console、Http、Log、UUID）；
   * 只出现在 TS 效果 / PII / LSP 表中的 Consent、Db、GDPR、Set 也列入，两引擎同表（parity）。与 TS BUILTIN_NAMESPACES 同表。
   */
  private static final Set<String> BUILTIN_NAMESPACES = Set.of(
      "Bool", "Consent", "Console", "Crypto", "Date", "Db", "Decimal", "GDPR", "Http", "IO", "Int", "List", "Log", "Map",
      "Maybe", "Option", "PII", "Result", "Set", "Text", "UUID", "Verdict");

  /**
   * 分析结果。
   *
   * @param inputRefs 字典序去重的路径；无法静态确定时为 {@code ["*"]}
   * @param construct 产生 {@code *} 的第一个构造；inputRefs 不是 {@code ["*"]} 时为 null
   */
  public record Result(List<String> inputRefs, String construct) {
    public Result {
      inputRefs = List.copyOf(inputRefs);
    }

    public boolean unresolvable() {
      return construct != null;
    }
  }

  /** 是否为该函数写入 inputRefs：模块声明了档案（不论注册表），且函数返回 Verdict。 */
  public static boolean applies(CoreModel.Module module, CoreModel.Func func) {
    return module != null && module.profile != null && GovernanceChecker.returnsVerdict(func);
  }

  /** 分析 module 中的规则 rule（rule 须属于 module，以便识别同模块调用与导入别名）。 */
  public static Result analyze(CoreModel.Module module, CoreModel.Func rule) {
    InputRefsAnalyzer a = new InputRefsAnalyzer(module);
    a.run(rule);
    if (a.firstConstruct != null) return new Result(List.of(STAR), a.firstConstruct);
    return new Result(new ArrayList<>(a.refs), null);
  }

  // ---- 抽象值 ----

  /**
   * 来源：star 为顶元；paths 为第一个参数下的路径（{@code ""} 为参数本身）；fields 为记录构造的按字段来源。
   * 不可变；EMPTY 为不含输入来源的值。
   */
  private record Src(boolean star, Set<String> paths, Map<String, Src> fields) {
    static final Src EMPTY = new Src(false, Set.of(), Map.of());
    static final Src TOP = new Src(true, Set.of(), Map.of());

    static Src path(String p) {
      return new Src(false, Set.of(p), Map.of());
    }

    /**
     * 记忆化键用的规范形：去掉不含输入来源的字段（递归），其余原样。记录的 equals / hashCode 按结构比较
     * （Set / Map 按内容），因此结构相同的来源键相同、不同的来源键不同——不经字符串拼接，路径或 Map 键里出现
     * 任何字符都不会碰撞（与 TS 的 JSON 键同一语义）。
     */
    Src canonical() {
      if (star) return TOP;
      Map<String, Src> fs = new HashMap<>();
      fields.forEach((k, v) -> {
        if (!v.isEmpty()) fs.put(k, v.canonical());
      });
      return new Src(false, Set.copyOf(paths), fs);
    }

    boolean isEmpty() {
      return !star && paths.isEmpty() && fields.values().stream().allMatch(Src::isEmpty);
    }

    Src union(Src o) {
      if (o.isEmpty()) return this;
      if (isEmpty()) return o;
      if (star || o.star) return TOP;
      Set<String> ps = new TreeSet<>(paths);
      ps.addAll(o.paths);
      Map<String, Src> fs = new LinkedHashMap<>(fields);
      o.fields.forEach((k, v) -> fs.merge(k, v, Src::union));
      return new Src(false, ps, fs);
    }
  }

  private enum Mode { RULE, CALLEE, LAMBDA }

  /** 一次函数体遍历的上下文：变量环境、Return 的处理方式与被调函数 Return 的来源累积。 */
  private static final class Frame {
    final Map<String, Src> env;
    final Mode mode;
    final int depth;
    Src returns = Src.EMPTY;

    Frame(Map<String, Src> env, Mode mode, int depth) {
      this.env = env;
      this.mode = mode;
      this.depth = depth;
    }

    void bind(String name, Src v) {
      if (name != null) env.merge(name, v, Src::union);
    }
  }

  // ---- 状态 ----

  private final CoreModel.Module module;
  private final Map<String, CoreModel.Func> funcs = new HashMap<>();
  private final List<String> importAliases = new ArrayList<>();
  private final List<String> importPaths = new ArrayList<>();
  private final Map<CoreModel.Func, Integer> funcIds = new java.util.IdentityHashMap<>();
  /** 当前被消费的路径；内联被调函数体时临时换成该次调用自己的集合，结束后并回调用方。 */
  private Set<String> refs = new TreeSet<>();
  private final Deque<CoreModel.Func> stack = new ArrayDeque<>();
  /** 同模块调用的记忆化：（调用栈，被调函数，实参来源）→ 该次内联的摘要。 */
  private final Map<MemoKey, Summary> memo = new HashMap<>();

  /** 记忆化键：调用栈（栈顶在前的函数编号）、被调函数编号、各实参来源的规范形，按结构比较。 */
  private record MemoKey(List<Integer> stack, int callee, List<Src> args) {}
  /** 节点先序编号：进入节点时递增；记录编号最小的产生 * 的节点。 */
  private int nodeCounter;
  private int firstIndex = Integer.MAX_VALUE;
  private String firstConstruct;

  private InputRefsAnalyzer(CoreModel.Module module) {
    this.module = module;
    for (CoreModel.Decl d : module.decls == null ? List.<CoreModel.Decl>of() : module.decls) {
      if (d instanceof CoreModel.Func f) {
        funcIds.put(f, funcIds.size());
        if (f.name != null) funcs.putIfAbsent(f.name, f);
      }
      if (d instanceof CoreModel.Import imp) {
        if (imp.alias != null) importAliases.add(imp.alias);
        if (imp.path != null) importPaths.add(imp.path);
      }
    }
  }

  private void run(CoreModel.Func rule) {
    Map<String, Src> env = new HashMap<>();
    List<CoreModel.Param> params = rule.params == null ? List.of() : rule.params;
    // 只追踪第一个参数（§11.6 补充规则 (c)）；其余参数是不含输入来源的局部名
    for (int i = 0; i < params.size(); i++) {
      env.put(params.get(i).name, i == 0 ? Src.path("") : Src.EMPTY);
    }
    stack.push(rule);
    walk(rule.body, new Frame(env, Mode.RULE, 0));
    stack.pop();
  }

  private Src produce(int index, String construct) {
    if (index < firstIndex) {
      firstIndex = index;
      firstConstruct = construct;
    }
    return Src.TOP;
  }

  private void consume(Src v) {
    if (v.star) return;
    refs.addAll(v.paths);
    v.fields.values().forEach(this::consume);
  }

  // ---- 语句 ----

  private void walk(CoreModel.Stmt stmt, Frame f) {
    switch (stmt) {
      case null -> { }
      case CoreModel.Let s -> f.bind(s.name, eval(s.expr, f));
      case CoreModel.Set s -> f.bind(s.name, eval(s.expr, f));
      case CoreModel.Return s -> {
        Src v = eval(s.expr, f);
        if (f.mode == Mode.CALLEE) f.returns = f.returns.union(v);
        else consume(v);
      }
      case CoreModel.If s -> {
        consume(eval(s.cond, f));
        walk(s.thenBlock, f);
        walk(s.elseBlock, f);
      }
      case CoreModel.Match s -> {
        Src subject = eval(s.expr, f);
        consume(subject);
        for (CoreModel.Case c : s.cases == null ? List.<CoreModel.Case>of() : s.cases) {
          bindPattern(c.pattern, subject, f);
          walk(c.body, f);
        }
      }
      case CoreModel.Block s -> walkAll(s.statements, f);
      case CoreModel.Scope s -> walkAll(s.statements, f);
      case CoreModel.Start s -> f.bind(s.name, eval(s.expr, f));
      case CoreModel.Wait ignored -> { }
      case CoreModel.Workflow s -> {
        for (CoreModel.Step step : s.steps == null ? List.<CoreModel.Step>of() : s.steps) {
          walk(step.body, f);
          walk(step.compensate, f);
        }
      }
    }
  }

  private void walkAll(List<CoreModel.Stmt> stmts, Frame f) {
    if (stmts != null) stmts.forEach(s -> walk(s, f));
  }

  /** 模式变量绑定到被匹配值的来源（PatCtor 的 names 与嵌套 args 都绑定，两引擎 IR 形状差异因此无影响）。 */
  private void bindPattern(CoreModel.Pattern p, Src v, Frame f) {
    switch (p) {
      case CoreModel.PatName n -> f.bind(n.name, v);
      case CoreModel.PatCtor c -> {
        if (c.names != null) c.names.forEach(n -> f.bind(n, v));
        if (c.args != null) c.args.forEach(a -> bindPattern(a, v, f));
      }
      case null, default -> { }
    }
  }

  // ---- 表达式 ----

  private Src eval(CoreModel.Expr expr, Frame f) {
    if (expr == null) return Src.EMPTY;
    int index = ++nodeCounter;
    if (index > MAX_NODE_VISITS) return produce(index, "inline-depth");
    return switch (expr) {
      case CoreModel.Name n -> name(n.name, index, f);
      case CoreModel.Call c -> call(c, index, f);
      case CoreModel.Construct c -> {
        Map<String, Src> fields = new LinkedHashMap<>();
        for (CoreModel.FieldInit fi : c.fields == null ? List.<CoreModel.FieldInit>of() : c.fields) {
          fields.merge(fi.name, eval(fi.expr, f), Src::union);
        }
        yield fields.values().stream().anyMatch(v -> v.star) ? Src.TOP : new Src(false, Set.of(), fields);
      }
      case CoreModel.ListE l -> evalAll(l.elements, f).stream().reduce(Src.EMPTY, Src::union);
      case CoreModel.Ok o -> eval(o.expr, f);
      case CoreModel.Err e -> eval(e.expr, f);
      case CoreModel.Some s -> eval(s.expr, f);
      case CoreModel.Await a -> eval(a.expr, f);
      case CoreModel.IfE i -> {
        consume(eval(i.cond, f));
        Src t = eval(i.thenE, f);
        yield t.union(eval(i.elseE, f));
      }
      case CoreModel.Lambda l -> {
        Map<String, Src> env = new HashMap<>(f.env);
        if (l.params != null) l.params.forEach(p -> env.put(p.name, Src.EMPTY));
        walk(l.body, new Frame(env, Mode.LAMBDA, f.depth));
        yield Src.EMPTY;
      }
      default -> Src.EMPTY;
    };
  }

  private List<Src> evalAll(List<CoreModel.Expr> exprs, Frame f) {
    List<Src> out = new ArrayList<>();
    if (exprs != null) exprs.forEach(e -> out.add(eval(e, f)));
    return out;
  }

  /** 变量引用与点分字段访问：首段是局部名时逐段扩展路径；否则（函数名、枚举成员、限定名）不含输入来源。 */
  private Src name(String name, int index, Frame f) {
    if (name == null) return Src.EMPTY;
    if (f.env.containsKey(name)) return f.env.get(name);
    int dot = name.indexOf('.');
    if (dot < 0 || !f.env.containsKey(name.substring(0, dot))) return Src.EMPTY;
    Src v = f.env.get(name.substring(0, dot));
    for (String seg : name.substring(dot + 1).split("\\.", -1)) {
      v = select(v, seg, index);
    }
    return v;
  }

  private Src select(Src v, String seg, int index) {
    if (v.star) return v;
    Set<String> ps = new TreeSet<>();
    for (String p : v.paths) {
      String q = p.isEmpty() ? seg : p + "." + seg;
      if (segments(q) > MAX_SEGMENTS) return produce(index, "path-too-long");
      ps.add(q);
    }
    Src fromPaths = ps.isEmpty() ? Src.EMPTY : new Src(false, ps, Map.of());
    return fromPaths.union(v.fields.getOrDefault(seg, Src.EMPTY));
  }

  private static int segments(String path) {
    int n = 1;
    for (int i = 0; i < path.length(); i++) {
      if (path.charAt(i) == '.') n++;
    }
    return n;
  }

  private Src call(CoreModel.Call c, int index, Frame f) {
    if (!(c.target instanceof CoreModel.Name tn) || tn.name == null) {
      Src target = eval(c.target, f);
      List<Src> args = evalAll(c.args, f);
      return target.isEmpty() && allEmpty(args) ? Src.EMPTY : produce(index, "function-value");
    }
    String t = tn.name;
    if (OPERATORS.contains(t)) return consumeAll(evalAll(c.args, f));
    if ("await".equals(t) || ResolutionChoice.KEYWORD_CTORS.contains(t)) return unionAll(evalAll(c.args, f));
    if (f.env.containsKey(t) || isFieldOfLocal(t, f)) {
      Src target = name(t, index, f);
      List<Src> args = evalAll(c.args, f);
      return target.isEmpty() && allEmpty(args) ? Src.EMPTY : produce(index, "function-value");
    }
    CoreModel.Func local = localFunc(t);
    if (local != null) return inline(local, evalAll(c.args, f), index, f);
    if (CallTargetBuiltins.isCallTarget(t)) return consumeAll(evalAll(c.args, f));
    if (MAP_KEY_ACCESS.contains(t) && c.args != null && c.args.size() == 2) return mapKey(c, index, f);
    if (MAP_ITERATION.contains(t)) return producesIfAny(evalAll(c.args, f), index, "map-iteration");
    if (SERIALIZERS.contains(t)) return producesIfAny(evalAll(c.args, f), index, "whole-input-serialized");
    if (t.indexOf('.') < 0 || isImported(t) || !BUILTIN_NAMESPACES.contains(t.substring(0, t.indexOf('.')))) {
      return producesIfAny(evalAll(c.args, f), index, "cross-module-call");
    }
    // 内置命名空间的限定名（Text.* / List.* / Verdict.* …）：实参被消费
    return consumeAll(evalAll(c.args, f));
  }

  private boolean isFieldOfLocal(String t, Frame f) {
    int dot = t.indexOf('.');
    return dot > 0 && f.env.containsKey(t.substring(0, dot));
  }

  /** 同模块函数：未限定名，或 {@code <模块名>.<函数名>}（§11.6 第 2 条：按模块限定名分类）。 */
  private CoreModel.Func localFunc(String t) {
    if (t.indexOf('.') < 0) return funcs.get(t);
    String prefix = module.name == null ? null : module.name + ".";
    if (prefix == null || !t.startsWith(prefix)) return null;
    String rest = t.substring(prefix.length());
    return rest.indexOf('.') < 0 ? funcs.get(rest) : null;
  }

  private boolean isImported(String t) {
    String head = t.substring(0, t.indexOf('.'));
    return importAliases.contains(head) || importPaths.stream().anyMatch(p -> t.startsWith(p + "."));
  }

  private Src mapKey(CoreModel.Call c, int index, Frame f) {
    Src map = eval(c.args.get(0), f);
    CoreModel.Expr keyExpr = c.args.get(1);
    Src key = eval(keyExpr, f);
    if (keyExpr instanceof CoreModel.StringE s && s.value != null) {
      if (map.isEmpty()) return Src.EMPTY;
      if (!representableKey(s.value)) return produce(index, "unrepresentable-key");
      return select(map, s.value, index);
    }
    consume(key);
    return map.isEmpty() ? Src.EMPTY : produce(index, "dynamic-map-key");
  }

  /** 一次内联的摘要：Return 的来源、被消费的路径、体内第一个产生 * 的构造（无则 null）。 */
  private record Summary(Src returns, Set<String> consumed, String construct) {}

  /**
   * 同模块调用：形参绑定到实参来源后内联被调函数体。结果按（调用栈，被调函数，实参来源）记忆化——同一调用点的
   * 重复展开不再重遍历函数体；体内产生的 * 在实参之后、调用点之后的节点之前按先序登记（取新编号）。
   */
  /** 字面量 Map 键能否作为一段点路径：非空、不含点、不含控制字符 U+0000–U+001F。 */
  private static boolean representableKey(String key) {
    if (key.isEmpty()) return false;
    for (int i = 0; i < key.length(); i++) {
      char ch = key.charAt(i);
      if (ch == '.' || ch < 0x20) return false;
    }
    return true;
  }

  private Src inline(CoreModel.Func callee, List<Src> args, int index, Frame f) {
    if (allEmpty(args)) return Src.EMPTY;
    if (stack.contains(callee)) return produce(index, "recursion");
    if (f.depth + 1 > MAX_INLINE_DEPTH) return produce(index, "inline-depth");
    MemoKey key = memoKey(callee, args);
    Summary s = memo.get(key);
    if (s == null) {
      s = summarize(callee, args, f.depth + 1);
      memo.put(key, s);
    }
    refs.addAll(s.consumed());
    if (s.construct() != null) produce(++nodeCounter, s.construct());
    return s.returns();
  }

  private MemoKey memoKey(CoreModel.Func callee, List<Src> args) {
    List<Integer> ids = new ArrayList<>();
    stack.forEach(fn -> ids.add(funcIds.get(fn)));
    return new MemoKey(List.copyOf(ids), funcIds.get(callee), args.stream().map(Src::canonical).toList());
  }

  private Summary summarize(CoreModel.Func callee, List<Src> args, int depth) {
    Set<String> outerRefs = refs;
    int outerIndex = firstIndex;
    String outerConstruct = firstConstruct;
    refs = new TreeSet<>();
    firstIndex = Integer.MAX_VALUE;
    firstConstruct = null;
    Map<String, Src> env = new HashMap<>();
    List<CoreModel.Param> params = callee.params == null ? List.of() : callee.params;
    for (int i = 0; i < params.size(); i++) {
      env.merge(params.get(i).name, i < args.size() ? args.get(i) : Src.EMPTY, Src::union);
    }
    Frame frame = new Frame(env, Mode.CALLEE, depth);
    stack.push(callee);
    walk(callee.body, frame);
    stack.pop();
    Summary s = new Summary(frame.returns, refs, firstConstruct);
    refs = outerRefs;
    firstIndex = outerIndex;
    firstConstruct = outerConstruct;
    return s;
  }

  private Src producesIfAny(List<Src> args, int index, String construct) {
    return allEmpty(args) ? Src.EMPTY : produce(index, construct);
  }

  private Src consumeAll(List<Src> args) {
    args.forEach(this::consume);
    return unionAll(args);
  }

  private static Src unionAll(List<Src> args) {
    return args.stream().reduce(Src.EMPTY, Src::union);
  }

  private static boolean allEmpty(List<Src> args) {
    return args.stream().allMatch(Src::isEmpty);
  }
}
