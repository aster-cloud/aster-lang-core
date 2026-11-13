package aster.core.ir;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Func.retTypeInferred 的 IR JSON 协议（ADR 0055 #9、#19）：恒输出，false 与 true 都写出并能往返。
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
  void falseIsAlwaysSerialized() throws Exception {
    String json = mapper.writeValueAsString(func(false));
    assertTrue(json.contains("\"retTypeInferred\":false"), json);
  }

  @Test
  void trueIsSerializedAndRoundTrips() throws Exception {
    String json = mapper.writeValueAsString(func(true));
    assertTrue(json.contains("\"retTypeInferred\":true"), json);
    var back = mapper.readValue(json, CoreModel.Func.class);
    assertTrue(back.retTypeInferred);
  }
}
