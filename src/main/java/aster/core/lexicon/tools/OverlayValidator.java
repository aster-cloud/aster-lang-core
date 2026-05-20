package aster.core.lexicon.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static aster.core.lexicon.tools.LexiconValidationReport.Issue;
import static aster.core.lexicon.tools.LexiconValidationReport.Severity;

/**
 * Overlay-parity validator. Closes the L2 gap noted in
 * aster-cloud/.claude/plan/glossary-contract.md §6.
 *
 * <p>Rules (per plan §6.2):
 * <ol>
 *   <li>Every overlay file present in the backbone (en-US) must exist in the candidate.</li>
 *   <li>Every key in a backbone overlay file must have a value in the candidate's corresponding file.</li>
 *   <li>Keys present in candidate but not backbone are reported as INFO (extra strings are allowed but logged).</li>
 *   <li>Values matching backbone byte-for-byte get a WARNING (likely missed translation).</li>
 * </ol>
 *
 * <p>The G6 cross-check (overlay value vs glossary translation) is handled in a separate
 * validator that consumes the Maven `io.aster:glossary-contract` artifact; not part of this class.
 */
public final class OverlayValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Run the parity check.
     *
     * @param candidateOverlaysDir directory containing the candidate locale's `overlays/*.json`
     * @param backboneOverlaysDir directory containing the backbone (en-US) `overlays/*.json`
     * @param candidateLocaleId BCP-47-ish id used in the report (e.g. "de-DE")
     */
    public LexiconValidationReport validate(
        Path candidateOverlaysDir,
        Path backboneOverlaysDir,
        String candidateLocaleId
    ) {
        List<Issue> issues = new ArrayList<>();

        if (!Files.isDirectory(backboneOverlaysDir)) {
            issues.add(new Issue(Severity.ERROR, "BACKBONE_OVERLAYS_MISSING",
                "Backbone overlays dir not found: " + backboneOverlaysDir,
                "Ensure aster-lang-en overlays are present before validating other locales"));
            return new LexiconValidationReport(candidateLocaleId, issues);
        }
        if (!Files.isDirectory(candidateOverlaysDir)) {
            issues.add(new Issue(Severity.ERROR, "CANDIDATE_OVERLAYS_MISSING",
                "Candidate overlays dir not found: " + candidateOverlaysDir,
                "Create overlays/ directory in your lexicon resource tree"));
            return new LexiconValidationReport(candidateLocaleId, issues);
        }

        Set<String> backboneFiles = listJsonFiles(backboneOverlaysDir);
        Set<String> candidateFiles = listJsonFiles(candidateOverlaysDir);

        // Rule 1: backbone file presence in candidate.
        Set<String> missingFiles = new TreeSet<>(backboneFiles);
        missingFiles.removeAll(candidateFiles);
        for (String f : missingFiles) {
            issues.add(new Issue(Severity.ERROR, "OVERLAY_FILE_MISSING",
                "Backbone overlay file '" + f + "' is missing in " + candidateLocaleId,
                "Create overlays/" + f + " with translations for every key present in the backbone version"));
        }

        // Rules 2 + 3 + 4: per-file key + value comparison for files present in both.
        Set<String> commonFiles = new TreeSet<>(backboneFiles);
        commonFiles.retainAll(candidateFiles);
        for (String fname : commonFiles) {
            comparePerFile(
                backboneOverlaysDir.resolve(fname),
                candidateOverlaysDir.resolve(fname),
                fname,
                issues
            );
        }

        // Informational: candidate has overlay files not in backbone.
        Set<String> extraFiles = new TreeSet<>(candidateFiles);
        extraFiles.removeAll(backboneFiles);
        for (String f : extraFiles) {
            issues.add(new Issue(Severity.INFO, "OVERLAY_FILE_NOT_IN_BACKBONE",
                "Overlay file '" + f + "' exists in " + candidateLocaleId + " but not in backbone",
                "Either propagate to backbone (aster-lang-en) so it becomes required everywhere, or accept locale-specific extension"));
        }

        return new LexiconValidationReport(candidateLocaleId, issues);
    }

    private void comparePerFile(Path backbonePath, Path candidatePath, String fname, List<Issue> issues) {
        JsonNode backbone, candidate;
        try {
            backbone = mapper.readTree(Files.readString(backbonePath));
            candidate = mapper.readTree(Files.readString(candidatePath));
        } catch (IOException e) {
            issues.add(new Issue(Severity.ERROR, "OVERLAY_PARSE_FAILED",
                "Failed to read overlay '" + fname + "': " + e.getMessage(),
                "Ensure the file is valid JSON"));
            return;
        }

        // Most overlay files have a top-level "texts" or "rules" object; some are flat maps.
        // We compare key sets at the first object level. Nested objects are walked.
        Set<String> backboneKeys = flattenKeys(backbone, "");
        Set<String> candidateKeys = flattenKeys(candidate, "");

        Set<String> missing = new TreeSet<>(backboneKeys);
        missing.removeAll(candidateKeys);
        for (String k : missing) {
            issues.add(new Issue(Severity.ERROR, "OVERLAY_KEY_MISSING",
                "Key '" + k + "' from overlay '" + fname + "' is missing in candidate",
                "Add the key to overlays/" + fname + " with a translation"));
        }

        // Value byte-identical to backbone → likely untranslated.
        for (String k : backboneKeys) {
            if (!candidateKeys.contains(k)) continue;
            JsonNode b = pathGet(backbone, k);
            JsonNode c = pathGet(candidate, k);
            if (b == null || c == null) continue;
            if (b.isTextual() && c.isTextual() && b.asText().equals(c.asText())) {
                issues.add(new Issue(Severity.WARNING, "OVERLAY_VALUE_UNTRANSLATED",
                    "Key '" + k + "' in '" + fname + "' has byte-identical value to backbone — likely missed translation",
                    "Either translate, or explicitly mark do-not-translate via a sibling .gloss/skip file (future work)"));
            }
        }

        // Candidate-only keys.
        Set<String> extra = new TreeSet<>(candidateKeys);
        extra.removeAll(backboneKeys);
        for (String k : extra) {
            issues.add(new Issue(Severity.INFO, "OVERLAY_KEY_NOT_IN_BACKBONE",
                "Key '" + k + "' in '" + fname + "' exists in candidate but not in backbone",
                "Either propagate to backbone or accept locale-specific extension"));
        }
    }

    private static Set<String> listJsonFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            return new TreeSet<>();
        }
    }

    /** Flatten a JSON object's leaves into dotted key paths. Arrays are indexed. */
    private static Set<String> flattenKeys(JsonNode node, String prefix) {
        Set<String> out = new TreeSet<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                if (e.getValue().isObject() || e.getValue().isArray()) {
                    out.addAll(flattenKeys(e.getValue(), key));
                } else {
                    out.add(key);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                out.addAll(flattenKeys(node.get(i), prefix + "[" + i + "]"));
            }
        }
        return out;
    }

    private static JsonNode pathGet(JsonNode root, String dottedPath) {
        JsonNode cur = root;
        for (String part : dottedPath.split("\\.")) {
            if (cur == null) return null;
            cur = cur.get(part);
        }
        return cur;
    }
}
