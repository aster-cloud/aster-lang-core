package aster.core.typecheck.capability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ManifestReader 失败即关闭（fail-closed）测试。
 * <p>
 * 当 manifest 路径被显式指定但不可读 / 内容非法时，必须抛错而非静默放行——
 * 否则能力/effect 边界会被悄悄绕过。
 */
class ManifestReaderTest {

  @Test
  void nullPathThrows() {
    assertThrows(IllegalArgumentException.class, () -> ManifestReader.read(null));
  }

  @Test
  void missingFileThrowsFailClosed() {
    var missing = Path.of("does-not-exist-" + System.nanoTime() + ".json");
    assertThrows(IllegalArgumentException.class, () -> ManifestReader.read(missing));
  }

  @Test
  void invalidJsonThrowsFailClosed(@TempDir Path dir) throws IOException {
    var path = dir.resolve("broken.json");
    Files.writeString(path, "{ this is not valid json ");
    // IOException -> IllegalStateException（fail-closed），不会返回空配置静默放行。
    assertThrows(IllegalStateException.class, () -> ManifestReader.read(path));
  }

  @Test
  void unknownCapabilityThrowsFailClosed(@TempDir Path dir) throws IOException {
    var path = dir.resolve("bad-cap.json");
    Files.writeString(path, "{\"capabilities\":{\"allow\":[\"NotARealCapability\"]}}");
    assertThrows(IllegalArgumentException.class, () -> ManifestReader.read(path));
  }
}
