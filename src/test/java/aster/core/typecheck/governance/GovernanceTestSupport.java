package aster.core.typecheck.governance;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;

/** 治理相关测试共用的编译链工具：源码 → 规范化 → 解析 → 降级 → 类型检查。 */
final class GovernanceTestSupport {
  private GovernanceTestSupport() {}

  static CoreModel.Module lower(String body) {
    String src = "Module probe.\n\n" + body + "\n";
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer); tokens.fill(); tokens.seek(0);
    var parser = new AsterParser(tokens); parser.removeErrorListeners();
    var ast = new AstBuilder().visitModule(parser.module());
    return new CoreLowering().lowerModule(ast);
  }

  static List<Diagnostic> diagnose(String body) {
    return new TypeChecker().typecheckModule(lower(body));
  }

  static long count(List<Diagnostic> d, ErrorCode code) {
    return d.stream().filter(x -> x.code() == code).count();
  }

  static boolean has(List<Diagnostic> d, ErrorCode code) {
    return d.stream().anyMatch(x -> x.code() == code);
  }
}
