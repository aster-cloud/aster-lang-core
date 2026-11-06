package aster.core.canonicalizer;

import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

/**
 * 语料快照（#243 冠词改动的回归对比用）：对固定输入清单——classpath 上 aster-lang-test 的
 * corpus/ 全部 .aster 与 JSON 内嵌源码，加本仓 src/test/resources——逐份输出
 * canonicalize 文本、语法错误、AST JSON、Core JSON。仅在设置 -Dcorpus.snapshot.out 时运行；
 * 对比流程见 scripts/corpus-snapshot-diff.sh。
 */
class CorpusSnapshotTest {
  static void collect(com.fasterxml.jackson.databind.JsonNode n, List<String> out) {
    if (n.isObject()) n.fields().forEachRemaining(e -> {
      if (e.getValue().isTextual() && e.getValue().asText().contains("Module ")) out.add(e.getValue().asText());
      else collect(e.getValue(), out);
    });
    else if (n.isArray()) n.forEach(c -> collect(c, out));
    else if (n.isTextual() && n.asText().contains("Module ")) out.add(n.asText());
  }

  @Test
  void snapshot() throws Exception {
    String out = System.getProperty("corpus.snapshot.out");
    org.junit.jupiter.api.Assumptions.assumeTrue(out != null && !out.isBlank(), "未设置 corpus.snapshot.out");
    var corpusUri = CorpusSnapshotTest.class.getResource("/corpus/VERSION").toURI();
    List<Path> roots = new ArrayList<>();
    if ("jar".equals(corpusUri.getScheme())) {
      try { FileSystems.newFileSystem(corpusUri, Map.of()); } catch (FileSystemAlreadyExistsException ignored) { }
    }
    roots.add(Path.of(corpusUri).getParent());
    roots.add(Path.of("src/test/resources"));
    List<Path> files = new ArrayList<>();
    for (Path root : roots) {
      try (Stream<Path> s = Files.walk(root)) { s.filter(p -> p.toString().endsWith(".aster") || p.toString().endsWith(".json")).forEach(files::add); }
    }
    files.sort(Comparator.comparing(Path::toString));
    ObjectMapper om = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    StringBuilder sb = new StringBuilder();
    // 读取失败（I/O、JSON 解析）属于快照基础设施故障：不吞，直接让测试失败
    for (Path p : files) {
      List<String> srcs = new ArrayList<>();
      if (p.toString().endsWith(".json")) collect(om.readTree(Files.readString(p)), srcs);
      else srcs.add(Files.readString(p));
      String name = p.toUri().toString().replaceAll("^.*!/", "").replaceAll("^.*/src/test/resources/", "resources/");
      for (String src : srcs) {
        sb.append("=== ").append(name).append('\n');
        snapshotSource(src, om, sb);
      }
    }
    org.junit.jupiter.api.Assertions.assertTrue(sb.length() > 0, "语料为空");
    Files.writeString(Path.of(out), sb.toString());
  }

  /**
   * 单份源码的快照。引擎对该源抛出的异常（tier1-reject 等语料本就应被拒绝）是被比较的
   * 行为本身，连同完整消息写入快照，前后不一致即被 diff 发现；序列化失败同样记录。
   */
  private static void snapshotSource(String src, ObjectMapper om, StringBuilder sb) {
    String canon;
    try {
      canon = new Canonicalizer().canonicalize(src);
    } catch (RuntimeException e) {
      sb.append("CANON-ERR ").append(e).append('\n');
      return;
    }
    sb.append(canon).append('\n');
    var parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canon))));
    parser.removeErrorListeners();
    List<String> errs = new ArrayList<>();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object o, int l, int c, String m, RecognitionException e) {
        errs.add(l + ":" + c + " " + m);
      }
    });
    try {
      var ast = new AstBuilder().visitModule(parser.module());
      sb.append("SYNTAX ").append(errs).append('\n');
      sb.append("AST ").append(om.writeValueAsString(ast)).append('\n');
      sb.append("CORE ").append(om.writeValueAsString(new CoreLowering().lowerModule(ast))).append('\n');
    } catch (Exception e) {
      sb.append("SYNTAX ").append(errs).append('\n');
      sb.append("ERR ").append(e).append('\n');
    }
  }
}
