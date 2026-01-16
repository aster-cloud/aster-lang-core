package aster.core.typecheck.capability;

import aster.core.typecheck.CapabilityKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Manifest 读取器，负责解析 JSON 文件并构造 ManifestConfig。
 */
public final class ManifestReader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ManifestReader() {
  }

  public static ManifestConfig read(Path manifestPath) {
    if (manifestPath == null) {
      throw new IllegalArgumentException("manifestPath 不能为空");
    }
    if (!Files.exists(manifestPath)) {
      throw new IllegalArgumentException("Manifest 文件不存在: " + manifestPath);
    }
    try (var input = Files.newInputStream(manifestPath)) {
      var root = MAPPER.readTree(input);
      var capabilitiesNode = root.path("capabilities");
      var allowed = parseCapabilities(capabilitiesNode.path("allow"));
      var denied = parseCapabilities(capabilitiesNode.path("deny"));
      return new ManifestConfig(allowed, denied);
    } catch (IOException ex) {
      throw new IllegalStateException("读取 manifest 失败: " + manifestPath, ex);
    }
  }

  private static Set<CapabilityKind> parseCapabilities(JsonNode node) {
    if (node == null || node.isMissingNode() || !node.isArray()) {
      return Set.of();
    }
    var caps = EnumSet.noneOf(CapabilityKind.class);
    for (var element : node) {
      if (!element.isTextual()) {
        throw new IllegalArgumentException("Manifest 能力定义必须是字符串: " + element);
      }
      var label = element.asText();
      var kind = CapabilityKind.fromLabel(label).orElseThrow(() ->
        new IllegalArgumentException("Manifest 包含未知能力: " + label)
      );
      caps.add(kind);
    }
    return caps;
  }
}
