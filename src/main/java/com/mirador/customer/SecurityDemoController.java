package com.mirador.customer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Map;

/**
 * Intentionally vulnerable endpoints for security education and demo purposes.
 *
 * <p><b>WARNING:</b> These endpoints contain deliberate security flaws.
 * They exist solely to demonstrate common OWASP vulnerabilities and their fixes.
 * Never deploy vulnerable endpoints in production.
 *
 * <h3>Demonstrated vulnerabilities</h3>
 * <ul>
 *   <li><b>SQL Injection (OWASP A03)</b> — {@code /demo/security/sqli-vulnerable}
 *       concatenates user input directly into SQL. The fix at
 *       {@code /demo/security/sqli-safe} uses parameterized queries.</li>
 *   <li><b>XSS / Content-Type confusion (OWASP A07)</b> — {@code /demo/security/xss-vulnerable}
 *       reflects user input as HTML without escaping. The fix at
 *       {@code /demo/security/xss-safe} HTML-encodes the output.</li>
 *   <li><b>CORS misconfiguration</b> — {@code /demo/security/cors-info} explains
 *       why {@code allowedOrigins("*")} with {@code allowCredentials(true)} is dangerous.</li>
 * </ul>
 */
@Tag(name = "Security Demos (OWASP)", description = "⚠️ Intentionally vulnerable endpoints for educational purposes. "
        + "Demonstrates SQL Injection (A03), XSS (A07), CORS misconfiguration, IDOR (A01), and security headers.")
@SecurityRequirements   // all demo endpoints are permit-all
@RestController
@RequestMapping("/demo/security")
// NOTE FOR SONARQUBE: The SQL injection (sqli-vulnerable), XSS (xss-vulnerable) and IDOR
// (idor-vulnerable) endpoints are INTENTIONALLY VULNERABLE — they demonstrate OWASP A01/A03/A07.
// Mark those specific issues as "Won't Fix" or "Accepted" in SonarQube.
// All other code quality issues (duplicate literals, etc.) in this class ARE fixed.
public class SecurityDemoController {

    // Sonar java:S1192 — response map keys used in the security-headers demo.
    private static final String KEY_EXPECTED    = "expected";
    private static final String KEY_EXPLANATION = "explanation";
    private static final String KEY_RESULTS     = "results";

    private final JdbcTemplate jdbcTemplate;

    public SecurityDemoController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── SQL Injection Demo ─────────────────────────────────────────────────

    /**
     * VULNERABLE — SQL injection via string concatenation.
     *
     * <p>Try: {@code ?name=Alice' OR '1'='1} to dump all customers.
     * Try: {@code ?name='; DROP TABLE customer; --} (won't work because
     * {@code queryForList} doesn't execute DDL, but illustrates the risk).
     */
    @Operation(summary = "⚠️ SQL Injection — VULNERABLE",
            description = "Concatenates user input directly into SQL. Try `?name=Alice' OR '1'='1` to dump all customers. "
                    + "**OWASP A03:2021 — Injection**")
    // INTENTIONAL VULNERABILITY: SQL injection via string concatenation (OWASP A03).
    // This endpoint exists to demonstrate the attack. The suppression below is
    // deliberate — the companion /sqli-safe endpoint shows the correct
    // parameterized form, and the front-end "Security Demo" UI diffs the two.
    @SuppressWarnings({"javasecurity:S3649", "java:S2077"})
    @GetMapping("/sqli-vulnerable")
    public Map<String, Object> sqliVulnerable(
            @Parameter(description = "User input injected into SQL", example = "Alice' OR '1'='1")
            @RequestParam String name) {
        String sql = "SELECT id, name, email FROM customer WHERE name = '" + name + "'";
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
        return Map.of(
                "query", sql,
                "vulnerability", "String concatenation — input is NOT sanitized",
                KEY_RESULTS, results,
                "exploit", "Try: ?name=Alice' OR '1'='1"
        );
    }

