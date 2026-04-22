package com.mirador.observability.quality.parsers;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ReportParsers} — the static helpers shared by all
 * 7 quality report parsers (Surefire / JaCoCo / SpotBugs / PMD /
 * Checkstyle / OWASP / Pitest). Bugs here cascade into every parser, so
 * coverage matters more than the per-parser tests.
 *
 * <p>Each test runs in microseconds. Focus areas:
 * <ul>
 *   <li>Null-safe parsing — the parsers tolerate malformed input;
 *       guarantees of "{@code null} for malformed" must hold.</li>
 *   <li>XML hardening — DOCTYPE / external entities MUST be rejected
 *       (XXE / SSRF / billion-laughs guard).</li>
 * </ul>
 */
class ReportParsersTest {

    // ── round1 ─────────────────────────────────────────────────────────────────

    @Test
    void round1_truncatesToOneDecimal() {
        assertThat(ReportParsers.round1(3.14159)).isEqualTo(3.1);
        assertThat(ReportParsers.round1(99.99)).isEqualTo(100.0);
        assertThat(ReportParsers.round1(0.0)).isZero();
        assertThat(ReportParsers.round1(-2.55)).isEqualTo(-2.5);
    }

    // ── parseIntOrNull ─────────────────────────────────────────────────────────

    @Test
    void parseIntOrNull_validNumber_returnsInt() {
        assertThat(ReportParsers.parseIntOrNull("42")).isEqualTo(42);
        assertThat(ReportParsers.parseIntOrNull("0")).isZero();
        assertThat(ReportParsers.parseIntOrNull("-100")).isEqualTo(-100);
    }

    @Test
    void parseIntOrNull_trimsWhitespace() {
        assertThat(ReportParsers.parseIntOrNull("  42  ")).isEqualTo(42);
    }

    @Test
    void parseIntOrNull_nullEmptyOrMalformed_returnsNull() {
        assertThat(ReportParsers.parseIntOrNull(null)).isNull();
        assertThat(ReportParsers.parseIntOrNull("")).isNull();
        assertThat(ReportParsers.parseIntOrNull("   ")).isNull();
        assertThat(ReportParsers.parseIntOrNull("not-a-number")).isNull();
        assertThat(ReportParsers.parseIntOrNull("1.5")).isNull(); // strict integer
    }

    // ── parseDoubleOrNull ──────────────────────────────────────────────────────

    @Test
    void parseDoubleOrNull_validNumber_returnsRoundedDouble() {
        assertThat(ReportParsers.parseDoubleOrNull("3.14159")).isEqualTo(3.1);
        assertThat(ReportParsers.parseDoubleOrNull("42")).isEqualTo(42.0);
    }

    @Test
    void parseDoubleOrNull_nullEmptyOrMalformed_returnsNull() {
        assertThat(ReportParsers.parseDoubleOrNull(null)).isNull();
        assertThat(ReportParsers.parseDoubleOrNull("")).isNull();
        assertThat(ReportParsers.parseDoubleOrNull("not-a-number")).isNull();
    }

    // ── intAttr ────────────────────────────────────────────────────────────────

    @Test
    void intAttr_validAttribute_returnsParsedInt() throws Exception {
        Element el = parseXmlRoot("<root tests=\"42\" failed=\"3\"/>");
        assertThat(ReportParsers.intAttr(el, "tests")).isEqualTo(42);
        assertThat(ReportParsers.intAttr(el, "failed")).isEqualTo(3);
    }

    @Test
    void intAttr_missingOrMalformed_returnsZero() throws Exception {
        // Default-to-zero is intentional — every consumer accumulates these
        // into a running total, so absent/malformed counters mean
        // "this suite contributed nothing", not "fail the parse".
        Element el = parseXmlRoot("<root tests=\"abc\"/>");
        assertThat(ReportParsers.intAttr(el, "missing")).isZero();
        assertThat(ReportParsers.intAttr(el, "tests")).isZero();
    }

    // ── doubleAttr ─────────────────────────────────────────────────────────────

    @Test
    void doubleAttr_validAttribute_returnsParsedDouble() throws Exception {
        Element el = parseXmlRoot("<suite time=\"3.14\"/>");
        assertThat(ReportParsers.doubleAttr(el, "time")).isEqualTo(3.14);
    }

