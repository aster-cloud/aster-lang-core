package aster.core.cedar;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 把规则中的表达式翻译为 Cedar 文本（ADR 0047 §3、§4.3）。
 * 条件翻译为 {@link Cond}（T = 无错且为真，F = 无错且为假）；读 context 键的原子都在同一合取中先经 {@code has} 守卫。
 * 同一实例只服务一个规则的一遍遍历：{@code inferOnly} 为类型推断遍，翻译遍依赖推断遍写入 {@link RuleFacts} 的键类型。
 * 不可翻译的构造写一条 E721 并返回 {@link Term#ERROR}，外层遇到 ERROR 静默传播，保证每处问题只报一次。
 */
final class ConditionTranslator {

  /** 中间值种类。 */
  enum Kind { LONG_LIT, STRING_LIT, BOOL_LIT, ARITH, CONTEXT_KEY, GUARD_FIELD, COND, ERROR }

  /**
   * 翻译中间值：{@code text} 为 Cedar 文本（COND 时为 null）；{@code keys} 为读取的 context 键（首次出现顺序）；
   * {@code literal} 为字面量值；{@code field} 为 guard 字段名或 context 键名；
   * {@code multiplies} 仅对 ARITH 有意义：算术项中含乘法（探针需额外取 1）。
   */
  record Term(Kind kind, String text, List<String> keys, Object literal, String field, Cond cond, boolean multiplies) {
    static final Term ERROR = new Term(Kind.ERROR, null, List.of(), null, null, null);

    Term(Kind kind, String text, List<String> keys, Object literal, String field, Cond cond) {
      this(kind, text, keys, literal, field, cond, false);
    }

    boolean failed() {
      return kind == Kind.ERROR;
    }
  }

  // 与文本字面量比较、可翻译为 Cedar 相等判断的 guard 字段
  private static final Set<String> TEXT_FIELDS = Set.of(
      "action", "principal_id", "resource_id", "principal_type", "resource_type", "agent_model");
  // 2^53：Aster 数值比较经 double，只有 |v| < 2^53 的整数能精确表示
  private static final long SAFE_LIMIT = 9_007_199_254_740_992L;
  private static final String SAFE_MAX = Long.toString(SAFE_LIMIT - 1);
  private static final Pattern IDENT = Pattern.compile("[_a-zA-Z][_a-zA-Z0-9]*");
  private static final Set<String> RESERVED = Set.of(
      "true", "false", "if", "then", "else", "in", "like", "has", "is", "__cedar");

  private final String param;
  private final String rule;
  private final RuleFacts facts;
  private final List<Diagnostic> errors;
  private final List<Diagnostic> typeErrors;
  private final boolean inferOnly;
  private final CoreModel.Origin fallback;
  private CoreModel.Origin origin;

  /**
   * @param errors     E721 的去处（推断遍传入丢弃列表）
   * @param typeErrors E722 的去处（两遍共用，RuleFacts 保证每键只报一次）
   * @param fallback   语句无位置时使用的函数位置
   */
  ConditionTranslator(String param, String rule, RuleFacts facts, List<Diagnostic> errors,
                      List<Diagnostic> typeErrors, boolean inferOnly, CoreModel.Origin fallback) {
    this.param = param;
    this.rule = rule;
    this.facts = facts;
    this.errors = errors;
    this.typeErrors = typeErrors;
    this.inferOnly = inferOnly;
    this.fallback = fallback;
    this.origin = fallback;
  }

  /** 单个条件的两遍翻译（先推断类型再翻译），失败时返回 null；供单元测试使用。 */
  static Cond translateAlone(String param, String rule, CoreModel.Expr e, RuleFacts facts, List<Diagnostic> out) {
    new ConditionTranslator(param, rule, facts, new ArrayList<>(), out, true, null).cond(e, Map.of());
    return new ConditionTranslator(param, rule, facts, out, out, false, null).cond(e, Map.of()).orElse(null);
  }

  /** 设置后续诊断所指的语句位置。 */
  void at(CoreModel.Origin statementOrigin) {
    this.origin = statementOrigin != null ? statementOrigin : fallback;
  }

  /** 报告一条 E721。 */
  void unsupported(String construct) {
    errors.add(diag(ErrorCode.CEDAR_UNSUPPORTED_CONSTRUCT, Map.of("rule", rule, "construct", construct)));
  }

  /** 把表达式翻译为条件；失败（已报错）时返回 empty。 */
  Optional<Cond> cond(CoreModel.Expr e, Map<String, Term> env) {
    return toCond(term(e, env));
  }

  /**
   * Let 在其位置要求的「已定义」条件：条件取 {@code (T || F)}；算术项取全部键的 {@code has} 加该项的范围守卫，
   * 迫使 Cedar 求值该项（Aster 在 Let 处对非数值操作数抛错，Cedar 须同样落空）；裸键只取 {@code has}；
   * 不读 context 键（或已失败）时为 empty。
   */
  Optional<String> definedness(Term t) {
    if (t.failed() || t.keys().isEmpty()) return Optional.empty();
    if (t.kind() == Kind.COND) return Optional.of("(" + t.cond().t() + " || " + t.cond().f() + ")");
    if (t.kind() == Kind.ARITH) return Optional.of("(" + hasAll(t.keys()) + " && " + rangeGuard(t) + ")");
    return Optional.of("(" + hasAll(t.keys()) + ")");
  }

  /** 翻译任意表达式为中间值。 */
  Term term(CoreModel.Expr e, Map<String, Term> env) {
    return switch (e) {
      case CoreModel.Name n -> name(n.name, env);
      case CoreModel.IntE i -> integer(i.value);
      case CoreModel.LongE l -> integer(l.value);
      case CoreModel.StringE s -> literal(Kind.STRING_LIT, CedarText.quote(s.value), s.value);
      case CoreModel.Bool b -> literal(Kind.BOOL_LIT, Boolean.toString(b.value), b.value);
      case CoreModel.Call c -> call(c, env);
      case CoreModel.IfE x -> reject("if-expression");
      case CoreModel.Lambda x -> reject("lambda");
      case CoreModel.DecimalE x -> reject("decimal value");
      case CoreModel.DoubleE x -> reject("decimal value");
      case null -> reject("missing expression");
      default -> reject("expression of kind '" + e.getClass().getSimpleName() + "'");
    };
  }

  // Aster 比较经 double，超出 ±(2^53−1) 的整数字面量无法与 Cedar 的精确 Long 比较保持一致
  private Term integer(long value) {
    if (value >= SAFE_LIMIT || value <= -SAFE_LIMIT) return reject("integer literal outside \u00b12^53");
    return literal(Kind.LONG_LIT, Long.toString(value), value);
  }

  // ---------- 名称 ----------

  // 单段且为 Let 名 → 内联；首段为参数名 → guard 字段 / context 键；其余不可翻译
  private Term name(String full, Map<String, Term> env) {
    String[] seg = full.split("\\.", -1);
    if (seg.length == 1 && env.containsKey(full)) return env.get(full);
    boolean guardRef = seg.length >= 2 && seg[0].equals(param) && GuardShape.GUARD_FIELDS.contains(seg[1]);
    if (!guardRef) return reject("reference '" + full + "'");
    if (seg[1].equals("context")) return contextKey(full, seg);
    if (seg.length > 2) return reject("reference '" + full + "'");
    return new Term(Kind.GUARD_FIELD, null, List.of(), null, seg[1], null);
  }

  // 只支持一层 context 键
  private Term contextKey(String full, String[] seg) {
    if (seg.length == 2) return reject("reference '" + full + "'");
    String key = String.join(".", Arrays.copyOfRange(seg, 2, seg.length));
    if (seg.length > 3) return reject("nested context key '" + key + "'");
    facts.recordRead(key);
    return new Term(Kind.CONTEXT_KEY, contextRef(key), List.of(key), null, key, null);
  }

  // ---------- 调用（运算符与库调用） ----------

  private Term call(CoreModel.Call c, Map<String, Term> env) {
    if (!(c.target instanceof CoreModel.Name target)) return reject("call");
    String op = target.name;
    List<CoreModel.Expr> args = c.args == null ? List.of() : c.args;
    return switch (op) {
      case "==", "!=", "<", "<=", ">", ">=" -> binary(op, args, env, (l, r) -> compare(op, l, r));
      case "+", "-", "*" -> binary(op, args, env, (l, r) -> arith(op, l, r));
      case "and" -> binary(op, args, env, this::and);
      case "or" -> binary(op, args, env, this::or);
      case "not" -> args.size() == 1 ? not(term(args.get(0), env)) : reject("call to 'not'");
      case "/", "//", "%" -> reject("division");
      case "List.contains" -> contains(args, env);
      default -> reject("call to '" + op + "'");
    };
  }

  // 两侧都先翻译以收集全部错误；任一侧失败则静默传播
  private Term binary(String op, List<CoreModel.Expr> args, Map<String, Term> env, BinaryOperator<Term> f) {
    if (args.size() != 2) return reject("call to '" + op + "'");
    Term l = term(args.get(0), env);
    Term r = term(args.get(1), env);
    return l.failed() || r.failed() ? Term.ERROR : f.apply(l, r);
  }

  // List.contains 只放行 principal_roles 与文本字面量
  private Term contains(List<CoreModel.Expr> args, Map<String, Term> env) {
    if (args.size() != 2) return reject("call to 'List.contains'");
    Term list = term(args.get(0), env);
    Term item = term(args.get(1), env);
    if (list.failed() || item.failed()) return Term.ERROR;
    boolean roles = list.kind() == Kind.GUARD_FIELD && list.field().equals("principal_roles");
    if (!roles || item.kind() != Kind.STRING_LIT) return reject("call to 'List.contains'");
    facts.recordRole((String) item.literal());
    return always("principal.roles.contains(" + item.text() + ")");
  }

  // ---------- 组合（ADR 0047 §3 短路公式） ----------

  private Term and(Term l, Term r) {
    Optional<Cond> a = toCond(l);
    Optional<Cond> b = toCond(r);
    if (a.isEmpty() || b.isEmpty()) return Term.ERROR;
    Cond x = a.get();
    Cond y = b.get();
    Cond c = new Cond("(" + x.t() + " && " + y.t() + ")", "(" + x.f() + " || (" + x.t() + " && " + y.f() + "))");
    return condTerm(c, merge(l.keys(), r.keys()));
  }

  private Term or(Term l, Term r) {
    Optional<Cond> a = toCond(l);
    Optional<Cond> b = toCond(r);
    if (a.isEmpty() || b.isEmpty()) return Term.ERROR;
    Cond x = a.get();
    Cond y = b.get();
    Cond c = new Cond("(" + x.t() + " || (" + x.f() + " && " + y.t() + "))", "(" + x.f() + " && " + y.f() + ")");
    return condTerm(c, merge(l.keys(), r.keys()));
  }

  private Term not(Term t) {
    if (t.failed()) return Term.ERROR;
    return toCond(t).map(c -> condTerm(c.negate(), t.keys())).orElse(Term.ERROR);
  }

  // 中间值作条件：单独的 context 键视为 Bool 键
  private Optional<Cond> toCond(Term t) {
    return switch (t.kind()) {
      case COND -> Optional.of(t.cond());
      case BOOL_LIT -> Optional.of(Boolean.TRUE.equals(t.literal()) ? new Cond("true", "false") : new Cond("false", "true"));
      case CONTEXT_KEY -> Optional.of(boolKey(t));
      case ERROR -> Optional.empty();
      default -> {
        reject("non-boolean condition");
        yield Optional.empty();
      }
    };
  }

  private Cond boolKey(Term key) {
    typeKey(key.field(), ContextType.BOOL);
    String has = has(key.field());
    return new Cond("(" + has + " && " + key.text() + ")", "(" + has + " && !" + key.text() + ")");
  }

  // ---------- 比较 ----------

  private Term compare(String op, Term l, Term r) {
    if (l.kind() == Kind.GUARD_FIELD || r.kind() == Kind.GUARD_FIELD) return guardCompare(op, l, r);
    if (l.kind() == Kind.COND || r.kind() == Kind.COND) return reject("comparison of conditions");
    Optional<ContextType> type = comparedType(l, r);
    if (type.isEmpty()) return Term.ERROR;
    boolean equality = op.equals("==") || op.equals("!=");
    if (!equality && type.get() == ContextType.STRING) return reject("ordering on text");
    if (!equality && type.get() == ContextType.BOOL) return reject("ordering on boolean");
    recordLiteral(l, r);
    recordLiteral(r, l);
    List<String> keys = merge(l.keys(), r.keys());
    List<String> ranges = type.get() == ContextType.LONG ? rangeGuards(l, r) : List.of();
    return condTerm(guarded(keys, ranges, l.text() + " " + op + " " + r.text()), keys);
  }

  // 比较两侧的共同类型：字面量/算术项自带类型并传给另一侧的键；两个裸键须在推断遍后类型均已知且相同
  private Optional<ContextType> comparedType(Term l, Term r) {
    Optional<ContextType> lt = intrinsic(l);
    Optional<ContextType> rt = intrinsic(r);
    if (lt.isPresent() && rt.isPresent()) {
      return lt.equals(rt) ? lt : rejectType("comparison between mismatched types");
    }
    if (lt.isPresent() || rt.isPresent()) {
      ContextType type = lt.orElseGet(rt::get);
      typeKey((lt.isPresent() ? r : l).field(), type);
      return Optional.of(type);
    }
    if (inferOnly) return Optional.empty();
    Optional<ContextType> a = facts.typeOf(l.field());
    boolean same = a.isPresent() && a.equals(facts.typeOf(r.field()));
    return same ? a : rejectType("comparison between untyped context keys");
  }

  private static Optional<ContextType> intrinsic(Term t) {
    return switch (t.kind()) {
      case LONG_LIT, ARITH -> Optional.of(ContextType.LONG);
      case STRING_LIT -> Optional.of(ContextType.STRING);
      case BOOL_LIT -> Optional.of(ContextType.BOOL);
      default -> Optional.empty();
    };
  }

  // guard 字段只与文本字面量做 ==/!=；这些原子永远有定义，F = !(T)
  private Term guardCompare(String op, Term l, Term r) {
    Term field = l.kind() == Kind.GUARD_FIELD ? l : r;
    Term other = field == l ? r : l;
    String ref = param + "." + field.field();
    if (!TEXT_FIELDS.contains(field.field())) return reject("comparison on '" + ref + "'");
    if (!op.equals("==") && !op.equals("!=")) return reject("ordering on text");
    if (other.kind() != Kind.STRING_LIT) return reject("comparison of '" + ref + "' with a non-literal");
    String value = (String) other.literal();
    facts.recordGuardLiteral(field.field(), value);
    Term atom = always(guardAtom(field.field(), value));
    return op.equals("==") ? atom : condTerm(atom.cond().negate(), List.of());
  }

  private static String guardAtom(String field, String value) {
    return switch (field) {
      case "action" -> "action == " + CedarText.entity("Action", value);
      case "principal_id" -> "principal == " + CedarText.entity("Principal", value);
      case "resource_id" -> "resource == " + CedarText.entity("Resource", value);
      case "principal_type" -> "principal.type == " + CedarText.quote(value);
      case "resource_type" -> "resource.type == " + CedarText.quote(value);
      default -> "context has agent_model && context.agent_model == " + CedarText.quote(value);
    };
  }

  // ---------- 算术 ----------

  // 只允许 + - * 作用于整数字面量、context 键与算术项；参与运算的键推断为 Long
  private Term arith(String op, Term l, Term r) {
    if (!numeric(l) || !numeric(r)) return reject("arithmetic on non-integer operand");
    Stream.of(l, r).filter(t -> t.kind() == Kind.CONTEXT_KEY).forEach(t -> typeKey(t.field(), ContextType.LONG));
    String text = "(" + l.text() + " " + op + " " + r.text() + ")";
    boolean multiplies = op.equals("*") || l.multiplies() || r.multiplies();
    return new Term(Kind.ARITH, text, merge(l.keys(), r.keys()), null, null, null, multiplies);
  }

  private static boolean numeric(Term t) {
    return t.kind() == Kind.LONG_LIT || t.kind() == Kind.ARITH || t.kind() == Kind.CONTEXT_KEY;
  }

  // ---------- 文本构造 ----------

  // Long 比较中非字面量的操作数（含只由字面量组成的算术项）须落在 ±(2^53−1) 内，否则 T、F 都不成立 → 无策略适用 → Cedar 拒绝（只会比 Aster 更严）
  private static List<String> rangeGuards(Term l, Term r) {
    return Stream.of(l, r)
        .filter(t -> t.kind() == Kind.CONTEXT_KEY || t.kind() == Kind.ARITH)
        .map(ConditionTranslator::rangeGuard)
        .toList();
  }

  private static String rangeGuard(Term t) {
    return "(-" + SAFE_MAX + " <= " + t.text() + " && " + t.text() + " <= " + SAFE_MAX + ")";
  }

  // T = (has… && 范围… && (e))，F = (has… && 范围… && !(e))；既不读键也无范围守卫时退化为永远有定义的原子
  private static Cond guarded(List<String> keys, List<String> ranges, String expr) {
    List<String> parts = Stream.concat(keys.stream().map(ConditionTranslator::has), ranges.stream()).toList();
    if (parts.isEmpty()) return new Cond("(" + expr + ")", "!(" + expr + ")");
    String prefix = String.join(" && ", parts) + " && ";
    return new Cond("(" + prefix + "(" + expr + "))", "(" + prefix + "!(" + expr + "))");
  }

  private static Term always(String atom) {
    return condTerm(new Cond("(" + atom + ")", "!(" + atom + ")"), List.of());
  }

  private static Term condTerm(Cond c, List<String> keys) {
    return new Term(Kind.COND, null, keys, null, null, c);
  }

  private static Term literal(Kind kind, String text, Object value) {
    return new Term(kind, text, List.of(), value, null, null);
  }

  private static String hasAll(List<String> keys) {
    return keys.stream().map(ConditionTranslator::has).collect(Collectors.joining(" && "));
  }

  // 合法且非保留的标识符用点号访问，否则用字符串下标
  private static String has(String key) {
    return "context has " + (plainIdent(key) ? key : CedarText.quote(key));
  }

  private static String contextRef(String key) {
    return plainIdent(key) ? "context." + key : "context[" + CedarText.quote(key) + "]";
  }

  private static boolean plainIdent(String key) {
    return IDENT.matcher(key).matches() && !RESERVED.contains(key);
  }

  private static List<String> merge(List<String> a, List<String> b) {
    Set<String> out = new LinkedHashSet<>(a);
    out.addAll(b);
    return List.copyOf(out);
  }

  // ---------- 事实与诊断 ----------

  private void recordLiteral(Term key, Term value) {
    boolean isLiteral = value.kind() == Kind.LONG_LIT || value.kind() == Kind.STRING_LIT || value.kind() == Kind.BOOL_LIT;
    if (key.kind() == Kind.CONTEXT_KEY && isLiteral) facts.recordLiteral(key.field(), value.literal());
    if (key.kind() == Kind.ARITH && value.kind() == Kind.LONG_LIT) recordArithLiterals(key, value.literal());
  }

  // 算术项与字面量 c 比较：项内每个键记录 0、c（含乘法再记 1）。0 在前使基准探针把其余操作数固定为 0，
  // 逐一改变单维时该键走过 c−1、c、c+1，从而让整个算术项跨越边界
  private void recordArithLiterals(Term arith, Object c) {
    for (String key : arith.keys()) {
      facts.recordLiteral(key, 0L);
      facts.recordLiteral(key, c);
      if (arith.multiplies()) facts.recordLiteral(key, 1L);
    }
  }

  private void typeKey(String key, ContextType type) {
    facts.recordType(key, type).ifPresent(first -> typeErrors.add(diag(ErrorCode.CEDAR_UNTYPED_CONTEXT_KEY,
        Map.of("key", key, "rule", rule, "first", label(first), "second", label(type)))));
  }

  private static String label(ContextType type) {
    return switch (type) {
      case LONG -> "number";
      case STRING -> "text";
      case BOOL -> "boolean";
    };
  }

  private Term reject(String construct) {
    unsupported(construct);
    return Term.ERROR;
  }

  private Optional<ContextType> rejectType(String construct) {
    unsupported(construct);
    return Optional.empty();
  }

  private Diagnostic diag(ErrorCode code, Map<String, Object> data) {
    return new Diagnostic(Diagnostic.Severity.ERROR, code, code.render(data), Optional.ofNullable(origin),
        Optional.ofNullable(code.help()), Map.copyOf(data));
  }
}
