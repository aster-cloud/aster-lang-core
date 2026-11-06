package aster.core.canonicalizer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 冠词删除不得吞掉声明名（#243）。夹具与 aster-lang-ts 共享（parity），两仓字节一致，
 * 不得单方面修改；逐条断言编译成功与 error 级诊断码序列。
 */
class ArticleFixtureTest {

  static Stream<Arguments> fixtures() throws Exception {
    try (InputStream in = ArticleFixtureTest.class.getResourceAsStream("/canonicalizer/article-fixtures.json")) {
      JsonNode root = new ObjectMapper().readTree(in);
      List<Arguments> out = new ArrayList<>();
      for (JsonNode f : root.get("cases")) {
        out.add(Arguments.of(f.get("name").asText(), f.get("source").asText(), f.get("success").asBoolean(), f.get("errors")));
      }
      return out.stream();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void matchesFixture(String name, String source, boolean success, JsonNode expectedErrors) {
    var parser = new AsterParser(new CommonTokenStream(new AsterCustomLexer(
      CharStreams.fromString(new Canonicalizer().canonicalize(source)))));
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        throw new IllegalStateException("line " + line + ":" + col + " " + msg);
      }
    });
    var ast = assertDoesNotThrow(() -> new AstBuilder().visitModule(parser.module()), name);
    var core = new CoreLowering().lowerModule(ast);
    var actual = new TypeChecker().typecheckModule(core).stream()
      .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
      .map(d -> d.code().code())
      .toList();
    var expected = new ArrayList<String>();
    expectedErrors.forEach(e -> expected.add(e.isArray() ? e.get(0).asText() : e.asText()));
    assertEquals(expected, actual, name);
    assertEquals(success, actual.isEmpty(), name);
  }
}
