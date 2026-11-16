package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * W709：@pii 输入字段流入返回的 Text（aster-cloud#772 方案 3）。执行输出原样落库并在日志页展示，返回类型没有
 * {@code @pii} 时无法按声明打码，因此在编译期提醒策略作者。纯函数，作用于名称解析 R2 之后的 Core（只含规范名）。
 * TS 镜像：aster-lang-ts/src/governance/pii-text-return.ts，两引擎对同一 Core 必须给出相同结果
 * （共享夹具 governance_pii_text_return_parity.json）。
 *
 * <p>单个函数内的前向数据流。抽象值记录：来源 @pii 字段路径集合（以参数名为前缀，如 {@code applicant.name}）、
 * 已知的声明类型（用于继续选字段）、是否已知为 Text、以及它恰好对应的输入访问路径。
 * <ul>
 *   <li>来源：形参的声明类型（逐层解包 PiiType / Option / Maybe）带 {@code @pii}，或沿点分访问选到的模块内记录字段带
 *       {@code @pii}；从已带来源的值上选字段，来源原样保留（{@code @pii} 包住整个记录时报参数本身）。</li>
 *   <li>传播只经过：变量引用、点分字段访问、{@code +} 与 {@code Text.concat} 的实参、if 表达式的两个分支、
 *       Let / Set 绑定。Set 覆盖原值；If / Match 各分支在环境副本上遍历，汇合处对未 Return 的分支取并；
 *       块内必然 Return 之后的语句不可达，不再分析。条件与 Match 主语不传播。</li>
 *   <li>报告：函数声明返回类型恰为 {@code Text}（不含 {@code @pii(...) Text}，后者表示作者已声明输出是 PII），
 *       或返回类型由推断得到且返回值已知为 Text 时，返回值带来源的每个 Return 按字段字典序各报一条。</li>
 * </ul>
 *
 * <p>有意保守，只求没有误报，漏报可以接受：
 * <ul>
 *   <li>不跨函数传播：调用同模块函数、内置函数（{@code +} 与 {@code Text.concat} 除外）或任何其他调用的结果都不带来源；
 *       被调函数自己的 {@code @pii} 形参在被调函数内单独检查。</li>
 *   <li>不分析集合：List / Map 的元素、记录构造、Ok / Some 等包装都不带来源。</li>
 *   <li>Match 的模式变量、lambda 体与工作流步骤不分析；Scope 与外层共用环境。</li>
 *   <li>只看声明类型：未标注（{@code typeInferred}）的形参与字段不视为 PII，也不按名字猜测。</li>
 * </ul>
 */
public final class PiiTextReturnAnalyzer {
  private static final String TEXT = "Text";
  /** 字符串拼接：实参的来源全部并入结果。 */
  private static final Set<String> CONCAT = Set.of("+", "Text.concat");

  /** 一条发现：泄露的 @pii 字段路径与 Return 语句的源码位置。 */
  public record Leak(String field, CoreModel.Origin origin) {}

  /**
   * 抽象值。pii 为来源字段路径（字典序）；type 为已知的声明类型（未知为 null）；text 表示值已知为 Text；
   * path 为该值恰好对应的输入访问路径（如 {@code applicant.contact}），无则为 null。
   */
  private record Val(SortedSet<String> pii, CoreModel.Type type, boolean text, String path) {
    static final Val CLEAN = new Val(new TreeSet<>(), null, false, null);
  }

  private final Map<String, CoreModel.Data> records = new HashMap<>();
  private final boolean declaredText;
  private final List<Leak> leaks = new ArrayList<>();

  private PiiTextReturnAnalyzer(CoreModel.Module module, CoreModel.Func func) {
    for (CoreModel.Decl d : module == null || module.decls == null ? List.<CoreModel.Decl>of() : module.decls) {
      if (d instanceof CoreModel.Data data && data.name != null) records.putIfAbsent(data.name, data);
    }
    this.declaredText = !func.retTypeInferred && func.ret instanceof CoreModel.TypeName tn && TEXT.equals(tn.name);
  }

