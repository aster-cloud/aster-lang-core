package aster.core.typecheck.governance;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.VocabularyLoader;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreModel;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.resolve.NameResolution;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;

/** 治理相关测试共用的编译链工具：源码 → 规范化 → 解析 → 降级 → 类型检查。 */
final class GovernanceTestSupport {
  private GovernanceTestSupport() {}

  static CoreModel.Module lower(String body) {
    return lowerSource("Module probe.\n\n" + body + "\n");
  }

  /** 完整源码（自带 Module 头，可含 Profile 声明）。 */
  static CoreModel.Module lowerSource(String src) {
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer); tokens.fill(); tokens.seek(0);
    var parser = new AsterParser(tokens); parser.removeErrorListeners();
    var ast = new AstBuilder().visitModule(parser.module());
    return new CoreLowering().lowerModule(ast);
  }

  /**
   * 完整编译通道（与 CoreIrFingerprintCli 同一路径）：按词法表规范化 → 解析 → R1 → lower → R2 → inputRefs。
   * lexicon 为 null 时为 en-US；vocabularyJson 为 null 时不带领域词汇。任一 E8xx 抛 NameResolutionException。
   */
  static CoreModel.Module resolveSource(String src, String lexicon, String vocabularyJson) {
    VocabularyScopes scopes;
    try {
      scopes = vocabularyJson == null ? null : VocabularyScopes.build(VocabularyLoader.loadFromString(vocabularyJson));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Canonicalizer canonicalizer = lexicon == null && scopes == null
        ? new Canonicalizer()
        : new Canonicalizer(LexiconRegistry.getInstance().getOrThrow(lexicon == null ? "en-US" : lexicon), scopes);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonicalizer.canonicalize(src)));
    var tokens = new CommonTokenStream(lexer); tokens.fill(); tokens.seek(0);
    var parser = new AsterParser(tokens); parser.removeErrorListeners();
    var ast = new AstBuilder().visitModule(parser.module());
    return NameResolution.resolveAndLowerChecked(ast, new ResolutionContext(scopes, null)).core();
  }

  static List<Diagnostic> diagnose(String body) {
    return diagnoseSource("Module probe.\n\n" + body + "\n");
  }

  static List<Diagnostic> diagnoseSource(String fullSource) {
    return new TypeChecker().typecheckModule(lowerSource(fullSource));
  }

  /** 声明了档案的探针模块源码。 */
  static String profiled(String id, String body) {
    return "Module probe.\nProfile \"" + id + "\".\n\n" + body + "\n";
  }

  /** 拼接某错误码的全部消息，便于断言包含关系。 */
  static String messages(List<Diagnostic> d, ErrorCode code) {
    return d.stream().filter(x -> x.code() == code).map(Diagnostic::message).collect(Collectors.joining("\n"));
  }

  static long count(List<Diagnostic> d, ErrorCode code) {
    return d.stream().filter(x -> x.code() == code).count();
  }

  static boolean has(List<Diagnostic> d, ErrorCode code) {
    return d.stream().anyMatch(x -> x.code() == code);
  }
}
