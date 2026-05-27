package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.typecheck.model.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 端 PII always-on conformance test (P0-1 / ADR-0009).
 * <p>
 * 验证 {@link TypeChecker#typecheckModule(CoreModel.Module)} 永远跑 PII 流分析，
 * 不再依赖 {@code ENFORCE_PII} / {@code ASTER_ENFORCE_PII} 环境变量。
 * <p>
 * 与 TS 端 aster-lang-ts/test/unit/typecheck/pii-cross-runtime-conformance.test.ts
 * 形成跨语言镜像合同：两端 typecheck 入口对同一 PII 违规场景都必须报告 PII
 * 诊断。注意：两端独立实现，byte-identical 字节等价不在本 conformance 范围
 * （那是 P1 单 parser 真源议题）。
 * <p>
 * 本测试**不读** TS 端实现细节、**不依赖** byte-identical 输出。只保证：
 * Java 端 TypeChecker 在没有 env 设置时仍然产生 PII 诊断（P0-1 之前会静默禁用）。
 */
@DisplayName("PII always-on conformance (Java 端 / ADR-0009 P0-1)")
class PiiAlwaysOnConformanceTest {

  private TypeChecker typeChecker;

  @BeforeEach
  void setUp() {
    typeChecker = new TypeChecker();
  }

  @Test
  @DisplayName("无 ENFORCE_PII env var 时 PII 检查仍然运行：assign downgrade 场景")
  void piiChecksRunWhenEnvUnset_assignDowngrade() {
    // 测试场景：L2 email → 普通 Text 变量（这是 PII_ASSIGN_DOWNGRADE 违规）
    var fn = func(
      "assign_downgrade_test",
      List.of(piiParam("email", "L2", "email")),
      textType(),
      List.of(
        letStmt("plain", stringLiteral("safe")),
        setStmt("plain", nameExpr("email")),
        returnStmt(nameExpr("plain"))
      ),
      List.of()
    );
    var module = moduleOf("tests.pii.always_on.downgrade", List.of(fn));

    var diagnostics = typeChecker.typecheckModule(module);

    // P0-1 之前：env 未设 → shouldEnforcePii() 返回 false → 0 PII 诊断
    // P0-1 之后：env 未设也跑 PII → 至少有一个 PII_ASSIGN_DOWNGRADE
    assertTrue(
      hasCode(diagnostics, ErrorCode.PII_ASSIGN_DOWNGRADE),
      "PII 检查应永远启用（ADR-0009 P0-1），但未检测到 PII_ASSIGN_DOWNGRADE：" + diagnostics
    );
  }

  @Test
  @DisplayName("HTTP sink 场景：Java 端永远跑 sink 分析（无 env 依赖）")
  void piiSinkChecksRunUnconditionally() {
    // L2 PII → callee 期望 plain Text 参数 → PII_ARG_VIOLATION
    var callee = func(
      "handle_plain",
      List.of(plainParam("data")),
      textType(),
      List.of(returnStmt(nameExpr("data"))),
      List.of()
    );
    var caller = func(
      "leak_via_call",
      List.of(piiParam("email", "L2", "email")),
      textType(),
      List.of(returnStmt(callExpr("handle_plain", nameExpr("email")))),
      List.of()
    );
    var module = moduleOf("tests.pii.always_on.arg", List.of(callee, caller));

    var diagnostics = typeChecker.typecheckModule(module);

    assertTrue(
      hasCode(diagnostics, ErrorCode.PII_ARG_VIOLATION),
      "PII 参数检查应永远启用（ADR-0009 P0-1），但未检测到 PII_ARG_VIOLATION：" + diagnostics
    );
  }

  @Test
  @DisplayName("无 PII 代码：Java 端不应误报")
  void noPiiInBenignCode() {
    var fn = func(
      "no_pii",
      List.of(plainParam("plain_text")),
      textType(),
      List.of(returnStmt(nameExpr("plain_text"))),
      List.of()
    );
    var module = moduleOf("tests.pii.always_on.no_pii", List.of(fn));

    var diagnostics = typeChecker.typecheckModule(module);

    boolean anyPii = diagnostics.stream().anyMatch(d -> isPiiCode(d.code()));
    assertFalse(
      anyPii,
      "无 PII 字段的代码不应触发 PII 诊断：" + diagnostics
    );
  }

  @Test
  @DisplayName("元测试：isPiiCode 覆盖 ErrorCode 中所有 Category.PII 成员")
  void piiCodeSetIsExhaustive() {
    // P0-R2 (codex review High #7 + Medium #10): 自动派生 PII codes 集合，
    // 防止未来新增 PII code 时漏加入 isPiiCode()。所有 Category.PII 的
    // ErrorCode 都必须被识别为 PII code。
    for (ErrorCode code : ErrorCode.values()) {
      if (code.category() == ErrorCode.Category.PII) {
        assertTrue(
          isPiiCode(code),
          "新增的 PII code " + code.name() + " (" + code.code() + ") 未加入 isPiiCode() 集合 —— " +
            "请同步更新 isPiiCode() 和 TS 端 pii-cross-runtime-conformance.test.ts 的 PII_CODES"
        );
      }
    }
  }

  @Test
  @DisplayName("元测试：ErrorCode 必须含 PII_ANALYZER_FAILED (E404)，与 TS 端对齐")
  void piiAnalyzerFailedCodeMirroredFromTs() {
    // P0-R2 (codex review High #2/#7): TS 端 src/diagnostics/error_codes.ts
    // 新增了 PII_ANALYZER_FAILED = E404。Java 端必须有对应镜像以保证跨语言
    // analyzer failure 语义一致。
    assertEquals("E404", ErrorCode.PII_ANALYZER_FAILED.code(),
      "PII_ANALYZER_FAILED 必须是 E404 与 TS 端镜像");
    assertEquals(ErrorCode.Severity.ERROR, ErrorCode.PII_ANALYZER_FAILED.severity(),
      "PII analyzer failure 必须是 error severity（不能伪装成 warning）");
    assertEquals(ErrorCode.Category.PII, ErrorCode.PII_ANALYZER_FAILED.category(),
      "PII_ANALYZER_FAILED 必须归类为 PII");
  }

  // ============================================================
  // Helpers
  // ============================================================

  private boolean hasCode(List<Diagnostic> diagnostics, ErrorCode code) {
    return diagnostics.stream().anyMatch(d -> d.code() == code);
  }

  /**
   * 所有 PII 相关错误码。P0-R2 (codex review High #7): 与 TS 端
   * pii-cross-runtime-conformance.test.ts 的 PII_CODES 集合对齐——
   * 任何遗漏意味着 conformance 在该 code 上 drift 时测试不会捕获。
   */
  private boolean isPiiCode(ErrorCode code) {
    return code == ErrorCode.PII_ASSIGN_DOWNGRADE
        || code == ErrorCode.PII_SINK_UNSANITIZED
        || code == ErrorCode.PII_ARG_VIOLATION
        || code == ErrorCode.PII_IMPLICIT_UPLEVEL
        || code == ErrorCode.PII_SINK_UNKNOWN
        || code == ErrorCode.PII_HTTP_UNENCRYPTED
        || code == ErrorCode.PII_ANNOTATION_MISSING
        || code == ErrorCode.PII_SENSITIVITY_MISMATCH
        || code == ErrorCode.PII_MISSING_CONSENT_CHECK
        || code == ErrorCode.PII_ANALYZER_FAILED;
  }

  private CoreModel.Module moduleOf(String name, List<? extends CoreModel.Decl> decls) {
    var m = new CoreModel.Module();
    m.name = name;
    m.decls = new ArrayList<>(decls);
    return m;
  }

  private CoreModel.Func func(
    String name,
    List<CoreModel.Param> params,
    CoreModel.Type ret,
    List<CoreModel.Stmt> body,
    List<String> effects
  ) {
    var func = new CoreModel.Func();
    func.name = name;
    func.params = params;
    func.ret = ret;
    func.effects = effects;
    var block = new CoreModel.Block();
    block.statements = body;
    func.body = block;
    return func;
  }

  private CoreModel.Param piiParam(String name, String level, String category) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = piiType(level, category);
    return param;
  }

  private CoreModel.Param plainParam(String name) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = textType();
    return param;
  }

  private CoreModel.PiiType piiType(String level, String category) {
    var pii = new CoreModel.PiiType();
    pii.baseType = textType();
    pii.sensitivity = level;
    pii.category = category;
    return pii;
  }

  private CoreModel.TypeName textType() {
    var t = new CoreModel.TypeName();
    t.name = "Text";
    return t;
  }

  private CoreModel.Let letStmt(String name, CoreModel.Expr expr) {
    var stmt = new CoreModel.Let();
    stmt.name = name;
    stmt.expr = expr;
    return stmt;
  }

  private CoreModel.Set setStmt(String name, CoreModel.Expr expr) {
    var stmt = new CoreModel.Set();
    stmt.name = name;
    stmt.expr = expr;
    return stmt;
  }

  private CoreModel.Return returnStmt(CoreModel.Expr expr) {
    var stmt = new CoreModel.Return();
    stmt.expr = expr;
    return stmt;
  }

  private CoreModel.Name nameExpr(String name) {
    var n = new CoreModel.Name();
    n.name = name;
    return n;
  }

  private CoreModel.StringE stringLiteral(String value) {
    var s = new CoreModel.StringE();
    s.value = value;
    return s;
  }

  private CoreModel.Call callExpr(String funcName, CoreModel.Expr... args) {
    var call = new CoreModel.Call();
    call.target = nameExpr(funcName);
    call.args = List.of(args);
    return call;
  }
}
