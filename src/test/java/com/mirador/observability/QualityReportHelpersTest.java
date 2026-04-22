package com.mirador.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirador.observability.quality.parsers.OwaspReportParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the CVE-cleaning helpers now hosted in
 * {@link OwaspReportParser} (Phase B-1 split, 2026-04-22). These cover the
 * string/regex logic without loading the full Spring context.
 */
class QualityReportHelpersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── cleanCveId ──────────────────────────────────────────────────────────

    @Test
    void cleanCveId_nullRawName_returnsUnknown() {
        assertThat(OwaspReportParser.cleanCveId(null, null)).isEqualTo("UNKNOWN");
    }

    @Test
    void cleanCveId_blankRawName_returnsUnknown() {
        assertThat(OwaspReportParser.cleanCveId("   ", null)).isEqualTo("UNKNOWN");
    }

    @Test
    void cleanCveId_properCvePattern_returnedAsIs() {
        assertThat(OwaspReportParser.cleanCveId("CVE-2026-12345", null))
                .isEqualTo("CVE-2026-12345");
    }

    @Test
    void cleanCveId_ghsaInReferenceUrl_extractsGhsaId() {
        ArrayNode refs = MAPPER.createArrayNode();
        ObjectNode ref = MAPPER.createObjectNode();
        ref.put("url", "https://github.com/advisories/GHSA-abcd-1234-efgh");
        refs.add(ref);

        assertThat(OwaspReportParser.cleanCveId("## Some markdown title", refs))
                .isEqualTo("GHSA-abcd-1234-efgh");
    }

    @Test
    void cleanCveId_noGhsaInRefs_fallsBackToFirstLine() {
        ArrayNode refs = MAPPER.createArrayNode();
        ObjectNode ref = MAPPER.createObjectNode();
        ref.put("url", "https://example.com/no-ghsa-here");
        refs.add(ref);

        String rawName = "# Header\nA short description";
        // Header line is skipped, "A short description" is the first non-header line
        assertThat(OwaspReportParser.cleanCveId(rawName, refs))
                .isEqualTo("A short description");
    }

    @Test
    void cleanCveId_longFirstLine_truncatedTo40Chars() {
        String longName = "A".repeat(50);
        assertThat(OwaspReportParser.cleanCveId(longName, null))
                .hasSize(41) // 40 + "…"
                .endsWith("…");
    }

    @Test
    void cleanCveId_nullReferences_fallsBackToFirstLine() {
        assertThat(OwaspReportParser.cleanCveId("Plain name without CVE", null))
                .isEqualTo("Plain name without CVE");
    }

    // ── cleanCveDescription ─────────────────────────────────────────────────

    @Test
    void cleanCveDescription_nullInput_returnsEmpty() {
        assertThat(OwaspReportParser.cleanCveDescription(null)).isEmpty();
    }

    @Test
    void cleanCveDescription_blankInput_returnsEmpty() {
        assertThat(OwaspReportParser.cleanCveDescription("   ")).isEmpty();
    }

    @Test
    void cleanCveDescription_plainParagraph_returnedClean() {
        assertThat(OwaspReportParser.cleanCveDescription("A simple description."))
                .isEqualTo("A simple description.");
    }

    @Test
    void cleanCveDescription_markdownHeaders_skipped() {
        String raw = "# Title\nActual description here.";
        assertThat(OwaspReportParser.cleanCveDescription(raw))
                .isEqualTo("Actual description here.");
    }

    @Test
    void cleanCveDescription_boldAndItalicStripped() {
        String raw = "**Critical** vulnerability in *core* module.";
        assertThat(OwaspReportParser.cleanCveDescription(raw))
                .isEqualTo("Critical vulnerability in core module.");
    }

    @Test
    void cleanCveDescription_inlineCodeStripped() {
        String raw = "Use `sanitize()` before rendering.";
        assertThat(OwaspReportParser.cleanCveDescription(raw))
                .isEqualTo("Use sanitize() before rendering.");
    }

    @Test
    void cleanCveDescription_linkStripped_textKept() {
        String raw = "See [advisory](https://example.com/advisory) for details.";
        assertThat(OwaspReportParser.cleanCveDescription(raw))
                .isEqualTo("See advisory for details.");
    }

    @Test
    void cleanCveDescription_htmlTagsRemoved() {
        String raw = "Vuln in <code>foo()</code> method.";
        assertThat(OwaspReportParser.cleanCveDescription(raw))
                .isEqualTo("Vuln in foo() method.");
    }

    @Test
    void cleanCveDescription_codeBlocksSkipped_firstParagraphExtracted() {
        String raw = "Description paragraph.\n\n```bash\nnpm exploit\n```";
        assertThat(OwaspReportParser.cleanCveDescription(raw))
                .isEqualTo("Description paragraph.");
    }

    @Test
    void cleanCveDescription_longResult_truncatedAt200Chars() {
        String longDesc = "x".repeat(250);
        String result = OwaspReportParser.cleanCveDescription(longDesc);
        assertThat(result).hasSize(201).endsWith("…");
    }

    @Test
    void cleanCveDescription_multiLineParagraph_joinedWithSpace() {
        String raw = "First line.\nSecond line.\nThird line.";
        assertThat(OwaspReportParser.cleanCveDescription(raw))
                .isEqualTo("First line. Second line. Third line.");
    }
}
