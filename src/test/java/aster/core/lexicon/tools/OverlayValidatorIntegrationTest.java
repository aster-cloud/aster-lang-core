package aster.core.lexicon.tools;

import aster.core.lexicon.tools.LexiconValidationReport.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-repo integration: validate the real `aster-lang-{en,zh,de}` overlay
 * directories against each other. Skips when the sibling repos aren't
 * present (CI environments without checkouts will not run these).
 *
 * <p>Treat this as a smoke test for `OverlayValidator` against production
 * data — the unit tests in `OverlayValidatorTest` use synthetic fixtures
 * and don't depend on filesystem layout.</p>
 */
@DisplayName("OverlayValidator — cross-repo integration with aster-lang-{en,zh,de}")
class OverlayValidatorIntegrationTest {

    private final OverlayValidator validator = new OverlayValidator();

    private static Path langRepo(String locale) {
        return Path.of(System.getProperty("user.dir"), "..", "aster-lang-" + locale,
            "src", "main", "resources", "overlays");
    }

    static boolean langReposPresent() {
        return Files.isDirectory(langRepo("en"))
            && Files.isDirectory(langRepo("zh"))
            && Files.isDirectory(langRepo("de"));
    }

    /**
     * The integration tests are diagnostic, not gating. They exercise
     * OverlayValidator against real lang-* repos and report findings.
     * Failures are expected when lang-* backfill hasn't happened yet
     * (which is exactly why G4 exists). The tests assert *file-level*
     * presence (the cheap fix) but report *key-level* findings to logs.
     */
    @Test
    @EnabledIf("langReposPresent")
    @DisplayName("aster-lang-zh has all backbone overlay files")
    void zhHasAllBackboneFiles() throws IOException {
        var report = validator.validate(langRepo("zh"), langRepo("en"), "zh-CN");
        var fileLevelErrors = report.issues().stream()
            .filter(i -> i.severity() == Severity.ERROR)
            .filter(i -> i.code().equals("OVERLAY_FILE_MISSING"))
            .toList();
        assertThat(fileLevelErrors)
            .as("zh-CN must have every overlay file the backbone has")
            .isEmpty();
        // Log key-level findings for visibility (lang team to triage).
        report.issues().stream()
            .filter(i -> i.code().equals("OVERLAY_KEY_MISSING"))
            .forEach(i -> System.out.println("[zh-CN key-gap] " + i.message()));
    }

    @Test
    @EnabledIf("langReposPresent")
    @DisplayName("aster-lang-de has all backbone overlay files (post-G4 backfill)")
    void deHasAllBackboneFiles() throws IOException {
        var report = validator.validate(langRepo("de"), langRepo("en"), "de-DE");
        var fileLevelErrors = report.issues().stream()
            .filter(i -> i.severity() == Severity.ERROR)
            .filter(i -> i.code().equals("OVERLAY_FILE_MISSING"))
            .toList();
        assertThat(fileLevelErrors)
            .as("de-DE must have every overlay file the backbone has after G4 backfill (lsp-ui-texts.json)")
            .isEmpty();
        report.issues().stream()
            .filter(i -> i.code().equals("OVERLAY_KEY_MISSING"))
            .forEach(i -> System.out.println("[de-DE key-gap] " + i.message()));
    }
}