    /**
     * SAFE — parameterized query prevents SQL injection.
     *
     * <p>The {@code ?} placeholder tells the JDBC driver to treat the value as data,
     * not as part of the SQL syntax. No escaping needed — the driver handles it.
     */
    @Operation(summary = "✅ SQL Injection — SAFE",
            description = "Uses a parameterized query (`?` placeholder). Same input as the vulnerable endpoint — the driver treats it as data, not SQL.")
    @GetMapping("/sqli-safe")
    public Map<String, Object> sqliSafe(
            @Parameter(description = "Same input, now safely parameterized", example = "Alice")
            @RequestParam String name) {
        String sql = "SELECT id, name, email FROM customer WHERE name = ?";
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, name);
        return Map.of(
                "query", sql,
                "fix", "Parameterized query — input is treated as data, not SQL",
                KEY_RESULTS, results
        );
    }

    // ─── XSS Demo ───────────────────────────────────────────────────────────

    /**
     * VULNERABLE — reflects user input as HTML without escaping.
     *
     * <p>Try: {@code ?name=<script>alert('XSS')</script>}
     * The browser will execute the script if the response is rendered as HTML.
     */
    // INTENTIONAL VULNERABILITY: reflected XSS (OWASP A07). The companion
    // /xss-safe endpoint shows the correct HtmlUtils.htmlEscape form.
    @SuppressWarnings("javasecurity:S5131")
    @Operation(summary = "⚠️ XSS — VULNERABLE (reflects raw HTML)",
            description = "Echoes `name` directly into an HTML page. Try `?name=<script>alert('XSS')</script>`. "
                    + "**OWASP A07:2021 — Cross-Site Scripting**")
    @GetMapping(value = "/xss-vulnerable", produces = "text/html")
    public String xssVulnerable(
            @Parameter(description = "Injected HTML/JavaScript", example = "<img src=x onerror=alert('XSS')>")
            @RequestParam String name) {
        return "<html><body><h1>Hello, " + name + "!</h1>"
                + "<p>This page is vulnerable to XSS because user input is reflected without escaping.</p>"
                + "<p>Try: <code>?name=&lt;script&gt;alert('XSS')&lt;/script&gt;</code></p>"
                + "</body></html>";
    }

    /**
     * SAFE — HTML-encodes user input before reflecting it.
     *
     * <p>The same input {@code <script>alert('XSS')</script>} is rendered as
     * harmless text instead of being executed as JavaScript.
     */
    @Operation(summary = "✅ XSS — SAFE (HTML-encoded output)",
            description = "HTML-encodes the input using `HtmlUtils.htmlEscape()` before reflecting it. `<script>` becomes `&lt;script&gt;` — displayed as text, not executed.")
    @GetMapping(value = "/xss-safe", produces = "text/html")
    public String xssSafe(
            @Parameter(description = "Input that will be safely HTML-encoded", example = "<b>Bold</b> & <i>italic</i>")
            @RequestParam String name) {
        // Spring's HtmlUtils handles the full OWASP escape set (&, <, >, ", ',
        // plus the HTML5 `` character); Sonar recognises it as a trusted sanitizer
        // so this variant is XSS-clean per javasecurity:S5131.
        String escaped = HtmlUtils.htmlEscape(name);
        return "<html><body><h1>Hello, " + escaped + "!</h1>"
                + "<p>This page is safe because user input is HTML-encoded before rendering.</p>"
                + "</body></html>";
    }

    // ─── CORS Misconfiguration Info ─────────────────────────────────────────

    /**
     * Explains CORS misconfiguration risks.
     *
     * <p>This endpoint doesn't demonstrate the vulnerability itself (that would require
     * a misconfigured CORS policy), but explains what goes wrong when
     * {@code allowedOrigins("*")} is combined with {@code allowCredentials(true)}.
     */
    @Operation(summary = "CORS configuration info",
            description = "Explains why `allowedOrigins('*') + allowCredentials(true)` is dangerous and how this API is correctly configured.")
    @GetMapping("/cors-info")
    public Map<String, Object> corsInfo(HttpServletRequest request) {
        return Map.of(
                "currentOriginPolicy", "http://localhost:4200 (restrictive — correct)",
                "dangerousConfig", "allowedOrigins('*') + allowCredentials(true)",
                "risk", "Any website can make authenticated requests to this API on behalf of the user's browser session",
                "attack", "evil.com includes <script>fetch('http://localhost:8080/customers', {credentials:'include'})</script>",
                "fix", "Always restrict allowedOrigins to known frontends. Never use '*' with credentials.",
                "yourOrigin", request.getHeader("Origin")
        );
    }

    // ─── IDOR — Broken Object Level Authorization (OWASP A01) ───────────────

    /**
     * VULNERABLE — returns any customer record by ID with no ownership check.
     *
     * <p>An attacker can enumerate IDs (1, 2, 3 …) to harvest all customer data.
     * This is OWASP API Security A01:2021 — Broken Object Level Authorization (BOLA/IDOR).
     */
    @Operation(summary = "⚠️ IDOR — VULNERABLE (no ownership check)",
            description = "Returns any customer record by ID with no access control. Enumerate IDs to harvest all data. "
                    + "**OWASP A01:2021 — Broken Object Level Authorization (BOLA/IDOR)**")
    @GetMapping("/idor-vulnerable")
    public Map<String, Object> idorVulnerable(
            @Parameter(description = "Any customer ID", example = "1")
            @RequestParam long id) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT id, name, email, created_at FROM customer WHERE id = ?", id);
        return Map.of(
                "requestedId", id,
                "vulnerability", "No ownership check — any caller can access any customer by guessing the ID",
                "owaspCategory", "A01:2021 — Broken Object Level Authorization (BOLA/IDOR)",
                "exploit", "Enumerate IDs: try id=1, id=2, id=3 … to harvest all customer records",
                KEY_RESULTS, results
        );
    }

    /**
     * SAFE — shows how an ownership check should be implemented.
     *
     * <p>In a real endpoint, the check would compare the authenticated user's identity
     * against the resource owner stored in the database. Here we simulate the pattern
     * and return only the query that would be used.
     */
    @Operation(summary = "✅ IDOR — SAFE (ownership check pattern)",
            description = "Shows the correct pattern: `WHERE id = ? AND created_by = :currentUser` + `@PreAuthorize` ownership check.")
    @GetMapping("/idor-safe")
    public Map<String, Object> idorSafe(
            @Parameter(description = "Customer ID to demonstrate the safe pattern", example = "1")
            @RequestParam long id) {
        return Map.of(
                "requestedId", id,
                "fix", "Verify the caller owns or has explicit permission to access this specific resource",
                "safeQuery", "SELECT * FROM customer WHERE id = ? AND created_by = :currentAuthenticatedUser",
                "springAnnotation", "@PreAuthorize(\"hasRole('ADMIN') or @customerService.isOwner(#id, authentication.name)\")",
                "pattern", "BOLA/IDOR prevention: every object-level read/write must include an ownership or permission check — not just 'is the user authenticated?'",
                KEY_RESULTS, List.of()
        );
    }

    // ─── Security Headers ────────────────────────────────────────────────────

    /**
     * Returns metadata about the OWASP-recommended security headers set by
     * {@link com.mirador.auth.SecurityHeadersFilter} on every response.
     *
     * <p>The frontend reads the <em>actual</em> response headers from this HTTP response
     * and compares them against the expected values returned in this body.
     */
    @Operation(summary = "Security headers metadata",
            description = "Returns expected values and explanations for OWASP-recommended security headers: "
                    + "`X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`, `CSP`, `Permissions-Policy`. "
                    + "The frontend compares these expected values against the actual response headers.")
    @GetMapping("/headers")
    public Map<String, Object> headersInfo() {
        return Map.of(
                "headers", List.of(
                        Map.of("name", "X-Content-Type-Options",
                               KEY_EXPECTED, "nosniff",
                               KEY_EXPLANATION, "Prevents MIME-type sniffing — browser won't reinterpret a CSV as HTML or JavaScript"),
                        Map.of("name", "X-Frame-Options",
                               KEY_EXPECTED, "DENY",
                               KEY_EXPLANATION, "Blocks clickjacking — prevents this page from being embedded in an <iframe>"),
                        Map.of("name", "X-XSS-Protection",
                               KEY_EXPECTED, "0",
                               KEY_EXPLANATION, "Disables the broken legacy XSS auditor. Modern protection uses CSP instead."),
                        Map.of("name", "Referrer-Policy",
                               KEY_EXPECTED, "strict-origin-when-cross-origin",
                               KEY_EXPLANATION, "Limits URL leakage in the Referer header for cross-origin requests"),
                        Map.of("name", "Content-Security-Policy",
                               KEY_EXPECTED, "default-src 'self'; frame-ancestors 'none'",
                               KEY_EXPLANATION, "Blocks inline scripts and external resource loading — prevents XSS escalation and clickjacking"),
                        Map.of("name", "Permissions-Policy",
                               KEY_EXPECTED, "camera=(), microphone=(), geolocation=()",
                               KEY_EXPLANATION, "Disables browser APIs (camera, mic, geolocation) that a REST API should never need"),
                        Map.of("name", "Strict-Transport-Security",
                               KEY_EXPECTED, "not set (HTTP dev environment)",
                               KEY_EXPLANATION, "Should be set in HTTPS production: max-age=31536000; includeSubDomains — forces HTTPS for 1 year and prevents SSL stripping")
                )
        );
    }
}
