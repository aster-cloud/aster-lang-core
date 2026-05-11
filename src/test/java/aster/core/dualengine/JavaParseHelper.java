package aster.core.dualengine;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone Java parse helper for the classify-existing migration script.
 *
 * <p>Reads one absolute .aster file path per line on stdin; writes
 * {@code OK} or {@code FAIL: <first error>} per input on stdout.
 *
 * <p>Usage from Node:
 * <pre>
 *   echo "/abs/path/to/file.aster" | java -cp ... aster.core.dualengine.JavaParseHelper
 * </pre>
 *
 * <p>Why exists: classify-existing.mjs needs to know whether each Java corpus
 * file is actually accepted by the Java grammar. Spawning gradle test per
 * file would be too slow; this helper batches them in one JVM.
 */
public final class JavaParseHelper {

    private JavaParseHelper() {}

    public static void main(String[] args) throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    String source = Files.readString(Path.of(line));
                    String err = tryParse(source);
                    if (err == null) {
                        System.out.println("OK");
                    } else {
                        System.out.println("FAIL: " + err);
                    }
                } catch (Exception e) {
                    System.out.println("FAIL: THROWN: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                System.out.flush();
            }
        }
    }

    /** Returns null on success, or first-error string on failure. */
    private static String tryParse(String source) {
        List<String> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
                if (errors.isEmpty()) errors.add("L" + line + ":" + col + " " + msg);
            }
        };
        try {
            AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(source));
            lexer.removeErrorListeners();
            lexer.addErrorListener(listener);

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            tokens.seek(0);

            AsterParser parser = new AsterParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(listener);
            parser.module();
        } catch (Throwable t) {
            if (errors.isEmpty()) errors.add("THROWN: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return errors.isEmpty() ? null : errors.get(0);
    }
}
