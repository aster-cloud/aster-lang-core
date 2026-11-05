package aster.core.typecheck;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 调用位置上由运行期直接分派的单段 builtin 函数名。
 * <p>
 * truffle {@code Loader} 对单段调用目标的判定是 {@code Builtins.has(name) && !userFunctionNames.contains(name)}：
 * 命中即调用 builtin，不看同名局部变量。所以 {@code given add as Int … add(1, 2)} 能正常运行，
 * 类型检查不能把它当作「调用非函数局部名」（#268 E001）报错。
 * <p>
 * 本表镜像 aster-lang-truffle {@code aster.truffle.runtime.Builtins} 中不带点的注册名；
 * truffle {@code Builtins.has} 先经 {@code canonicalName} 归一：单词别名（equals / is / over / under / divide …）
 * 转小写后映射到规范名，所以别名不区分大小写，规范名本身区分大小写（#234）。
 * {@code CallTargetBuiltinsTruffleSyncTest} 在能找到 truffle 源码时逐项核对两张表。TS 侧
 * （aster-lang-ts {@code NAMED_BUILTIN_OPS} / {@code CALL_TARGET_BUILTIN_ALIASES}）使用同一份名单。
 */
public final class CallTargetBuiltins {

  /** 单段 builtin 名（与 truffle Builtins 同步）。 */
  public static final Set<String> NAMES = Set.of(
    "add", "sub", "mul", "div", "intdiv", "mod",
    "eq", "ne", "lt", "lte", "gt", "gte",
    "and", "or", "not", "await"
  );

  /** truffle {@code canonicalName} 中的单词别名 → 规范名（只列单个单词、可作调用目标名的别名）。 */
  public static final Map<String, String> ALIASES = Map.of(
    "plus", "add", "minus", "sub", "times", "mul", "divide", "div", "modulo", "mod",
    "equals", "eq", "is", "eq", "under", "lt", "over", "gt"
  );

  /** 调用目标名在运行期是否按单段 builtin 分派：规范名区分大小写，单词别名不区分大小写。 */
  public static boolean isCallTarget(String name) {
    return name != null && (NAMES.contains(name) || ALIASES.containsKey(name.toLowerCase(Locale.ROOT)));
  }

  private CallTargetBuiltins() {
  }
}