  /** 分析 module 中的函数 func，按 Return 的遍历顺序返回发现（同一 Return 内按字段字典序）。 */
  public static List<Leak> analyze(CoreModel.Module module, CoreModel.Func func) {
    if (func == null || func.body == null) return List.of();
    PiiTextReturnAnalyzer a = new PiiTextReturnAnalyzer(module, func);
    if (!a.declaredText && !func.retTypeInferred) return List.of();
    Map<String, Val> env = new HashMap<>();
    for (CoreModel.Param p : func.params == null ? List.<CoreModel.Param>of() : func.params) {
      if (p != null && p.name != null) env.put(p.name, a.seed(p));
    }
    a.walk(func.body, env);
    return List.copyOf(a.leaks);
  }

  private Val seed(CoreModel.Param p) {
    CoreModel.Type type = p.typeInferred ? null : p.type;
    SortedSet<String> pii = new TreeSet<>();
    if (isPii(type)) pii.add(p.name);
    return new Val(pii, type, isText(type), p.name);
  }

  // ---- 语句 ----

  /**
   * 遍历语句，返回该语句是否必然以 Return 结束（其后的语句不可达）。块内遇到必然结束的语句即停；
   * 汇合只取未结束分支的环境，已 Return 的分支对后续语句没有影响。Match 视为可能不命中任何分支，从不必然结束。
   */
  private boolean walk(CoreModel.Stmt stmt, Map<String, Val> env) {
    return switch (stmt) {
      case null -> false;
      case CoreModel.Let s -> bind(env, s.name, eval(s.expr, env));
      case CoreModel.Set s -> bind(env, s.name, eval(s.expr, env));
      case CoreModel.Return s -> {
        report(eval(s.expr, env), s.origin);
        yield true;
      }
      case CoreModel.If s -> {
        Map<String, Val> then = new HashMap<>(env);
        boolean thenEnds = walk(s.thenBlock, then);
        Map<String, Val> otherwise = new HashMap<>(env);
        boolean elseEnds = walk(s.elseBlock, otherwise);
        if (thenEnds && elseEnds) yield true;
        replace(env, thenEnds ? otherwise : elseEnds ? then : merge(then, otherwise));
        yield false;
      }
      case CoreModel.Match s -> {
        Map<String, Val> joined = new HashMap<>(env);
        for (CoreModel.Case c : s.cases == null ? List.<CoreModel.Case>of() : s.cases) {
          Map<String, Val> branch = new HashMap<>(env);
          bindPattern(c.pattern, branch);
          if (!walk(c.body, branch)) joined = merge(joined, branch);
        }
        replace(env, joined);
        yield false;
      }
      case CoreModel.Block s -> walkAll(s.statements, env);
      case CoreModel.Scope s -> walkAll(s.statements, env);
      case CoreModel.Start s -> bind(env, s.name, Val.CLEAN);
      case CoreModel.Wait ignored -> false;
      case CoreModel.Workflow ignored -> false;
    };
  }

  private boolean walkAll(List<CoreModel.Stmt> stmts, Map<String, Val> env) {
    for (CoreModel.Stmt s : stmts == null ? List.<CoreModel.Stmt>of() : stmts) {
      if (walk(s, env)) return true;
    }
    return false;
  }

  /** 绑定后语句继续向下执行，恒返回 false。 */
  private static boolean bind(Map<String, Val> env, String name, Val v) {
    if (name != null) env.put(name, v);
    return false;
  }

  /** 模式变量遮蔽同名外层绑定，一律不带来源。 */
  private static void bindPattern(CoreModel.Pattern p, Map<String, Val> env) {
    switch (p) {
      case CoreModel.PatName n -> bind(env, n.name, Val.CLEAN);
      case CoreModel.PatCtor c -> {
        if (c.args != null) c.args.forEach(a -> bindPattern(a, env));
      }
      case null, default -> { }
    }
  }

  private static void replace(Map<String, Val> env, Map<String, Val> next) {
    env.clear();
    env.putAll(next);
  }

  private static Map<String, Val> merge(Map<String, Val> a, Map<String, Val> b) {
    Map<String, Val> out = new HashMap<>(a);
    b.forEach((k, v) -> out.merge(k, v, PiiTextReturnAnalyzer::join));
    return out;
  }

  private static Val join(Val a, Val b) {
    SortedSet<String> pii = new TreeSet<>(a.pii);
    pii.addAll(b.pii);
    CoreModel.Type type = sameType(a.type, b.type) ? a.type : null;
    return new Val(pii, type, a.text && b.text, Objects.equals(a.path, b.path) ? a.path : null);
  }

