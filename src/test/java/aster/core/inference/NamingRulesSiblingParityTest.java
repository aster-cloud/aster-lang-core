package aster.core.inference;

import aster.core.typecheck.governance.ControlRegistrySiblingParityTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 推断命名规则的 classpath 副本与 aster-lang-locales 真相源 {@code type-inference/naming-rules.json} 逐字节相同
 * （ADR 0056 §3.2，副本模式同 ADR 0045 §2.2）。依次查找 {@code $ASTER_LOCALES_DIR}、兄弟目录
 * {@code ../aster-lang-locales}、CI 工作区子目录 {@code ./aster-lang-locales}；CI 中都不存在即失败，本地允许跳过。
 */
class NamingRulesSiblingParityTest {

  static final String RESOURCE = "/type-inference/naming-rules.json";

  /** 定位 locales 中 {@code type-inference/} 下的文件；共用测试向量也经此读取。 */
  static Optional<Path> locate(String file) {
    List<Path> candidates = new ArrayList<>();
    String env = System.getenv("ASTER_LOCALES_DIR");
    if (env != null && !env.isBlank()) {
      candidates.add(Path.of(env, "type-inference", file));
    }
    candidates.add(Path.of("..", "aster-lang-locales", "type-inference", file));
    candidates.add(Path.of("aster-lang-locales", "type-inference", file));
    return ControlRegistrySiblingParityTest.locate(candidates, System.getenv("CI"));
  }

  @Test
  void 副本与真相源逐字节相同() throws Exception {
    Optional<Path> source = locate("naming-rules.json");
    assumeTrue(source.isPresent(), "本地未并列 checkout aster-lang-locales，跳过");
    byte[] copy;
    try (var in = NamingRulesSiblingParityTest.class.getResourceAsStream(RESOURCE)) {
      copy = in.readAllBytes();
    }
    var mapper = new ObjectMapper();
    assertEquals(mapper.readTree(source.get().toFile()), mapper.readTree(copy));
    assertArrayEquals(Files.readAllBytes(source.get()), copy,
        "classpath 副本须与 locales type-inference/naming-rules.json 逐字节相同（整文件复制同步）");
  }
}
