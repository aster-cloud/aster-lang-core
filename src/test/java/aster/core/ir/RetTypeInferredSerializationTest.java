package aster.core.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Func.retTypeInferred 的 IR JSON 协议：false 不输出（既有 IR 与哈希不变），true 输出，
 * 读取 TS 产出的带该字段的 IR 时能正确恢复。
 */
class RetTypeInferredSerializationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static CoreModel.Func func(boolean inferred) {
    var f = new CoreModel.Func();
    f.name = "r";
    f.typeParams = List.of();
    f.params = List.of();
    var ret = new CoreModel.TypeName();
    ret.name = "Text";
    f.ret = ret;
    f.effects = List.of();
    f.retTypeInferred = inferred;
    return f;
  }

  @Test
  void falseIsOmittedFromJson() throws Exception {
    String json = mapper.writeValueAsString(func(false));
    assertFalse(json.contains("retTypeInferred"), json);
  }

  @Test
  void trueIsSerializedAndRoundTrips() throws Exception {
    String json = mapper.writeValueAsString(func(true));
    assertTrue(json.contains("\"retTypeInferred\":true"), json);
    var back = mapper.readValue(json, CoreModel.Func.class);
    assertTrue(back.retTypeInferred);
  }

  @Test
  void tsStyleIrWithFlagIsRead() throws Exception {
    String tsIr = "{\"kind\":\"Func\",\"name\":\"r\",\"typeParams\":[],\"params\":[],"
        + "\"ret\":{\"kind\":\"TypeName\",\"name\":\"Unknown\"},\"retTypeInferred\":true,\"effects\":[],"
        + "\"body\":{\"kind\":\"Block\",\"statements\":[]}}";
    var decl = mapper.readValue(tsIr, CoreModel.Decl.class);
    assertTrue(decl instanceof CoreModel.Func f && f.retTypeInferred);
    assertEquals("Unknown", ((CoreModel.TypeName) ((CoreModel.Func) decl).ret).name);
  }
}
