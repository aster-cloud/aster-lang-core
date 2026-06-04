package aster.core.dualengine;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Core IR fingerprint emitter for the dual-engine parity gate.
 *
 * <p>This is not a JUnit assertion — it's a CLI shim. The {@code @Test} method
 * checks for a {@code -Dparity.ir.input=...} system property; when absent the
 * test is a trivial no-op so ordinary {@code ./gradlew test} runs aren't
 * affected. When present, the system property points at a stdin-like input
 * file whose lines are absolute paths to {@code .aster} samples; the runner
 * loads each one through the full Java pipeline
 * (Canonicalize → ANTLR Parse → AstBuilder → CoreLowering), then emits a
 * compact "fingerprint" JSON object per sample to {@code parity.ir.output}.
 *
 * <p>The fingerprint is intentionally structural, not field-by-field:
 * <ul>
 *   <li>{@code declCount} — number of top-level decls in the module</li>
 *   <li>{@code declKinds} — sorted map of {@code kind → count}</li>
 *   <li>{@code declNames} — sorted list of declared symbol names</li>
 *   <li>{@code moduleName} — the module's declared name</li>
 * </ul>
 *
 * <p>Why a fingerprint and not raw JSON parity? The Java and TS Core IRs
 * use different field names in places (e.g. {@code Import.path/alias} vs
 * {@code Import.name/asName}) even though their {@code kind} discriminators
 * align. Aligning every field requires an ADR-level cross-team coordination;
 * the fingerprint catches the gross structural drifts (decl count mismatches,
 * kinds present on one side but not the other, missing names) the Phase B
 * gate cares about today. Field-level parity is Phase B v2.
 *
 * <p>Invocation:
 * <pre>
 *   ./gradlew test --tests CoreIrFingerprintCli \
 *     -Dparity.ir.input=/tmp/samples.txt \
 *     -Dparity.ir.output=/tmp/java-fp.jsonl
 * </pre>
 *
 * <p>The output is JSONL (one fingerprint per line) — easier to stream and
 * parse than a single huge JSON array.
 */
@Tag("parity-ir")
class CoreIrFingerprintCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emitFingerprintsWhenInvoked() throws Exception {
        String inputProp = System.getProperty("parity.ir.input");
        String outputProp = System.getProperty("parity.ir.output");
        if (inputProp == null || outputProp == null) {
            // No-op when run as part of the regular test suite. The parity
            // runner sets both properties before invoking gradle test.
            return;
        }

        Path inputPath = Paths.get(inputProp);
        Path outputPath = Paths.get(outputProp);
        if (!Files.exists(inputPath)) {
            throw new IllegalStateException("parity.ir.input not found: " + inputPath);
        }

        Canonicalizer canonicalizer = new Canonicalizer();
        CoreLowering lowering = new CoreLowering();

        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8);
             var writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String samplePath = line.trim();
                if (samplePath.isEmpty()) continue;

                ObjectNode record = MAPPER.createObjectNode();
                record.put("path", samplePath);
                try {
                    String source = Files.readString(Paths.get(samplePath), StandardCharsets.UTF_8);
                    String canonical = canonicalizer.canonicalize(source);

                    AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
                    CommonTokenStream tokens = new CommonTokenStream(lexer);
                    tokens.fill();
                    tokens.seek(0);

                    AsterParser parser = new AsterParser(tokens);
                    AsterParser.ModuleContext moduleCtx = parser.module();

                    AstBuilder builder = new AstBuilder();
                    aster.core.ast.Module ast = builder.visitModule(moduleCtx);

                    CoreModel.Module coreModule = lowering.lowerModule(ast);
                    JsonNode coreJson = MAPPER.valueToTree(coreModule);
                    record.put("ok", true);
                    record.set("fingerprint", fingerprint(coreJson));
                } catch (Throwable t) {
                    // A failure on one sample shouldn't abort the whole batch.
                    // The parity runner needs every sample's verdict so it can
                    // report which one regressed.
                    record.put("ok", false);
                    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    record.put("error", msg.length() > 240 ? msg.substring(0, 240) : msg);
                }
                writer.write(MAPPER.writeValueAsString(record));
                writer.newLine();
            }
        }
    }

    /**
     * Build a compact structural fingerprint of a lowered module. Operates
     * on {@code JsonNode} so the same logic can be ported to the TS side
     * with minimal divergence (just swap MAPPER calls for plain object
     * lookups).
     */
    private static JsonNode fingerprint(JsonNode core) {
        ObjectNode fp = MAPPER.createObjectNode();
        fp.put("moduleName", core.path("name").asText(""));

        JsonNode decls = core.path("decls");
        int declCount = decls.isArray() ? decls.size() : 0;
        fp.put("declCount", declCount);

        TreeMap<String, Integer> kindCounts = new TreeMap<>();
        List<String> names = new ArrayList<>();
        if (decls.isArray()) {
            for (JsonNode decl : decls) {
                String kind = decl.path("kind").asText("Unknown");
                kindCounts.merge(kind, 1, Integer::sum);
                JsonNode name = decl.path("name");
                if (name.isTextual()) names.add(name.asText());
            }
        }
        names.sort(String::compareTo);

        ObjectNode kindsNode = MAPPER.createObjectNode();
        for (var e : kindCounts.entrySet()) kindsNode.put(e.getKey(), e.getValue());
        fp.set("declKinds", kindsNode);

        ArrayNode namesNode = MAPPER.createArrayNode();
        for (String n : names) namesNode.add(n);
        fp.set("declNames", namesNode);

        return fp;
    }
}
