package aster.core.cedar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaEmitterTest {

  @Test
  void emitsActionsAndOptionalContext() throws Exception {
    String json = SchemaEmitter.emit(List.of("read", "delete"),
        new LinkedHashMap<>(Map.of("amount", ContextType.LONG)), true, "decide");
    JSONAssert.assertEquals("""
      {"Aster":{"entityTypes":{
        "Principal":{"shape":{"type":"Record","attributes":{"type":{"type":"String"},"roles":{"type":"Set","element":{"type":"String"}}}}},
        "Resource":{"shape":{"type":"Record","attributes":{"type":{"type":"String"}}}}},
       "actions":{
        "read":{"appliesTo":{"principalTypes":["Principal"],"resourceTypes":["Resource"],"context":{"type":"Record","attributes":{"agent_model":{"type":"String","required":false},"amount":{"type":"Long","required":false}}}}},
        "delete":{"appliesTo":{"principalTypes":["Principal"],"resourceTypes":["Resource"],"context":{"type":"Record","attributes":{"agent_model":{"type":"String","required":false},"amount":{"type":"Long","required":false}}}}}}}}
      """, json, true);
  }

  @Test
  void fallbackActionWhenNoneMentioned() throws Exception {
    String json = SchemaEmitter.emit(List.of(), new LinkedHashMap<>(), false, "decide");
    assertThat(new ObjectMapper().readTree(json).at("/Aster/actions").fieldNames()).toIterable().containsExactly("decide");
  }

  @Test
  void contextTypesAndOrderAreStable() throws Exception {
    var keys = new LinkedHashMap<String, ContextType>();
    keys.put("tier", ContextType.STRING);
    keys.put("vip", ContextType.BOOL);
    String json = SchemaEmitter.emit(List.of("read"), keys, false, "decide");
    var attrs = new ObjectMapper().readTree(json).at("/Aster/actions/read/appliesTo/context/attributes");
    assertThat(attrs.fieldNames()).toIterable().containsExactly("tier", "vip");
    assertThat(attrs.at("/tier/type").asText()).isEqualTo("String");
    assertThat(attrs.at("/vip/type").asText()).isEqualTo("Boolean");
    assertThat(attrs.at("/vip/required").asBoolean(true)).isFalse();
    assertThat(json).doesNotContain(" ").doesNotContain("\n");
  }
}
