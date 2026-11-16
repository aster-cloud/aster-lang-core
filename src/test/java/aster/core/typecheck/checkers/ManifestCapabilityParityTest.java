package aster.core.typecheck.checkers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.capability.ManifestReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * manifest 能力检查双引擎 parity（ADR 0056 §4.1、aster-lang-ts#320）。
 *
 * <p>夹具 {@code diagnostics/manifest_capability_parity.json} 与 aster-lang-ts 的
 * {@code shared/manifest_capability_parity.json} byte-identical（由 ts 侧断言）。每例把标签写成 core 的 manifest 文件
 * {@code {"capabilities": {"allow": [...], "deny": [...]}}}，经 {@link ManifestReader} 读入（同时覆盖 io / cpu 类别键的展开），
 * 编译 source 后做带 manifest 的类型检查，比较 E300 的 [码, 行, cap]。
 */
class ManifestCapabilityParityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir
  Path tmp;

  @TestFactory
  Stream<DynamicTest> sharedFixture() throws Exception {
    JsonNode fixture;
    try (InputStream in = getClass().getResourceAsStream("/diagnostics/manifest_capability_parity.json")) {
      assertNotNull(in, "diagnostics/manifest_capability_parity.json 应在 test classpath 中");
      fixture = MAPPER.readTree(in);
    }
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : fixture.get("cases")) {
      tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> assertEquals(expected(c), actual(c))));
    }
    return tests.stream();
  }

  private static List<List<Object>> expected(JsonNode c) {
    List<List<Object>> out = new ArrayList<>();
    c.get("codes").forEach(e -> out.add(List.of(e.get(0).asText(), e.get(1).asInt(), e.get(2).asText())));
    return out;
  }

  private List<List<Object>> actual(JsonNode c) throws Exception {
    ObjectNode caps = MAPPER.createObjectNode();
    caps.set("allow", c.get("allow"));
    caps.set("deny", c.get("deny"));
    ObjectNode root = MAPPER.createObjectNode();
    root.set("capabilities", caps);
    Path manifest = Files.createTempFile(tmp, "manifest", ".json");
    Files.writeString(manifest, MAPPER.writeValueAsString(root));

    TypeChecker checker = new TypeChecker();
    checker.setManifest(ManifestReader.read(manifest));
    List<List<Object>> out = new ArrayList<>();
    for (var d : checker.typecheckModule(compile(c.get("source").asText()))) {
      if (d.code() != ErrorCode.CAPABILITY_NOT_ALLOWED) continue;
      int line = d.span().map(o -> o.start.line).orElse(0);
      out.add(List.of(d.code().code(), line, String.valueOf(d.data().get("cap"))));
    }
    out.sort(Comparator.comparing(e -> (String) e.get(2)));
    return out;
  }

  private static CoreModel.Module compile(String source) {
    String canonical = new Canonicalizer().canonicalize(source);
    AsterParser parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical))));
    aster.core.ast.Module ast = new AstBuilder().visitModule(parser.module());
    return NameResolution.resolveAndLowerChecked(ast, new ResolutionContext(null, null)).core();
  }
}
