package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * 构造器字段校验（2026-08-17 审计修复）。
 *
 * <p>此前 {@code BaseTypeChecker.checkConstruct} 是空壳——注释自述「简化实现」
 * 「完整实现需要查找 data 声明并验证字段」，直接返回类型名。后果是
 * {@code FIELD_TYPE_MISMATCH} / {@code UNKNOWN_FIELD} /
 * {@code MISSING_REQUIRED_FIELD} 三个错误码在 Java 侧的 emit 站点数为 <b>0</b>，
 * 而 TS 侧（typecheck/expression.ts 的 Construct 分支）三者全部实现。
 *
 * <p>同一段 CNL：TS 报 3 个诊断，Java <b>静默接受</b>。
 * 对合规引擎而言「未覆盖的决策分支不告警」本身就是缺陷；更麻烦的是错误码表
 * 两侧 byte-identical，制造了「表对齐 = 行为对齐」的假象——表对齐了，
 * 而表背后的行为一侧根本不存在。
 */
class ConstructFieldCheckTest {

  private java.util.List<ErrorCode> codesOf(String body) {
    String src = "Module probe.\n\n" + body + "\n";
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var ast = new AstBuilder().visitModule(parser.module());
    var core = new CoreLowering().lowerModule(ast);
    return new TypeChecker().typecheckModule(core).stream().map(d -> d.code()).toList();
  }

  private boolean has(String body, ErrorCode code) {
    return codesOf(body).contains(code);
  }

  private static final String DECL =
      "Define Applicant with name as Text, age as Int.\n\n";

  @Test
  void unknownFieldIsReported() {
    // 写了 data 声明里没有的字段
    assertTrue(
        has(DECL + "Rule main produce Applicant:\n"
            + "  Return Applicant with name set to \"a\", age set to 30, bogus set to 1.",
            ErrorCode.UNKNOWN_FIELD),
        "构造器里的未知字段必须报 UNKNOWN_FIELD——此前 Java 侧静默接受，而 TS 侧报错");
  }

  @Test
  void missingRequiredFieldIsReported() {
    // 少写了 age
    assertTrue(
        has(DECL + "Rule main produce Applicant:\n"
            + "  Return Applicant with name set to \"a\".",
            ErrorCode.MISSING_REQUIRED_FIELD),
        "缺失字段必须报 MISSING_REQUIRED_FIELD");
  }

  @Test
  void fieldTypeMismatchIsReported() {
    // name 声明为 Text，却给了 Int
    assertTrue(
        has(DECL + "Rule main produce Applicant:\n"
            + "  Return Applicant with name set to 42, age set to 30.",
            ErrorCode.FIELD_TYPE_MISMATCH),
        "字段类型不匹配必须报 FIELD_TYPE_MISMATCH");
  }

  @Test
  void wellFormedConstructIsClean() {
    // ★同等重要的一半：正确的构造器**不得**产生这三种诊断。
    //   否则「把校验做成一律报错」也能让上面三条通过，那是假修复。
    var codes = codesOf(DECL + "Rule main produce Applicant:\n"
        + "  Return Applicant with name set to \"alice\", age set to 30.");
    assertFalse(codes.contains(ErrorCode.UNKNOWN_FIELD), "合法构造器不应报未知字段");
    assertFalse(codes.contains(ErrorCode.MISSING_REQUIRED_FIELD), "字段齐全时不应报缺失");
    assertFalse(codes.contains(ErrorCode.FIELD_TYPE_MISMATCH),
        "类型正确时不应报不匹配，实际诊断=" + codes);
  }

  /**
   * ★「找不到 data 声明时不产生字段级噪声」这条分支**没有**用例覆盖——如实说明原因。
   *
   * <p>实现里确实有该分支（{@code decl == null} 时直接返回类型名，与 TS 一致），
   * 但构造一个未声明类型的源码**根本过不了 parser**：
   * {@code AstBuilder.visitModule} 在这种输入上抛 NPE（本地与 CI 均复现）。
   * 那是一个与本次改动无关的既有解析器缺陷，不应在本用例里顺带触发——
   * 否则这条用例测的是 parser 崩溃，而不是我要锁住的行为。
   *
   * <p>该分支目前由「与 TS 逐条对齐」保证，未被自动化覆盖，属已知缺口。
   */
}
