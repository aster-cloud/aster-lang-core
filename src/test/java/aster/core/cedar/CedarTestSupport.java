package aster.core.cedar;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.parser.AstBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.List;

/** Cedar 导出测试共用辅助：源码到 Core IR 的完整管线、函数查找与去 origin 序列化。 */
final class CedarTestSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CedarTestSupport() {}

  // 英文默认词法包的完整管线
  static CoreModel.Module lower(String src) {
    return lower(src, LexiconRegistry.getInstance().getOrThrow("en-US"));
  }

  // 规范化 → 词法 → 解析（语法错误即抛 IllegalStateException）→ AST → Core IR
  static CoreModel.Module lower(String src, Lexicon lexicon) {
    String canonical = new Canonicalizer(lexicon).canonicalize(src);
    var tokens = new CommonTokenStream(new AsterCustomLexer(CharStreams.fromString(canonical)));
    var parser = new AsterParser(tokens);
    List<String> errors = new ArrayList<>();
    parser.removeErrorListeners();
    parser.addErrorListener(new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
        errors.add("L" + line + ":" + col + " " + msg);
      }
    });
    var moduleCtx = parser.module();
    if (!errors.isEmpty()) {
      throw new IllegalStateException("syntax errors " + errors + "\ncanonical:\n" + canonical);
    }
    return new CoreLowering().lowerModule(new AstBuilder().visitModule(moduleCtx));
  }

  // 按名查找顶层函数，找不到即失败
  static CoreModel.Func func(CoreModel.Module m, String name) {
    return m.decls.stream()
        .filter(d -> d instanceof CoreModel.Func f && name.equals(f.name))
        .map(d -> (CoreModel.Func) d)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("function " + name + " not found"));
  }

  // 序列化并去掉所有 origin 键，便于结构比较
  static JsonNode json(CoreModel.Module core) {
    return stripOrigin(MAPPER.valueToTree(core));
  }

  private static JsonNode stripOrigin(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      ObjectNode out = MAPPER.createObjectNode();
      obj.fields().forEachRemaining(e -> {
        if (!e.getKey().equals("origin")) out.set(e.getKey(), stripOrigin(e.getValue()));
      });
      return out;
    }
    if (node instanceof ArrayNode arr) {
      ArrayNode out = MAPPER.createArrayNode();
      arr.forEach(child -> out.add(stripOrigin(child)));
      return out;
    }
    return node;
  }
}