    @Test
    void doubleAttr_missingOrMalformed_returnsZero() throws Exception {
        Element el = parseXmlRoot("<suite time=\"oops\"/>");
        assertThat(ReportParsers.doubleAttr(el, "time")).isZero();
        assertThat(ReportParsers.doubleAttr(el, "absent")).isZero();
    }

    // ── getTagText ─────────────────────────────────────────────────────────────

    @Test
    void getTagText_directChildPresent_returnsTrimmedTextContent() throws Exception {
        Element parent = parseXmlRoot("<parent><name>  Alice  </name></parent>");
        assertThat(ReportParsers.getTagText(parent, "name")).isEqualTo("Alice");
    }

    @Test
    void getTagText_missingChild_returnsEmptyString() throws Exception {
        Element parent = parseXmlRoot("<parent/>");
        assertThat(ReportParsers.getTagText(parent, "name")).isEmpty();
    }

    @Test
    void getTagText_nestedSameTag_onlyMatchesDirectChild() throws Exception {
        // Pinned behaviour: getTagText returns "" when the only `<inner>`
        // tag is a grand-child, not a direct child. Callers rely on this
        // to disambiguate Pitest's `<description>` (nested at multiple levels).
        Element parent = parseXmlRoot(
                "<parent><wrapper><inner>nested</inner></wrapper></parent>");
        assertThat(ReportParsers.getTagText(parent, "inner")).isEmpty();
    }

    // ── parseDurationSeconds ───────────────────────────────────────────────────

    @Test
    void parseDurationSeconds_validIso_returnsSeconds() {
        OptionalLong dur = ReportParsers.parseDurationSeconds(
                "2026-04-22T10:00:00Z", "2026-04-22T10:01:30Z");
        assertThat(dur).hasValue(90L);
    }

    @Test
    void parseDurationSeconds_malformedTimestamp_returnsEmpty() {
        // Either input malformed → empty (caller omits the field).
        assertThat(ReportParsers.parseDurationSeconds("nope", "2026-04-22T10:00:00Z"))
                .isEmpty();
        assertThat(ReportParsers.parseDurationSeconds("2026-04-22T10:00:00Z", "nope"))
                .isEmpty();
        assertThat(ReportParsers.parseDurationSeconds(null, null)).isEmpty();
    }

    // ── secureDocumentBuilder ──────────────────────────────────────────────────

    @Test
    void secureDocumentBuilder_rejectsExternalDoctype() throws Exception {
        // XXE guard — DOCTYPE must be disabled (blocks classic XXE,
        // billion-laughs, file://etc/passwd reads). Test pins the security
        // posture against accidental factory-config drift.
        DocumentBuilder builder = ReportParsers.secureDocumentBuilder();
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """;

        assertThatThrownBy(() -> builder.parse(toInputSource(xxe)))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void secureDocumentBuilder_parsesPlainXml() throws Exception {
        DocumentBuilder builder = ReportParsers.secureDocumentBuilder();

        Document doc = builder.parse(toInputSource("<root><child/></root>"));

        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("root");
    }

    @Test
    void secureNamespaceAwareDocumentBuilder_alsoRejectsDoctype() throws Exception {
        // The pom.xml-friendly variant must enforce the same security posture.
        DocumentBuilder builder = ReportParsers.secureNamespaceAwareDocumentBuilder();
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """;

        assertThatThrownBy(() -> builder.parse(toInputSource(xxe)))
                .isInstanceOf(SAXException.class);
    }

    // ── loadResource ───────────────────────────────────────────────────────────

    @Test
    void loadResource_classpathHit_returnsStream() throws Exception {
        // META-INF/MANIFEST.MF is on the test classpath of any Spring Boot
        // app — handy fixture for the classpath-first branch.
        try (InputStream stream = ReportParsers.loadResource("META-INF/MANIFEST.MF", "/no/such/path")) {
            assertThat(stream).isNotNull();
        }
    }

    @Test
    void loadResource_neitherPathExists_returnsNull() {
        InputStream stream = ReportParsers.loadResource(
                "absolutely/no/such/classpath/resource.txt",
                "/tmp/no-such-file-" + System.nanoTime());
        assertThat(stream).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Element parseXmlRoot(String xml) throws Exception {
        return ReportParsers.secureDocumentBuilder()
                .parse(toInputSource(xml))
                .getDocumentElement();
    }

    private static InputSource toInputSource(String xml) {
        return new InputSource(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
