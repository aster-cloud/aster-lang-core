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
import java.util.TreeMap;
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

        // Enumerate both dirs separately so the diagnostic names the failing
        // role (backbone vs candidate). The earlier combined try/catch hid
        // which directory was unreadable.
        Set<String> backboneFiles;
        try {
            backboneFiles = listJsonFiles(backboneOverlaysDir);
        } catch (IOException ioe) {
            issues.add(new Issue(Severity.ERROR, "OVERLAY_LIST_FAILED",
                "Failed to enumerate backbone overlays at " + backboneOverlaysDir + ": " + ioe.getMessage(),
                "Check directory permissions and filesystem health"));
            return new LexiconValidationReport(candidateLocaleId, issues);
        }
        Set<String> candidateFiles;
        try {
            candidateFiles = listJsonFiles(candidateOverlaysDir);
        } catch (IOException ioe) {
            issues.add(new Issue(Severity.ERROR, "OVERLAY_LIST_FAILED",
                "Failed to enumerate candidate overlays for " + candidateLocaleId + " at " + candidateOverlaysDir + ": " + ioe.getMessage(),
                "Check directory permissions and filesystem health"));
            return new LexiconValidationReport(candidateLocaleId, issues);
        }

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

        // Structured LeafPath keys guarantee no collisions: keys like {"a.b":"X"}
        // and {"a":{"b":"X"}} hash to distinct paths, so OVERLAY_KEY_MISSING /
        // OVERLAY_KEY_NOT_IN_BACKBONE are reliable even with adversarial keys.
        Map<LeafPath, JsonNode> backboneLeaves = collectLeaves(backbone, "");
        Map<LeafPath, JsonNode> candidateLeaves = collectLeaves(candidate, "");

        Set<LeafPath> missing = new TreeSet<>(backboneLeaves.keySet());
        missing.removeAll(candidateLeaves.keySet());
        for (LeafPath k : missing) {
            issues.add(new Issue(Severity.ERROR, "OVERLAY_KEY_MISSING",
                "Key '" + k.display() + "' from overlay '" + fname + "' is missing in candidate",
                "Add the key to overlays/" + fname + " with a translation"));
        }

        // Value byte-identical to backbone → likely untranslated.
        for (var entry : backboneLeaves.entrySet()) {
            JsonNode c = candidateLeaves.get(entry.getKey());
            if (c == null) continue;
            JsonNode b = entry.getValue();
            if (b.isTextual() && c.isTextual() && b.asText().equals(c.asText())) {
                issues.add(new Issue(Severity.WARNING, "OVERLAY_VALUE_UNTRANSLATED",
                    "Key '" + entry.getKey().display() + "' in '" + fname + "' has byte-identical value to backbone — likely missed translation",
                    "Either translate, or explicitly mark do-not-translate via a sibling .gloss/skip file (future work)"));
            }
        }

        // Candidate-only keys.
        Set<LeafPath> extra = new TreeSet<>(candidateLeaves.keySet());
        extra.removeAll(backboneLeaves.keySet());
        for (LeafPath k : extra) {
            issues.add(new Issue(Severity.INFO, "OVERLAY_KEY_NOT_IN_BACKBONE",
                "Key '" + k.display() + "' in '" + fname + "' exists in candidate but not in backbone",
                "Either propagate to backbone or accept locale-specific extension"));
        }
    }

    /**
     * List {@code *.json} files in {@code dir}. Propagates IO errors so an
     * unreadable directory fails loudly (the previous version returned an
     * empty set and produced a false PASS).
     */
    private static Set<String> listJsonFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * Structured leaf path. Each element is either a field (object key, with
     * the original unescaped string) or an index (array position). Using
     * typed segments instead of a flat display string means that
     * {@code {"a.b":"X"}} and {@code {"a":{"b":"X"}}} hash and compare as
     * <em>different</em> paths, which is necessary for honest parity checks
     * across JSON whose keys may contain '.', '[', ']' or empty strings.
     */
    public static final class LeafPath implements Comparable<LeafPath> {
        public sealed interface Segment permits Field, Index {}
        public record Field(String name) implements Segment {}
        public record Index(int position) implements Segment {}

        private final List<Segment> segments;
        private final String display;

        private LeafPath(List<Segment> segments) {
            this.segments = List.copyOf(segments);
            this.display = renderDisplay(this.segments);
        }

        public static LeafPath root() { return new LeafPath(List.of()); }
        public LeafPath plus(Segment s) {
            List<Segment> next = new ArrayList<>(segments.size() + 1);
            next.addAll(segments); next.add(s);
            return new LeafPath(next);
        }
        public List<Segment> segments() { return segments; }
        public String display() { return display; }

        @Override public String toString() { return display; }
        @Override public boolean equals(Object o) {
            return o instanceof LeafPath p && segments.equals(p.segments);
        }
        @Override public int hashCode() { return segments.hashCode(); }

        /**
         * Sort by structural identity, not just display. Two paths whose
         * displays collide (e.g. flat "a.b" vs nested ["a","b"]) must compare
         * NON-EQUAL, otherwise {@link java.util.TreeSet} would treat them as
         * the same element and silently drop OVERLAY_KEY_MISSING /
         * OVERLAY_KEY_NOT_IN_BACKBONE diagnostics.
         */
        @Override public int compareTo(LeafPath o) {
            int dc = display.compareTo(o.display);
            if (dc != 0) return dc;
            int min = Math.min(segments.size(), o.segments.size());
            for (int i = 0; i < min; i++) {
                Segment a = segments.get(i), b = o.segments.get(i);
                int rank = segmentRank(a) - segmentRank(b);
                if (rank != 0) return rank;
                if (a instanceof Field fa && b instanceof Field fb) {
                    int fc = fa.name().compareTo(fb.name());
                    if (fc != 0) return fc;
                } else if (a instanceof Index ia && b instanceof Index ib) {
                    int ic = Integer.compare(ia.position(), ib.position());
                    if (ic != 0) return ic;
                }
            }
            return Integer.compare(segments.size(), o.segments.size());
        }

        private static int segmentRank(Segment s) { return s instanceof Field ? 0 : 1; }

        private static String renderDisplay(List<Segment> segs) {
            StringBuilder sb = new StringBuilder();
            for (Segment s : segs) {
                if (s instanceof Field f) {
                    if (sb.length() > 0) sb.append('.');
                    sb.append(f.name());
                } else if (s instanceof Index ix) {
                    sb.append('[').append(ix.position()).append(']');
                }
            }
            return sb.toString();
        }
    }

    /**
     * Collect leaves into an ordered map keyed by {@link LeafPath}. The
     * structured key guarantees injective identity — two semantically
     * different paths never collide, even when one uses literal '.' / '['
     * characters in object keys.
     *
     * The {@link JsonNode} reference is the source of truth for value
     * comparison; the display path is only used for diagnostic strings.
     */
    static Map<LeafPath, JsonNode> collectLeaves(JsonNode node, String displayPrefix) {
        Map<LeafPath, JsonNode> out = new TreeMap<>();
        collectLeavesInto(node, LeafPath.root(), out);
        return out;
    }

    private static void collectLeavesInto(JsonNode node, LeafPath path, Map<LeafPath, JsonNode> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                LeafPath next = path.plus(new LeafPath.Field(e.getKey()));
                if (e.getValue().isObject() || e.getValue().isArray()) {
                    collectLeavesInto(e.getValue(), next, out);
                } else {
                    out.put(next, e.getValue());
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectLeavesInto(node.get(i), path.plus(new LeafPath.Index(i)), out);
            }
        } else if (path.segments().size() > 0) {
            out.put(path, node);
        }
    }

    /**
     * Resolve a {@code flattenKeys}-style path (e.g. {@code "texts.items[0].label"})
     * back to its {@link JsonNode}.
     *
     * <p>The previous implementation used a naive {@code split(".")} which failed
     * on array indices because {@code node.get("items[0]")} returns null.
     * That silently dropped every array element from the OVERLAY_VALUE_UNTRANSLATED
     * check. This walker tokenises on {@code .} and {@code [N]} explicitly so
     * arrays participate in the parity scan.
     */
    static JsonNode pathGet(JsonNode root, String path) {
        if (path.isEmpty()) return null;
        // Leading separators or consecutive separators mean an empty token,
        // which is malformed input — fail closed.
        if (path.charAt(0) == '.' || path.charAt(0) == '[') return null;
        JsonNode cur = root;
        int i = 0;
        StringBuilder token = new StringBuilder();
        boolean justClosedBracket = false;
        while (i <= path.length()) {
            char c = i < path.length() ? path.charAt(i) : '\0';
            if (c == '.' || c == '[' || c == '\0') {
                if (token.length() > 0) {
                    if (cur == null) return null;
                    cur = cur.get(token.toString());
                    token.setLength(0);
                } else if (!justClosedBracket && c != '\0') {
                    // empty token between two separators (e.g. ".." or ".[") → malformed
                    return null;
                }
                justClosedBracket = false;
                if (c == '[') {
                    int end = path.indexOf(']', i);
                    if (end < 0) return null;
                    int idx;
                    try { idx = Integer.parseInt(path.substring(i + 1, end)); }
                    catch (NumberFormatException nfe) { return null; }
                    if (cur == null || !cur.isArray() || idx < 0 || idx >= cur.size()) return null;
                    cur = cur.get(idx);
                    i = end;
                    justClosedBracket = true;
                }
                i++;
            } else {
                token.append(c);
                i++;
            }
        }
        return cur;
    }
}
