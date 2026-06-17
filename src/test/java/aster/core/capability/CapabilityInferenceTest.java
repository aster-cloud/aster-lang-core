package aster.core.capability;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护中立 capability 模块（#26）。
 * <p>
 * {@link CapabilityInference} 是 lowering（phase 4）与 typecheck（phase 3）共享的
 * capability 推断来源，从 typecheck 下沉至此以解除 lowering -&gt; typecheck 的层级耦合。
 * 该模块仅依赖 IR，本测试直接覆盖其推断逻辑，确保下沉后行为不变。
 */
class CapabilityInferenceTest {

  @Test
  void inferCapabilityFromNameMatchesKnownPrefix() {
    assertEquals(CapabilityKind.HTTP, CapabilityInference.inferCapabilityFromName("Http.get").orElseThrow());
    assertEquals(CapabilityKind.SQL, CapabilityInference.inferCapabilityFromName("Db.query").orElseThrow());
    assertEquals(CapabilityKind.SQL, CapabilityInference.inferCapabilityFromName("Sql.exec").orElseThrow());
  }

  @Test
  void inferCapabilityFromNameUnknownReturnsEmpty() {
    assertTrue(CapabilityInference.inferCapabilityFromName("Custom.call").isEmpty());
    assertTrue(CapabilityInference.inferCapabilityFromName(null).isEmpty());
    assertTrue(CapabilityInference.inferCapabilityFromName("  ").isEmpty());
  }

  @Test
  void collectCapabilitiesAggregatesCallsAcrossNestedBlocks() {
    var caps = CapabilityInference.collectCapabilities(
      blockWithStatements(returnCall("Http.get"), returnCall("Db.query"))
    );

    assertEquals(List.of("Http.get"), caps.get(CapabilityKind.HTTP));
    assertEquals(List.of("Db.query"), caps.get(CapabilityKind.SQL));
  }

  @Test
  void collectCapabilitiesNullBodyReturnsEmpty() {
    assertTrue(CapabilityInference.collectCapabilities(null).isEmpty());
  }

  private CoreModel.Block blockWithStatements(CoreModel.Stmt... statements) {
    var block = new CoreModel.Block();
    block.statements = List.of(statements);
    return block;
  }

  private CoreModel.Return returnCall(String targetName) {
    var name = new CoreModel.Name();
    name.name = targetName;
    var call = new CoreModel.Call();
    call.target = name;
    call.args = List.of();
    var ret = new CoreModel.Return();
    ret.expr = call;
    return ret;
  }
}
