package aster.core.typecheck.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * classpath 副本与 aster-lang-locales 真相源深度相等。
 * 本地开发为兄弟目录 ../aster-lang-locales；CI（ci.yml）把 locales checkout 到工作区子目录
 * ./aster-lang-locales。CI 中两者都不存在即失败——静默跳过的门禁不是门禁；仅本地允许跳过。
 */
class ControlRegistrySiblingParityTest {
  static final List<Path> CANDIDATES = List.of(
      Path.of("..", "aster-lang-locales", "controls", "registry.json"),
      Path.of("aster-lang-locales", "controls", "registry.json"));

  @Test void 副本与真相源一致() throws Exception {
    Optional<Path> source = locate(CANDIDATES, System.getenv("CI"));
    assumeTrue(source.isPresent(), "本地未并列 checkout aster-lang-locales，跳过");
    var mapper = new ObjectMapper();
    var expected = mapper.readTree(source.get().toFile());
    try (var in = ControlRegistry.class.getResourceAsStream("/governance/controls-registry.json")) {
      assertEquals(expected, mapper.readTree(in));
    }
  }

  @Test void CI中真相源缺失即失败而非跳过() {
    List<Path> missing = List.of(Path.of("no-such-dir", "registry.json"));
    assertEquals(Optional.empty(), locate(missing, null));
    assertEquals(Optional.empty(), locate(missing, ""));
    try {
      locate(missing, "true");
    } catch (AssertionError expected) {
      return;
    }
    fail("CI 下真相源缺失应失败");
  }

  /** 返回首个存在的候选；全部缺失时 CI 下失败，本地返回空以便跳过。 */
  static Optional<Path> locate(List<Path> candidates, String ciEnv) {
    Optional<Path> found = candidates.stream().filter(Files::exists).findFirst();
    if (found.isEmpty() && ciEnv != null && !ciEnv.isBlank()) {
      fail("CI 中必须 checkout aster-lang-locales 以校验控制项注册表副本一致性，以下路径均不存在："
          + candidates.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList()
          + "。请检查 .github/workflows/ci.yml 的 checkout 步骤。");
    }
    return found;
  }
}
