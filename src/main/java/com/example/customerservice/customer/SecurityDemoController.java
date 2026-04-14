package com.example.customerservice.customer;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
@RestController
@RequestMapping("/demo/security")
public class SecurityDemoController {

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
    @GetMapping("/sqli-vulnerable")
    @SuppressWarnings("SqlInjection")
    public Map<String, Object> sqliVulnerable(@RequestParam String name) {
        String sql = "SELECT id, name, email FROM customer WHERE name = '" + name + "'";
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
        return Map.of(
                "query", sql,
                "vulnerability", "String concatenation — input is NOT sanitized",
                "results", results,
                "exploit", "Try: ?name=Alice' OR '1'='1"
        );
    }

    /**
     * SAFE — parameterized query prevents SQL injection.
     *
     * <p>The {@code ?} placeholder tells the JDBC driver to treat the value as data,
     * not as part of the SQL syntax. No escaping needed — the driver handles it.
     */
    @GetMapping("/sqli-safe")
    public Map<String, Object> sqliSafe(@RequestParam String name) {
        String sql = "SELECT id, name, email FROM customer WHERE name = ?";
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, name);
        return Map.of(
                "query", sql,
                "fix", "Parameterized query — input is treated as data, not SQL",
                "results", results
        );
    }

    // ─── XSS Demo ───────────────────────────────────────────────────────────

    /**
     * VULNERABLE — reflects user input as HTML without escaping.
     *
     * <p>Try: {@code ?name=<script>alert('XSS')</script>}
     * The browser will execute the script if the response is rendered as HTML.
     */
    @GetMapping(value = "/xss-vulnerable", produces = "text/html")
    public String xssVulnerable(@RequestParam String name) {
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
    @GetMapping(value = "/xss-safe", produces = "text/html")
    public String xssSafe(@RequestParam String name) {
        String escaped = name
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
}
