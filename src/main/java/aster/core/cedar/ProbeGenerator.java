package aster.core.cedar;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按规则事实确定性地生成等价性探针（ADR 0047 §5）。
 *
 * <p>维度按固定声明顺序：action、roles、principal_type、resource_type、principal_id、resource_id、agent_model，
 * 之后每个 context 键（按 {@link RuleFacts#contextTypes()} 的插入顺序）。组合序号为混合进制，第一个维度为最高位。
 * 各维度大小之积不超过 max 时取全笛卡尔积；否则先取「基准探针 + 逐一改变单个维度」，再以固定步长
 * {@code ceil(total / (max - 已取数))} 按序号补足。全程只用插入有序的集合，按值去重，跨运行与 JVM 顺序一致。
 */
public final class ProbeGenerator {

  /** 规则未提及的取值。 */
  public static final String OTHER = "__aster_other__";
  /** 刚越出 ±(2^53−1) 的数值，用于触发范围守卫。 */
  public static final long OUT_OF_DOMAIN = Probe.SAFE_MAX + 1;

  // context 键缺失的哨兵；只在维度内部使用，不会进入 Probe
  private static final Object MISSING = new Object() {
    @Override public String toString() { return "<missing>"; }
  };
  // context 维度之前的固定维度数
  private static final int FIXED_DIMS = 7;

  // 一个维度：去重后的候选值与基准取值下标
  private record Dim(String name, List<Object> values, int base) {
    Object at(int i) { return values.get(i); }
  }

  private ProbeGenerator() {}

  public static List<Probe> generate(RuleFacts facts, String fallbackAction, int max) {
    if (max < 1) throw new IllegalArgumentException("probe cap must be positive: " + max);
    List<Dim> dims = dimensions(facts, fallbackAction);
    BigInteger total = dims.stream().map(d -> BigInteger.valueOf(d.values().size()))
        .reduce(BigInteger.ONE, BigInteger::multiply);
    Set<Probe> out = new LinkedHashSet<>();
    if (total.compareTo(BigInteger.valueOf(max)) <= 0) {
      addStrided(dims, total, BigInteger.ONE, out, max);
      return List.copyOf(out);
    }
    addBaseAndSingles(dims, out, max);
    int remaining = max - out.size();
    if (remaining > 0) addStrided(dims, total, ceilDiv(total, BigInteger.valueOf(remaining)), out, max);
    return List.copyOf(out);
  }

  // ---------- 维度 ----------

  private static List<Dim> dimensions(RuleFacts facts, String fallbackAction) {
    List<Dim> dims = new ArrayList<>();
    dims.add(textDim("action", facts.actions(), fallbackAction));
    dims.add(rolesDim(facts.roles()));
    dims.add(textDim("principal_type", facts.principalTypes(), "agent"));
    dims.add(textDim("resource_type", facts.resourceTypes(), "res"));
    dims.add(textDim("principal_id", facts.principalIds(), "p-probe"));
    dims.add(textDim("resource_id", facts.resourceIds(), "r-probe"));
    dims.add(agentModelDim(facts));
    Map<String, List<Object>> literals = facts.contextLiterals();
    facts.contextTypes().forEach((key, type) -> dims.add(contextDim(key, type, literals.getOrDefault(key, List.of()))));
    return dims;
  }

  // 提及值 + 未提及值；无提及时固定为单一默认值
  private static Dim textDim(String name, List<String> mentioned, String fallback) {
    if (mentioned.isEmpty()) return dim(name, List.of(fallback), fallback);
    List<Object> values = new ArrayList<>(mentioned);
    values.add(OTHER);
    return dim(name, values, mentioned.get(0));
  }

  // 空集、每个角色单独、全部角色
  private static Dim rolesDim(List<String> roles) {
    List<Object> values = new ArrayList<>();
    values.add(List.of());
    roles.forEach(r -> values.add(List.of(r)));
    if (!roles.isEmpty()) values.add(List.copyOf(roles));
    return dim("principal_roles", values, List.of());
  }

  // 规则未读取 agent_model 时恒为缺失（null）
  private static Dim agentModelDim(RuleFacts facts) {
    if (!facts.agentModelUsed()) return dim("agent_model", Collections.singletonList(null), null);
    List<Object> values = new ArrayList<>();
    values.add(null);
    values.addAll(facts.agentModels());
    values.add(OTHER);
    return dim("agent_model", values, facts.agentModels().isEmpty() ? OTHER : facts.agentModels().get(0));
  }

  private static Dim contextDim(String key, ContextType type, List<Object> literals) {
    List<Object> values = new ArrayList<>();
    values.add(MISSING);
    Object base = switch (type) {
      case LONG -> longValues(literals, values);
      case STRING -> stringValues(literals, values);
      case BOOL -> boolValues(literals, values);
    };
    return dim(key, values, base);
  }

  // 每个字面量取 n-1、n、n+1（越出 ±(2^53−1) 的邻值省略），末尾追加 2^53；无字面量时以 0 作为存在值
  private static Object longValues(List<Object> literals, List<Object> values) {
    List<Long> numbers = literals.stream().map(l -> ((Number) l).longValue()).toList();
    if (numbers.isEmpty()) values.add(0L);
    for (long n : numbers) {
      for (long d = -1; d <= 1; d++) addInDomain(values, n + d);
    }
    values.add(OUT_OF_DOMAIN);
    return numbers.isEmpty() ? 0L : numbers.get(0);
  }

  private static void addInDomain(List<Object> values, long n) {
    if (n >= -Probe.SAFE_MAX && n <= Probe.SAFE_MAX) values.add(n);
  }

  private static Object stringValues(List<Object> literals, List<Object> values) {
    values.addAll(literals);
    values.add(OTHER);
    return literals.isEmpty() ? OTHER : literals.get(0);
  }

  private static Object boolValues(List<Object> literals, List<Object> values) {
    values.add(true);
    values.add(false);
    return literals.isEmpty() ? true : literals.get(0);
  }

  // 按值去重（保持首次出现顺序）并定位基准值；Stream.toList 不可变且允许 null（缺失的 agent_model）
  private static Dim dim(String name, List<Object> values, Object base) {
    List<Object> distinct = values.stream().distinct().toList();
    return new Dim(name, distinct, Math.max(0, distinct.indexOf(base)));
  }

  // ---------- 组合 ----------

  // 基准探针，再逐一把单个维度换成其余每个取值
  private static void addBaseAndSingles(List<Dim> dims, Set<Probe> out, int max) {
    int[] base = dims.stream().mapToInt(Dim::base).toArray();
    add(out, max, probe(dims, base));
    for (int i = 0; i < dims.size(); i++) {
      for (int j = 0; j < dims.get(i).values().size(); j++) {
        int[] idx = base.clone();
        idx[i] = j;
        add(out, max, probe(dims, idx));
      }
    }
  }

  // 按混合进制序号 0, step, 2·step, … 依次取探针，直到序号越过 total 或达到上限
  private static void addStrided(List<Dim> dims, BigInteger total, BigInteger step, Set<Probe> out, int max) {
    for (BigInteger k = BigInteger.ZERO; k.compareTo(total) < 0 && out.size() < max; k = k.add(step)) {
      out.add(probe(dims, digits(dims, k)));
    }
  }

  private static void add(Set<Probe> out, int max, Probe probe) {
    if (out.size() < max) out.add(probe);
  }

  // 序号 → 各维度下标；第一个维度为最高位
  private static int[] digits(List<Dim> dims, BigInteger ordinal) {
    int[] idx = new int[dims.size()];
    BigInteger rest = ordinal;
    for (int i = dims.size() - 1; i >= 0; i--) {
      BigInteger[] qr = rest.divideAndRemainder(BigInteger.valueOf(dims.get(i).values().size()));
      idx[i] = qr[1].intValueExact();
      rest = qr[0];
    }
    return idx;
  }

  private static BigInteger ceilDiv(BigInteger a, BigInteger b) {
    BigInteger[] qr = a.divideAndRemainder(b);
    return qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
  }

  @SuppressWarnings("unchecked")
  private static Probe probe(List<Dim> dims, int[] idx) {
    Map<String, Object> context = new LinkedHashMap<>();
    for (int i = FIXED_DIMS; i < dims.size(); i++) {
      Object v = dims.get(i).at(idx[i]);
      if (v != MISSING) context.put(dims.get(i).name(), v);
    }
    return new Probe(
        (String) dims.get(4).at(idx[4]),
        (String) dims.get(2).at(idx[2]),
        (List<String>) dims.get(1).at(idx[1]),
        (String) dims.get(0).at(idx[0]),
        (String) dims.get(3).at(idx[3]),
        (String) dims.get(5).at(idx[5]),
        context,
        (String) dims.get(6).at(idx[6]));
  }
}
