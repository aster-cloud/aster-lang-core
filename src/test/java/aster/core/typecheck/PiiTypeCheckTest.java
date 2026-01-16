package aster.core.typecheck;

import aster.core.ir.CoreModel;
import aster.core.typecheck.model.Diagnostic;
import aster.core.typecheck.pii.PiiTypeChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PiiTypeCheckTest {

  private PiiTypeChecker checker;

  @BeforeEach
  void setUp() {
    checker = new PiiTypeChecker();
  }

  @Test
  void testAssignPiiToPlain_shouldError() {
    var fn = func(
      "assign_plain",
      List.of(piiParam("email", "L2", "email")),
      textType(),
      List.of(
        letStmt("plain", stringLiteral("safe")),
        setStmt("plain", nameExpr("email")),
        returnStmt(nameExpr("plain"))
      ),
      List.of()
    );

    var diagnostics = checker.checkModule(List.of(fn));
    assertTrue(hasCode(diagnostics, ErrorCode.PII_ASSIGN_DOWNGRADE));
  }

  @Test
  void testImplicitUpgradeWarning() {
    var fn = func(
      "implicit_upgrade",
      List.of(piiParam("emailLow", "L1", "email")),
      piiType("L1", "email"),
      List.of(
        letStmt("low", nameExpr("emailLow")),
        setStmt("low", stringLiteral("anon")),
        returnStmt(nameExpr("low"))
      ),
      List.of()
    );

    var diagnostics = checker.checkModule(List.of(fn));
    assertTrue(hasCode(diagnostics, ErrorCode.PII_IMPLICIT_UPLEVEL));
  }

  @Test
  void testL2ToL1_shouldError() {
    var fn = func(
      "downgrade_level",
      List.of(
        piiParam("low", "L1", "email"),
        piiParam("high", "L2", "email")
      ),
      piiType("L1", "email"),
      List.of(
        letStmt("alias", nameExpr("low")),
        setStmt("alias", nameExpr("high")),
        returnStmt(nameExpr("alias"))
      ),
      List.of()
    );

    var diagnostics = checker.checkModule(List.of(fn));
    assertTrue(hasCode(diagnostics, ErrorCode.PII_ASSIGN_DOWNGRADE));
  }

  @Test
  void testMergeBranchesL3() {
    var fn = func(
      "merge_highest",
      List.of(
        piiParam("low", "L1", "email"),
        piiParam("top", "L3", "email")
      ),
      textType(),
      List.of(
        letStmt("final", nameExpr("low")),
        ifStmt(
          boolLiteral(true),
          block(setStmt("final", nameExpr("top"))),
          block(setStmt("final", nameExpr("low")))
        ),
        returnStmt(callExpr("IO.print", nameExpr("final")))
      ),
      List.of("io")
    );

    var diagnostics = checker.checkModule(List.of(fn));
    var diag = findDiagnostic(diagnostics, ErrorCode.PII_SINK_UNSANITIZED);
    assertNotNull(diag);
    assertEquals("L3", diag.data().get("level"));
  }

  @Test
  void testPropagateLevelAcrossBranches() {
    var fn = func(
      "merge_mid",
      List.of(piiParam("mid", "L2", "email")),
      textType(),
      List.of(
        letStmt("final", stringLiteral("plain")),
        ifStmt(
          boolLiteral(true),
          block(setStmt("final", nameExpr("mid"))),
          block(setStmt("final", stringLiteral("plain")))
        ),
        returnStmt(callExpr("IO.print", nameExpr("final")))
      ),
      List.of("io")
    );

    var diagnostics = checker.checkModule(List.of(fn));
    var diag = findDiagnostic(diagnostics, ErrorCode.PII_SINK_UNSANITIZED);
    assertNotNull(diag);
    assertEquals("L2", diag.data().get("level"));
  }

  @Test
  void testBlockPrintL2() {
    var fn = func(
      "print_pii",
      List.of(piiParam("email", "L2", "email")),
      textType(),
      List.of(returnStmt(callExpr("IO.print", nameExpr("email")))),
      List.of("io")
    );
    var diagnostics = checker.checkModule(List.of(fn));
    assertTrue(hasCode(diagnostics, ErrorCode.PII_SINK_UNSANITIZED));
  }

  @Test
  void testAllowPrintPlain() {
    var fn = func(
      "print_plain",
      List.of(plainParam("msg")),
      textType(),
      List.of(returnStmt(callExpr("IO.print", nameExpr("msg")))),
      List.of("io")
    );

    var diagnostics = checker.checkModule(List.of(fn));
    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void testForbidLogL3() {
    var fn = func(
      "log_secret",
      List.of(piiParam("secret", "L3", "email")),
      textType(),
      List.of(returnStmt(callExpr("Log.info", nameExpr("secret")))),
      List.of("io")
    );

    var diagnostics = checker.checkModule(List.of(fn));
    assertTrue(hasCode(diagnostics, ErrorCode.PII_SINK_UNSANITIZED));
  }

  @Test
  void testAcceptMatchingPiiArg() {
    var callee = func(
      "handle_email",
      List.of(piiParam("email", "L2", "email")),
      piiType("L2", "email"),
      List.of(returnStmt(nameExpr("email"))),
      List.of()
    );
    var caller = func(
      "forward_email",
      List.of(piiParam("email", "L2", "email")),
      piiType("L2", "email"),
      List.of(returnStmt(callExpr("handle_email", nameExpr("email")))),
      List.of()
    );

    var diagnostics = checker.checkModule(List.of(callee, caller));
    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void testRejectPiiArgMismatch() {
    var callee = func(
      "handle_email",
      List.of(piiParam("email", "L2", "email")),
      piiType("L2", "email"),
      List.of(returnStmt(nameExpr("email"))),
      List.of()
    );
    var caller = func(
      "send_plain",
      List.of(plainParam("email")),
      piiType("L2", "email"),
      List.of(returnStmt(callExpr("handle_email", nameExpr("email")))),
      List.of()
    );

    var diagnostics = checker.checkModule(List.of(callee, caller));
    assertTrue(hasCode(diagnostics, ErrorCode.PII_ARG_VIOLATION));
  }

  private boolean hasCode(List<Diagnostic> diagnostics, ErrorCode code) {
    return diagnostics.stream().anyMatch(diag -> diag.code() == code);
  }

  private Diagnostic findDiagnostic(List<Diagnostic> diagnostics, ErrorCode code) {
    return diagnostics.stream().filter(diag -> diag.code() == code).findFirst().orElse(null);
  }

  private CoreModel.Func func(String name, List<CoreModel.Param> params, CoreModel.Type ret, List<CoreModel.Stmt> body, List<String> effects) {
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

  private CoreModel.Param plainParam(String name) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = textType();
    return param;
  }

  private CoreModel.Param piiParam(String name, String level, String category) {
    var param = new CoreModel.Param();
    param.name = name;
    param.type = piiType(level, category);
    return param;
  }

  private CoreModel.TypeName textType() {
    var type = new CoreModel.TypeName();
    type.name = "Text";
    return type;
  }

  private CoreModel.PiiType piiType(String level, String category) {
    var pii = new CoreModel.PiiType();
    pii.baseType = textType();
    pii.sensitivity = level;
    pii.category = category;
    return pii;
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

  private CoreModel.If ifStmt(CoreModel.Expr cond, CoreModel.Block thenBlock, CoreModel.Block elseBlock) {
    var stmt = new CoreModel.If();
    stmt.cond = cond;
    stmt.thenBlock = thenBlock;
    stmt.elseBlock = elseBlock;
    return stmt;
  }

  private CoreModel.Block block(CoreModel.Stmt... statements) {
    var block = new CoreModel.Block();
    block.statements = List.of(statements);
    return block;
  }

  private CoreModel.Call callExpr(String targetName, CoreModel.Expr... args) {
    var call = new CoreModel.Call();
    call.target = nameExpr(targetName);
    call.args = List.of(args);
    return call;
  }

  private CoreModel.Name nameExpr(String name) {
    var expr = new CoreModel.Name();
    expr.name = name;
    return expr;
  }

  private CoreModel.StringE stringLiteral(String value) {
    var expr = new CoreModel.StringE();
    expr.value = value;
    return expr;
  }

  private CoreModel.Bool boolLiteral(boolean value) {
    var expr = new CoreModel.Bool();
    expr.value = value;
    return expr;
  }
}
