package aster.core.typecheck.governance;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Verdict 内置模块的签名表（ADR 0039 §2.2）。
 * 两引擎共享同一份事实：构造函数名、参数个数、参数全部为 Text、返回 Verdict。
 * TS 侧镜像：aster-lang-ts/src/typecheck/verdict_signatures.ts。
 */
public final class VerdictSignatures {
  private VerdictSignatures() {}

  public static final String TYPE_NAME = "Verdict";
  public static final String PREFIX = "Verdict.";

  private static final Map<String, Integer> ARITY = Map.of(
      "Verdict.allow", 0,
      "Verdict.deny", 1,
      "Verdict.require_approval", 2,
      "Verdict.escalate", 1);

  public static boolean isVerdictCall(String qualifiedName) {
    return qualifiedName != null && ARITY.containsKey(qualifiedName);
  }

  public static boolean hasVerdictPrefix(String qualifiedName) {
    return qualifiedName != null && qualifiedName.startsWith(PREFIX);
  }

  public static OptionalInt arityOf(String qualifiedName) {
    Integer n = ARITY.get(qualifiedName);
    return n == null ? OptionalInt.empty() : OptionalInt.of(n);
  }
}