  private static boolean sameType(CoreModel.Type a, CoreModel.Type b) {
    if (a == b) return true;
    return a instanceof CoreModel.TypeName x && b instanceof CoreModel.TypeName y && Objects.equals(x.name, y.name);
  }

  private void report(Val v, CoreModel.Origin origin) {
    if (v.pii.isEmpty() || !(declaredText || v.text)) return;
    v.pii.forEach(field -> leaks.add(new Leak(field, origin)));
  }

  // ---- 表达式 ----

  private Val eval(CoreModel.Expr expr, Map<String, Val> env) {
    return switch (expr) {
      case CoreModel.Name n -> name(n.name, env);
      case CoreModel.StringE ignored -> new Val(new TreeSet<>(), null, true, null);
      case CoreModel.Call c -> call(c, env);
      case CoreModel.IfE i -> {
        Val t = eval(i.thenE, env);
        Val e = eval(i.elseE, env);
        SortedSet<String> pii = new TreeSet<>(t.pii);
        pii.addAll(e.pii);
        yield new Val(pii, null, t.text && e.text, null);
      }
      case null, default -> Val.CLEAN;
    };
  }

  private Val call(CoreModel.Call c, Map<String, Val> env) {
    if (!(c.target instanceof CoreModel.Name tn) || !CONCAT.contains(tn.name)) return Val.CLEAN;
    SortedSet<String> pii = new TreeSet<>();
    boolean text = "Text.concat".equals(tn.name);
    for (CoreModel.Expr arg : c.args == null ? List.<CoreModel.Expr>of() : c.args) {
      Val v = eval(arg, env);
      pii.addAll(v.pii);
      text |= v.text;
    }
    return new Val(pii, null, text, null);
  }

  /** 变量引用与点分字段访问：首段是局部名时逐段选字段；否则（函数名、枚举成员、限定名）不带来源。 */
  private Val name(String name, Map<String, Val> env) {
    if (name == null) return Val.CLEAN;
    if (env.containsKey(name)) return env.get(name);
    int dot = name.indexOf('.');
    if (dot < 0 || !env.containsKey(name.substring(0, dot))) return Val.CLEAN;
    Val v = env.get(name.substring(0, dot));
    for (String seg : name.substring(dot + 1).split("\\.", -1)) {
      v = select(v, seg);
    }
    return v;
  }

  private Val select(Val v, String seg) {
    String path = v.path == null ? null : v.path + "." + seg;
    CoreModel.Field field = fieldOf(v.type, seg);
    CoreModel.Type type = field == null || field.typeInferred ? null : field.type;
    SortedSet<String> pii = new TreeSet<>(v.pii);
    if (path != null && isPii(type)) pii.add(path);
    return new Val(pii, type, isText(type), path);
  }

  /** 解包后为模块内记录类型时按名取字段；否则 null。 */
  private CoreModel.Field fieldOf(CoreModel.Type type, String seg) {
    if (!(unwrap(type) instanceof CoreModel.TypeName tn)) return null;
    CoreModel.Data data = records.get(tn.name);
    if (data == null || data.fields == null) return null;
    return data.fields.stream().filter(f -> f != null && seg.equals(f.name)).findFirst().orElse(null);
  }

  // ---- 类型 ----

  /** 去掉 PiiType / Option / Maybe 外壳。 */
  private static CoreModel.Type unwrap(CoreModel.Type type) {
    CoreModel.Type t = type;
    while (true) {
      switch (t) {
        case CoreModel.PiiType p -> t = p.baseType;
        case CoreModel.Option o -> t = o.type;
        case CoreModel.Maybe m -> t = m.type;
        case null, default -> {
          return t;
        }
      }
    }
  }

  /** 外壳中任一层是 PiiType。 */
  private static boolean isPii(CoreModel.Type type) {
    CoreModel.Type t = type;
    while (true) {
      switch (t) {
        case CoreModel.PiiType ignored -> {
          return true;
        }
        case CoreModel.Option o -> t = o.type;
        case CoreModel.Maybe m -> t = m.type;
        case null, default -> {
          return false;
        }
      }
    }
  }

  private static boolean isText(CoreModel.Type type) {
    return unwrap(type) instanceof CoreModel.TypeName tn && TEXT.equals(tn.name);
  }
}
