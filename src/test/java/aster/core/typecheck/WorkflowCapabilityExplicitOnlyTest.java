package aster.core.typecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

/**
 * ADR 0056 §4.1（V2）：IR 只记源码写下的能力，工作流步骤的能力检查只在 effectCapsExplicit 为真时进行。
 *
 * <p>与 ts {@code test/unit/effect/effect-caps-explicit-only.test.ts} 及 aster-lang-test 样本
 * {@code tier1-parity/workflow/workflow-bare-io-capability.aster} 对应：同一段「裸 {@code It performs io}
 * + 步骤里调用 {@code Http.get}」两侧都不报 {@code WORKFLOW_UNDECLARED_CAPABILITY}。
 */
class WorkflowCapabilityExplicitOnlyTest {

  private static CoreModel.Module lower(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    tokens.seek(0);
    var parser = new AsterParser(tokens);
    parser.removeErrorListeners();
    var ast = new AstBuilder().visitModule(parser.module());
    return new CoreLowering().lowerModule(ast);
  }

  private static List<ErrorCode> codesOf(String src) {
    return new TypeChecker().typecheckModule(lower(src)).stream().map(d -> d.code()).toList();
  }

  private static String workflow(String effects) {
    return "Module m.\n\n"
        + "Rule sync given endpoint as Text, produce Text. It performs " + effects + ":\n"
        + "  workflow:\n"
        + "    step fetch:\n"
        + "      Return Http.get(endpoint).\n"
        + "  .\n";
  }

  @Test
  void bareIoHasNoCapabilitiesInIr() {
    var func = (CoreModel.Func) lower(workflow("io")).decls.get(0);
    assertEquals(List.of(), func.effectCaps);
    assertFalse(func.effectCapsExplicit);
  }

  @Test
  void bareIoWorkflowDoesNotReportUndeclaredCapability() {
    assertFalse(codesOf(workflow("io")).contains(ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY));
  }

  @Test
  void bareIoCompensateStillReportsNewCapability() {
    // 补偿块能力一致性检查不受显式门控（与 ts 一致）
    var codes = codesOf("Module m.\n\n"
        + "Rule sync given u as Text, produce. It performs io:\n"
        + "  workflow:\n"
        + "    step fetch:\n"
        + "      Return Http.get(u).\n"
        + "    compensate:\n"
        + "      Return Db.query(u).\n"
        + "  .\n");
    assertTrue(codes.contains(ErrorCode.COMPENSATE_NEW_CAPABILITY), codes.toString());
    assertFalse(codes.contains(ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY), codes.toString());
  }

  @Test
  void explicitCapabilitiesAreStillCheckedPerStep() {
    assertTrue(codesOf(workflow("io [Sql]")).contains(ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY));
    assertFalse(codesOf(workflow("io [Http]")).contains(ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY));
  }
}
