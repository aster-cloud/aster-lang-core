package aster.core.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D13 unit tests for the canonical-source normalizer used in
 * {@link CanonicalizeGoldenTest}. These run unconditionally so the
 * normalization contract is pinned even when the fixture directory
 * isn't checked out (which is the typical CI condition that skips
 * the parent equivalence test).
 */
class CanonicalNormalizeTest {

    @Test
    @DisplayName("strips trailing whitespace on every line")
    void stripsTrailingWhitespace() {
        String input = "Module foo.   \nRule bar:\t\t\n  return 0.";
        String got = CanonicalizeGoldenTest.normalizeCanonicalSource(input);
        assertThat(got).isEqualTo("Module foo.\nRule bar:\n  return 0.");
    }

    @Test
    @DisplayName("normalizes CRLF to LF")
    void normalizesCrlf() {
        String input = "Module foo.\r\nRule bar:\r\n  return 0.\r\n";
        String got = CanonicalizeGoldenTest.normalizeCanonicalSource(input);
        assertThat(got).isEqualTo("Module foo.\nRule bar:\n  return 0.");
    }

    @Test
    @DisplayName("strips UTF-8 BOM")
    void stripsBom() {
        String input = "﻿Module foo.";
        String got = CanonicalizeGoldenTest.normalizeCanonicalSource(input);
        assertThat(got).isEqualTo("Module foo.");
    }

    @Test
    @DisplayName("collapses trailing blank lines")
    void collapsesTrailingBlankLines() {
        String input = "Module foo.\n\n\n\n";
        String got = CanonicalizeGoldenTest.normalizeCanonicalSource(input);
        assertThat(got).isEqualTo("Module foo.");
    }

    @Test
    @DisplayName("preserves leading indentation (only strips trailing)")
    void preservesLeadingIndent() {
        String input = "Rule bar:\n    return 0.";
        String got = CanonicalizeGoldenTest.normalizeCanonicalSource(input);
        assertThat(got).isEqualTo("Rule bar:\n    return 0.");
    }

    @Test
    @DisplayName("idempotent: normalize(normalize(x)) == normalize(x)")
    void idempotent() {
        String input = "﻿Module foo.   \r\n\r\nRule bar:\r\n  return 0.\r\n\r\n";
        String once = CanonicalizeGoldenTest.normalizeCanonicalSource(input);
        String twice = CanonicalizeGoldenTest.normalizeCanonicalSource(once);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    @DisplayName("null input → empty string (defensive)")
    void nullSafe() {
        String got = CanonicalizeGoldenTest.normalizeCanonicalSource(null);
        assertThat(got).isEqualTo("");
    }
}
