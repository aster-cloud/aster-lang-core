package aster.core.typecheck.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** classpath 副本与 aster-lang-locales 真相源深度相等（兄弟仓缺失时跳过）。 */
class ControlRegistrySiblingParityTest {
  @Test void 副本与真相源一致() throws Exception {
    Path source = Path.of("..", "aster-lang-locales", "controls", "registry.json");
    assumeTrue(Files.exists(source), "兄弟仓 aster-lang-locales 不在，跳过");
    var mapper = new ObjectMapper();
    var expected = mapper.readTree(source.toFile());
    try (var in = ControlRegistry.class.getResourceAsStream("/governance/controls-registry.json")) {
      assertEquals(expected, mapper.readTree(in));
    }
  }
}
