package aster.core.inference;

import aster.core.ast.Constraint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 逐条运行 aster-lang-locales {@code type-inference/test-vectors.json}（ADR 0056 §3.2 共用测试向量）：
 * 给定名字与约束，推断类型须等于 expected。ts 侧 {@code type-inference-vectors.test.ts} 跑同一份向量。
 */
class TypeInferenceVectorsTest {

  @Test
  void 共用测试向量全部一致() throws Exception {
    Optional<Path> source = NamingRulesSiblingParityTest.locate("test-vectors.json");
    assumeTrue(source.isPresent(), "本地未并列 checkout aster-lang-locales，跳过");
    JsonNode vectors = new ObjectMapper().readTree(source.get().toFile());
    assertTrue(vectors.size() > 0);
    List<String> wrong = new ArrayList<>();
    for (JsonNode v : vectors) {
      String name = v.path("name").asText();
      String actual = TypeInference.inferTypeName(name, constraints(v.path("constraints")));
      if (!actual.equals(v.path("expected").asText())) {
        wrong.add(name + " " + v.path("constraints") + "：期望 " + v.path("expected").asText() + "，实际 " + actual);
      }
    }
    assertEquals(List.of(), wrong);
  }

  /** IR v2 形状的约束（不带 origin）转为 AST 约束；Range 端点本来就是规范十进制串。 */
  private static List<Constraint> constraints(JsonNode array) {
    List<Constraint> out = new ArrayList<>();
    for (JsonNode c : array) {
      switch (c.path("kind").asText()) {
        case "Required" -> out.add(new Constraint.Required(null));
        case "Range" -> out.add(new Constraint.Range(text(c.get("min")), text(c.get("max")), null));
        case "Pattern" -> out.add(new Constraint.Pattern(c.path("regexp").asText(), null));
        default -> throw new IllegalArgumentException("未知约束：" + c);
      }
    }
    return out;
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }
}
