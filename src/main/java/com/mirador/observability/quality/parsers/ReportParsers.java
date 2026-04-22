package com.mirador.observability.quality.parsers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Shared helpers for {@code com.mirador.observability.QualityReportEndpoint}
 * section parsers.
 *
 * <p>Extracted 2026-04-22 under Phase B-1 of the
 * {@code docs/audit/clean-code-architecture-2026-04-22.md} split plan.
 * Host file was 1934 LOC before the split; these helpers add up to ~180
 * LOC that would otherwise stay inline in the endpoint.
 *
 * <h3>What lives here</h3>
 * <ul>
 *   <li>Null-safe numeric parsers ({@link #parseIntOrNull}, {@link #parseDoubleOrNull}).</li>
 *   <li>XML attribute accessors that default to {@code 0} on parse failure
 *       ({@link #intAttr}, {@link #doubleAttr}) — the parsers tolerate
 *       malformed nodes and emit a best-effort report rather than crash
 *       the whole endpoint.</li>
 *   <li>Factories for a <b>hardened</b> {@link DocumentBuilder} that
 *       disables DOCTYPE, external entities, schema resolution, and
 *       reference expansion — blocks XXE / SSRF / billion-laughs attacks
 *       on every parser here by construction. Two variants (namespace-on
 *       vs -off) because {@code pom.xml} parsing needs namespace-aware.</li>
 *   <li>Classpath-first {@link #loadResource(String, String) resource loader}
 *       — every parser looks in {@code META-INF/build-reports/} first
 *       (the packaged JAR case) then falls back to {@code target/} (the
 *       unpackaged {@code mvn verify} dev case).</li>
 *   <li>{@link #round1} + {@link #parseDurationSeconds} — small enough
 *       to be pure lambdas but repeated enough to justify a shared home.</li>
 * </ul>
 *
 * <h3>Why static, not a Spring bean</h3>
 * Every method is referentially transparent (no state, no IO beyond what
 * the caller passes in). Making this a {@code @Component} would buy
 * nothing and cost a Spring context in unit tests.
 */
public final class ReportParsers {

    private ReportParsers() { /* static-only helper */ }

    /** Round a double to 1 decimal, idiomatic for display-side percentages. */
    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** {@code null} for empty/blank/malformed input — keeps JSON shape "missing = null". */
    public static Integer parseIntOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException _) { return null; }
    }

    /** {@code null} for empty/blank/malformed input; result is {@link #round1}-trimmed. */
    public static Double parseDoubleOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return round1(Double.parseDouble(v.trim())); } catch (NumberFormatException _) { return null; }
    }

    /**
     * Returns {@code el.getAttribute(attr)} parsed as {@code int}, or {@code 0}
     * when the attribute is missing or malformed. The default is safe because
     * every consumer feeds the value into a running total — absent counters
     * as zero means "this suite contributed nothing".
     */
    public static int intAttr(Element el, String attr) {
        try {
            return Integer.parseInt(el.getAttribute(attr));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /**
     * Returns the text content of the FIRST DIRECT child element named
     * {@code tag} under {@code parent}, or empty when no such child exists.
     *
     * <p>"Direct child" matters for nested XML where the same tag name
     * exists at multiple levels (e.g. Pitest mutation XML has {@code description}
     * at both the mutation and the reason levels). The unqualified
     * {@code getElementsByTagName} would match descendants too.
     */
    public static String getTagText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() > 0 && nl.item(0).getParentNode() == parent) {
            return nl.item(0).getTextContent().trim();
        }
        return "";
    }

    /** Same contract as {@link #intAttr}, for {@code double} attributes. */
    public static double doubleAttr(Element el, String attr) {
        try {
            return Double.parseDouble(el.getAttribute(attr));
        } catch (NumberFormatException _) {
            return 0.0;
        }
    }

    /**
     * Parses an ISO-8601 start/finish pair and returns the number of whole
     * seconds between them. A malformed timestamp collapses to
     * {@link OptionalLong#empty()} so callers just omit the field.
     */
    public static OptionalLong parseDurationSeconds(String startIso, String finishIso) {
        try {
            Instant start  = Instant.parse(startIso);
            Instant finish = Instant.parse(finishIso);
            return OptionalLong.of(Duration.between(start, finish).getSeconds());
        } catch (Exception _) {
            return OptionalLong.empty();
        }
    }

    /**
     * Hardened {@link DocumentBuilder} for untrusted XML.
     *
     * <p>Disables DOCTYPE (blocks XXE + billion-laughs), external entities
     * (blocks SSRF), external DTD/schema resolution, and reference
     * expansion. Use this for every parser that reads XML from build
     * output — surefire, PMD, Checkstyle, SpotBugs, PIT.
     */
    public static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disallow DOCTYPE — prevents all XXE, SSRF and billion-laughs variants.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /**
     * Variant of {@link #secureDocumentBuilder} that leaves namespace-awareness
     * OFF — {@code pom.xml} XML needs this so XPath queries work without
     * prefix gymnastics.
     */
    public static DocumentBuilder secureNamespaceAwareDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /**
     * Loads a resource from classpath first, falling back to a local file
     * path. Returns {@code null} if neither exists or both failed to open.
     *
     * <p>Every parser uses the same lookup: packaged JAR ships reports in
     * {@code META-INF/build-reports/}, local {@code mvn verify} leaves
     * them in {@code target/}. Failures at either step fall through
     * silently — the section returns {@code {available: false}} upstream
     * rather than bubble an IO exception into the endpoint.
     */
    public static InputStream loadResource(String classpathPath, String devFallback) {
        ClassPathResource res = new ClassPathResource(classpathPath);
        if (res.exists()) {
            try {
                return res.getInputStream();
            } catch (IOException _) {
                // fall through
            }
        }
        File devFile = new File(devFallback);
        if (devFile.exists()) {
            try {
                return new java.io.FileInputStream(devFile);
            } catch (IOException _) {
                // fall through
            }
        }
        return null;
    }
}
