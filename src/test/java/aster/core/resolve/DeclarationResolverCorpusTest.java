package aster.core.resolve;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.model.Diagnostic;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R1 在无词汇时不得对既有语料产生诊断（ADR 0051 迁移安全网，对应 TS 端 388 个文件 0 诊断）。
 * 语料来自同级仓库 aster-lang-test；缺失时跳过，无法按 en-US 解析的文件不计入。
 */
class DeclarationResolverCorpusTest {

    private static final Path CORPUS = Path.of("..", "aster-lang-test", "corpus");

    @Test
    void parseableCorpusHasNoDiagnosticsWithoutVocabulary() throws IOException {
        assumeTrue(Files.isDirectory(CORPUS), "缺少 aster-lang-test/corpus");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CORPUS)) {
            files = walk.filter(p -> p.toString().endsWith(".aster")).sorted().toList();
        }
        int parsed = 0;
        List<String> findings = new ArrayList<>();
        for (Path file : files) {
            Module module = tryParse(Files.readString(file));
            if (module == null) continue;
            parsed++;
            for (Diagnostic d : DeclarationResolver.resolve(module, ResolutionContext.none()).diagnostics()) {
                findings.add(CORPUS.relativize(file) + ": " + d.code().code() + " " + d.message());
            }
        }
        System.out.println("R1 corpus: files=" + files.size() + " parsed=" + parsed + " diagnostics=" + findings.size());
        assertEquals(List.of(), findings);
    }

    /** 走生产 canonicalize→lex→parse 路径（en-US）；语法错误或构建异常返回 null。 */
    private static Module tryParse(String source) {
        List<String> errors = new ArrayList<>();
        BaseErrorListener collect = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
                errors.add(msg);
            }
        };
        try {
            String canonical = new Canonicalizer(LexiconRegistry.getInstance().getOrThrow("en-US"), null).canonicalize(source);
            AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
            lexer.removeErrorListeners();
            lexer.addErrorListener(collect);
            AsterParser parser = new AsterParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(collect);
            AsterParser.ModuleContext tree = parser.module();
            return errors.isEmpty() ? new AstBuilder().visitModule(tree) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
