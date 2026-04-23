package com.mirador.observability.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link QualityReportGenerator#main(String[])} — the CLI
 * that pre-aggregates tool outputs into
 * {@code target/classes/META-INF/quality-build-report.json}.
 *
 * <p>Pinned contracts :
 *   - main() exits 0 (no thrown exception) when all parser inputs are
 *     missing — each section reports {available: false} in the output
 *     JSON rather than aborting (per ADR-0052 Phase Q-2)
 *   - Output JSON contains the 11 expected top-level keys (generatedAt
 *     + 10 sections)
 *   - File is created at the documented path under target/classes/...
 *
 * <p>Note : this test EXECUTES main() and writes to the real filesystem.
 * The output path is fixed (target/classes/META-INF/quality-build-report.json)
 * so it overwrites whatever was there from a previous run — same contract
 * as the maven exec-maven-plugin invocation. Tests run from the project
 * root so the relative path resolves correctly.
 */
class QualityReportGeneratorTest {

    private static final String OUTPUT_PATH = "target/classes/META-INF/quality-build-report.json";

    @Test
    void main_writesJsonFileAtDocumentedPath_with11TopLevelKeys() throws Exception {
        // Ensure target/classes exists (it does after compile phase, but
        // belt-and-suspenders for an isolated test run).
        File target = new File("target/classes/META-INF");
        target.mkdirs();

        // Run the CLI — must NOT throw under any input state.
        QualityReportGenerator.main(new String[]{});

        // Verify the output file was created.
        File output = new File(OUTPUT_PATH);
        assertThat(output).exists();
        assertThat(output.length()).isGreaterThan(0);

        // Parse it and verify the 11 documented top-level keys.
        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = mapper.readValue(output, Map.class);

        assertThat(report).containsKeys(
                "generatedAt",
                "tests", "coverage", "bugs", "pmd", "checkstyle", "owasp",
                "pitest", "dependencies", "licenses", "metrics");
    }

    @Test
    void main_eachSectionHasAvailableFlag_evenWhenSourceFileIsMissing() throws Exception {
        // Pinned: per ADR-0052 Phase Q-2, missing parser inputs result in
        // {available: false, ...} for that section — NOT a hard failure.
        // The downstream actuator endpoint relies on this contract to
        // render "Run mvn prepare-package" messages instead of crashing.
        QualityReportGenerator.main(new String[]{});

        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = mapper.readValue(new File(OUTPUT_PATH), Map.class);

        for (String key : new String[]{
                "tests", "coverage", "bugs", "pmd", "checkstyle", "owasp",
                "pitest", "dependencies", "licenses", "metrics"
        }) {
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) report.get(key);
            assertThat(section).isNotNull();
            assertThat(section).containsKey("available");
        }
    }

    @Test
    void main_generatedAtFollowsIsoFormat() throws Exception {
        // Pinned: the format yyyy-MM-dd HH:mm:ss is the contract with
        // the actuator endpoint + Angular dashboard. Other formats
        // would parse-error in the UI's Date constructor.
        QualityReportGenerator.main(new String[]{});

        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = mapper.readValue(new File(OUTPUT_PATH), Map.class);

        String generatedAt = (String) report.get("generatedAt");
        assertThat(generatedAt).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }
}
