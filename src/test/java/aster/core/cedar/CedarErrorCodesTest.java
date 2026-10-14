package aster.core.cedar;

import aster.core.typecheck.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 锁定 Cedar 导出错误码 E720–E724 的编号与消息模板（ADR 0047 §4.4）。 */
class CedarErrorCodesTest {

  @Test
  void cedarCodesExistWithVerbatimTemplates() {
    assertEquals("E720", ErrorCode.CEDAR_NOT_EXPORTABLE_SHAPE.code());
    assertEquals("E721", ErrorCode.CEDAR_UNSUPPORTED_CONSTRUCT.code());
    assertEquals("E722", ErrorCode.CEDAR_UNTYPED_CONTEXT_KEY.code());
    assertEquals("E723", ErrorCode.CEDAR_EQUIVALENCE_MISMATCH.code());
    assertEquals("E724", ErrorCode.CEDAR_SCHEMA_INVALID.code());
    assertEquals(
        "Rule 'R-1' uses Match, which has no Cedar translation",
        ErrorCode.CEDAR_UNSUPPORTED_CONSTRUCT.render(Map.of("rule", "R-1", "construct", "Match")));
  }
}
