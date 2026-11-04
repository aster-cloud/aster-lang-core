package aster.core.typecheck;

import java.util.Set;

/**
 * 调用位置上由运行期直接分派的单段 builtin 函数名。
 * <p>
 * truffle {@code Loader} 对单段调用目标的判定是 {@code Builtins.has(name) && !userFunctionNames.contains(name)}：
 * 命中即调用 builtin，不看同名局部变量。所以 {@code given add as Int … add(1, 2)} 能正常运行，
 * 类型检查不能把它当作「调用非函数局部名」（#268 E001）报错。
 * <p>
 * 本表镜像 aster-lang-truffle {@code aster.truffle.runtime.Builtins} 中不带点的注册名；
 * {@code CallTargetBuiltinsTruffleSyncTest} 在能找到 truffle 源码时逐项核对。TS 侧
 * （aster-lang-ts {@code mayBeCallable}）使用同一份名单。
 */
public final class CallTargetBuiltins {

  /** 单段 builtin 名（与 truffle Builtins 同步）。 */
  public static final Set<String> NAMES = Set.of(
    "add", "sub", "mul", "div", "intdiv", "mod",
    "eq", "ne", "lt", "lte", "gt", "gte",
    "and", "or", "not", "await"
  );

  private CallTargetBuiltins() {
  }
}
