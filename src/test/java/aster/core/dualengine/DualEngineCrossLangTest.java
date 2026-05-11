package aster.core.dualengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import cloud.aster.test.CorpusLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language dual-engine equivalence test (Phase 3A-2; corpus extraction).
 *
 * <p>Bridges to the TypeScript engine via {@code ProcessBuilder} + stdio:
 *   <ol>
 *     <li>Spawns {@code node aster-lang-ts/scripts/dual-engine-runner.mjs}</li>
 *     <li>Pipes a JSON request {source, entry, input} → stdin</li>
 *     <li>Reads {success, value} ← stdout</li>
 *     <li>Compares value vs {@code expectedOutput} in the corpus</li>
 *   </ol>
 *
 * <p><b>Corpus source</b>: {@code cloud.aster-lang:aster-lang-test} (tier1 only;
 * tier2/tier3 are unsuitable for equivalence testing by construction).
 *
 * <p><b>Tagged {@code crosslang}</b> — default {@code ./gradlew test} excludes this
 * because it requires Node.js + a built aster-lang-ts artifact. To run:
 * <pre>
 *   cd aster-lang-ts && pnpm build
 *   cd ../aster-lang-core && ./gradlew crosslangTest
 * </pre>
 */
@Tag("crosslang")
@DisplayName("Dual-engine cross-language equivalence (TS PEG vs tier1 golden expectedOutput)")
class DualEngineCrossLangTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    @TestFactory
    @DisplayName("Each tier1 sample with .cases.json must produce identical output in TS engine")
    Stream<DynamicContainer> tsEngineMatchesExpected() throws Exception {
        Path runnerScript = resolveRunnerScript();

        if (!Files.exists(runnerScript)) {
            return Stream.of(DynamicContainer.dynamicContainer(
                "TS runner unavailable — skip",
                Stream.of(DynamicTest.dynamicTest("setup", () -> {
                    System.err.println("[crosslang] TS runner not found at " + runnerScript
                        + ". Run `cd aster-lang-ts && pnpm build` then retry.");
                }))
            ));
        }

        List<CorpusLoader.Sample> tier1 = CorpusLoader.listTier(CorpusLoader.Tier.TIER1);
        List<DynamicContainer> containers = new ArrayList<>();
        for (CorpusLoader.Sample sample : tier1) {
            JsonNode cases = sample.readCases();
            if (cases == null) continue; // tier1 syntax-only samples have no golden
            containers.add(buildPolicyContainer(sample, cases, runnerScript));
        }
        return containers.stream();
    }

    private DynamicContainer buildPolicyContainer(CorpusLoader.Sample sample, JsonNode cases, Path runner) {
        String entry = cases.path("entry").asText();
        JsonNode caseArr = cases.path("cases");
        String source = sample.readSource();
        String displayName = sample.resourcePath
            .replaceFirst("^corpus/tier1-equivalence/policies/", "");

        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < caseArr.size(); i++) {
            JsonNode c = caseArr.get(i);
            String caseName = c.path("name").asText("(unnamed " + i + ")");
            JsonNode input = c.path("input");
            JsonNode expected = c.path("expectedOutput");

            tests.add(DynamicTest.dynamicTest(caseName, () -> {
                JsonNode actual = invokeTsEngine(runner, source, entry, input);
                assertThat(actual.path("success").asBoolean())
                    .as("TS engine compilation/evaluation of %s", displayName)
                    .withFailMessage("TS engine reported failure: %s", actual.path("error").asText())
                    .isTrue();
                assertThat(actual.path("value"))
                    .as("TS engine value vs expectedOutput for %s / %s", displayName, caseName)
                    .isEqualTo(expected);
            }));
        }
        return DynamicContainer.dynamicContainer(displayName, tests);
    }

    private JsonNode invokeTsEngine(Path runner, String source, String entry, JsonNode input)
        throws IOException, InterruptedException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("source", source);
        request.put("entry", entry);
        request.set("input", input);

        Path tsRoot = runner.getParent().getParent();
        ProcessBuilder pb = new ProcessBuilder("node", runner.toString())
            .directory(tsRoot.toFile())
            .redirectErrorStream(false);

        Process process = pb.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(MAPPER.writeValueAsBytes(request));
        }

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) stdout.append(line);
        }

        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("TS runner timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
        }

        if (process.exitValue() != 0) {
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) stderr.append(line).append('\n');
            }
            throw new IOException("TS runner exited non-zero: " + stderr);
        }

        return MAPPER.readTree(stdout.toString());
    }

    private Path resolveRunnerScript() {
        Path workspaceRoot = Paths.get("").toAbsolutePath().getParent();
        if (workspaceRoot == null) return Paths.get("../aster-lang-ts/scripts/dual-engine-runner.mjs");
        return workspaceRoot.resolve("aster-lang-ts/scripts/dual-engine-runner.mjs");
    }
}
