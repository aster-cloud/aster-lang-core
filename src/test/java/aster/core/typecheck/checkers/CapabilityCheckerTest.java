package aster.core.typecheck.checkers;

import aster.core.capability.CapabilityKind;
import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.capability.ManifestConfig;
import aster.core.typecheck.capability.ManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityCheckerTest {

  private CapabilityChecker checker;

  @BeforeEach
  void setUp() {
    checker = new CapabilityChecker();
  }

  @Test
  void inferCapabilityFromNameHttp() {
    var inferred = CapabilityChecker.inferCapabilityFromName("Http.get");
    assertTrue(inferred.isPresent());
    assertEquals(CapabilityKind.HTTP, inferred.get());
  }

  @Test
  void inferCapabilityFromNameUnknownReturnsEmpty() {
    var inferred = CapabilityChecker.inferCapabilityFromName("Custom.call");
    assertTrue(inferred.isEmpty());
  }

  @Test
  void collectCapabilitiesAggregatesCalls() {
    var block = blockWithStatements(
      returnCall("Http.get"),
      returnCall("Db.query")
    );
    var caps = checker.collectCapabilities(block);

    assertEquals(List.of("Http.get"), caps.get(CapabilityKind.HTTP));
    assertEquals(List.of("Db.query"), caps.get(CapabilityKind.SQL));
  }

  @Test
  void manifestAllowsHttpDeniesSecrets() {
    var manifestPath = Path.of("src/test/resources/test-manifest.json");
    var manifest = ManifestReader.read(manifestPath);

    assertTrue(manifest.isAllowed(CapabilityKind.HTTP));
    assertFalse(manifest.isAllowed(CapabilityKind.SECRETS));
  }

  @Test
  void checkModuleSkipsWhenIoEffectPresent() {
    var func = createFunc("fetchFeed", List.of("io"), blockWithStatements(returnCall("Http.post")));

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void checkModuleReportsMissingIoEffect() {
    var func = createFunc("loadFeed", List.of(), blockWithStatements(returnCall("Http.get")));

    var diagnostics = checker.checkModule(List.of(func));

    assertEquals(1, diagnostics.size());
    assertEquals(ErrorCode.CAPABILITY_INFER_MISSING_IO, diagnostics.get(0).code());
  }

  // ===== A3: Crypto 是 cpu-class（与 TS 对齐），缺声明报 MISSING_CPU 非 MISSING_IO =====

  @Test
  void cryptoHashReportsMissingCpuNotIo() {
    // Hash.sha256（Crypto，cpu-class）无 effect → CAPABILITY_INFER_MISSING_CPU。
    var func = createFunc("digest", List.of(), blockWithStatements(returnCall("Hash.sha256")));

    var diagnostics = checker.checkModule(List.of(func));

    assertEquals(1, diagnostics.size());
    assertEquals(ErrorCode.CAPABILITY_INFER_MISSING_CPU, diagnostics.get(0).code());
  }

  @Test
  void cryptoKmsReportsMissingCpuNotIo() {
    // Kms.encrypt（Crypto，cpu-class）无 effect → MISSING_CPU（远程 KMS 应另加 Network/Secrets）。
    var func = createFunc("encrypt", List.of(), blockWithStatements(returnCall("Kms.encrypt")));

    var diagnostics = checker.checkModule(List.of(func));

    assertEquals(1, diagnostics.size());
    assertEquals(ErrorCode.CAPABILITY_INFER_MISSING_CPU, diagnostics.get(0).code());
  }

  @Test
  void cryptoWithCpuEffectIsClean() {
    // Crypto + 声明 cpu → 无诊断（cpu-class 需 @cpu 或 @io）。
    var func = createFunc("digest", List.of("cpu"), blockWithStatements(returnCall("Hash.sha256")));

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void cryptoWithIoEffectIsClean() {
    // Crypto + 声明 io → 无诊断（io 隐含可计算，覆盖 cpu-class）。
    var func = createFunc("digest", List.of("io"), blockWithStatements(returnCall("Hash.sha256")));

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void checkModuleIgnoresNullBody() {
    var func = createFunc("noop", List.of(), null);

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(diagnostics.isEmpty());
  }

  @Test
  void explicitCapabilityDeclarationCheck() {
    var func = createFunc(
      "fetchWorkflow",
      List.of("io"),
      blockWithStatements(
        returnCall("Http.get"),
        returnCall("Db.query")
      )
    );
    func.effectCaps = List.of("Http", "Secrets");
    func.effectCapsExplicit = true;

    var diagnostics = checker.checkModule(List.of(func));

    assertEquals(2, diagnostics.size());
    assertEquals(ErrorCode.EFF_CAP_MISSING, diagnostics.get(0).code());
    assertEquals(ErrorCode.EFF_CAP_SUPERFLUOUS, diagnostics.get(1).code());
  }

  @Test
  void functionViolatesManifest() {
    var manifest = new ManifestConfig(
      Set.of(CapabilityKind.HTTP, CapabilityKind.SECRETS),
      Set.of(CapabilityKind.SECRETS)
    );
    checker.setManifest(manifest);
    var func = createFunc(
      "auditSecrets",
      List.of("io"),
      blockWithStatements(returnCall("Secrets.fetch"))
    );
    func.effectCaps = List.of("Secrets");
    func.effectCapsExplicit = true;

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(
      diagnostics.stream().anyMatch(d -> d.code() == ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY),
      "Manifest 拒绝的能力应产生 WORKFLOW_UNDECLARED_CAPABILITY 诊断"
    );
  }

  @Test
  void manifestNullSkipsCheck() {
    checker.setManifest(null);
    var func = createFunc(
      "publicHttp",
      List.of("io"),
      blockWithStatements(returnCall("Http.get"))
    );
    func.effectCaps = List.of("Http");
    func.effectCapsExplicit = true;

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(diagnostics.isEmpty(), "未配置 manifest 时不应触发额外诊断");
  }

  @Test
  void effectCapsExplicitFlagFalseSkipsCheck() {
    var func = createFunc(
      "backgroundJob",
      List.of("io"),
      blockWithStatements(
        returnCall("Db.query")
      )
    );
    func.effectCaps = List.of("Sql");
    func.effectCapsExplicit = false;

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(diagnostics.isEmpty(), "未显式声明 effectCaps 时应跳过能力核对");
  }

  @Test
  void workflowCapabilityAggregation() {
    var workflow = workflow(step(
      "fetchHttp",
      blockWithStatements(returnCall("Http.get")),
      null
    ));
    var func = createFunc("syncOrder", List.of("io"), blockWithStatements(workflow));
    func.effectCaps = List.of("Sql");
    func.effectCapsExplicit = true;

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(
      diagnostics.stream().anyMatch(d -> d.code() == ErrorCode.WORKFLOW_UNDECLARED_CAPABILITY),
      "workflow 使用未声明能力时应报 WORKFLOW_UNDECLARED_CAPABILITY"
    );
  }

  @Test
  void workflowCompensateParity() {
    var workflow = workflow(step(
      "settlePayment",
      blockWithStatements(returnCall("Http.post")),
      blockWithStatements(returnCall("Secrets.fetch"))
    ));
    var func = createFunc("settleWorkflow", List.of("io"), blockWithStatements(workflow));
    func.effectCaps = List.of("Http", "Secrets");
    func.effectCapsExplicit = true;

    var diagnostics = checker.checkModule(List.of(func));

    assertTrue(
      diagnostics.stream().anyMatch(d -> d.code() == ErrorCode.COMPENSATE_NEW_CAPABILITY),
      "compensate 块引入主体未使用的能力时应报 COMPENSATE_NEW_CAPABILITY"
    );
  }

  // ========== 辅助构造 ==========

  private CoreModel.Func createFunc(String name, List<String> effects, CoreModel.Block body) {
    var func = new CoreModel.Func();
    func.name = name;
    func.effects = effects;
    func.params = List.of();
    func.typeParams = List.of();
    func.effectCaps = List.of();
    var ret = new CoreModel.TypeName();
    ret.name = "Unit";
    func.ret = ret;
    func.body = body;
    return func;
  }

  private CoreModel.Return returnCall(String targetName) {
    var call = new CoreModel.Call();
    call.target = createName(targetName);
    call.args = List.of();

    var ret = new CoreModel.Return();
    ret.expr = call;
    return ret;
  }

  private CoreModel.Block blockWithStatements(CoreModel.Stmt... statements) {
    var block = new CoreModel.Block();
    block.statements = List.of(statements);
    return block;
  }

  private CoreModel.Name createName(String value) {
    var name = new CoreModel.Name();
    name.name = value;
    return name;
  }

  private CoreModel.Step step(String name, CoreModel.Block body, CoreModel.Block compensate) {
    var step = new CoreModel.Step();
    step.name = name;
    step.body = body;
    step.compensate = compensate;
    step.dependencies = List.of();
    step.effectCaps = List.of();
    return step;
  }

  private CoreModel.Workflow workflow(CoreModel.Step... steps) {
    var wf = new CoreModel.Workflow();
    wf.steps = List.of(steps);
    wf.effectCaps = List.of();
    return wf;
  }
}
