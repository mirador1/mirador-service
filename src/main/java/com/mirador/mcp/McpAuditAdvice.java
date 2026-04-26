package com.mirador.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mirador.observability.port.AuditEventPort;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aspect that records one audit event per MCP {@code @Tool} call AND emits
 * an INFO log line with MDC fields {@code tool_name} + {@code tool_args_hash}.
 *
 * <h3>Why an aspect, not in-line code in each service ?</h3>
 * <ul>
 *   <li>14 tools × 2 lines (audit + log) = 28 boilerplate lines that drift
 *       silently when a developer forgets one. The aspect runs once and
 *       covers every present and future {@code @Tool} method.</li>
 *   <li>The audit row is the regulatory contract — missing it on a write
 *       tool would be an actual incident. AOP gives a single point of
 *       enforcement.</li>
 * </ul>
 *
 * <h3>Why hash the args, not log them ?</h3>
 * <p>Tool arguments may carry PII (customer ID, email, …). The
 * {@code tool_args_hash} (8-char SHA-256 prefix) lets ops correlate
 * "the same call was issued twice in 30 s" without persisting the
 * payload itself. The audit row carries the JSON for compliance ; the
 * log line stays PII-safe.
 *
 * <h3>Failure containment</h3>
 * <p>An audit/log failure must NEVER block the tool call — the catch in
 * {@link #recordSafely} swallows + logs at WARN. A flaky audit DB cannot
 * take the MCP server down.
 */
@Aspect
@Component
public class McpAuditAdvice {

    private static final Logger log = LoggerFactory.getLogger(McpAuditAdvice.class);

    /** Prefix length on the SHA-256 of args JSON used as MDC value. */
    private static final int HASH_PREFIX_LEN = 8;

    /** Action label written to the {@code audit_event.action} column. */
    private static final String AUDIT_ACTION = "MCP_TOOL_CALL";

    /** MDC key carrying the matching tool name for log correlation. */
    private static final String MDC_TOOL_NAME = "tool_name";

    /** MDC key carrying the args-hash prefix for log correlation. */
    private static final String MDC_TOOL_ARGS_HASH = "tool_args_hash";

    private final AuditEventPort auditEventPort;
    private final ObjectMapper objectMapper;

    /**
     * @param auditEventPort the existing audit port (writes to {@code audit_event} table)
     */
    public McpAuditAdvice(AuditEventPort auditEventPort) {
        this.auditEventPort = auditEventPort;
        // Build a dedicated mapper so the aspect doesn't depend on the
        // Spring-wired ObjectMapper bean — Spring Boot 4's primary mapper
        // is the new tools.jackson type, NOT classic Jackson 2.x. Building
        // a small classic-Jackson mapper here keeps the audit row format
        // stable independent of how the rest of the app evolves its
        // serialisation stack. The mapper is configured to write Instant
        // / Duration / etc. as ISO-8601 strings (JSR-310 module + the
        // WRITE_DATES_AS_TIMESTAMPS=false pair) so audit rows are human-
        // readable.
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Around-advice on every method annotated {@link Tool}. Captures the
     * tool name, hashed args, and authenticated user ; lets the call
     * proceed (success or exception) ; records the outcome.
     */
    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundToolCall(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Tool tool = method.getAnnotation(Tool.class);
        String toolName = (tool != null && !tool.name().isBlank()) ? tool.name() : method.getName();
        Map<String, Object> args = collectArgs(method, pjp.getArgs());
        String argsJson = serialiseSafely(args);
        String argsHash = hashPrefix(argsJson);

        MDC.put(MDC_TOOL_NAME, toolName);
        MDC.put(MDC_TOOL_ARGS_HASH, argsHash);
        try {
            log.info("mcp_tool_call_started tool={} args_hash={}", toolName, argsHash);
            Object result = pjp.proceed();
            recordSafely(toolName, argsJson, "ok");
            return result;
        } catch (Throwable t) {
            // Audit even on failure — operators want to spot a tool that
            // returns errors consistently. Swallow nothing : re-throw so
            // the MCP framework returns the error to the client.
            recordSafely(toolName, argsJson, "error: " + t.getClass().getSimpleName());
            log.warn("mcp_tool_call_failed tool={} args_hash={} error={}",
                    toolName, argsHash, t.getMessage());
            throw t;
        } finally {
            MDC.remove(MDC_TOOL_NAME);
            MDC.remove(MDC_TOOL_ARGS_HASH);
        }
    }

    /**
     * Builds an ordered map of parameter name → arg value. Falls back to
     * positional names ({@code arg0, arg1, …}) when the bytecode does not
     * carry parameter names (compilation without {@code -parameters}).
     */
    private Map<String, Object> collectArgs(Method method, Object[] values) {
        Map<String, Object> map = new LinkedHashMap<>();
        var params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            String name = params[i].isNamePresent() ? params[i].getName() : ("arg" + i);
            Object value = values[i];
            map.put(name, value);
        }
        return map;
    }

    /**
     * Serialises args to JSON for the audit row. Falls back to a
     * stringified map when Jackson can't handle a value (e.g. a non-
     * serialisable user-supplied object) — defensive : never crash the
     * call because the audit serialiser failed.
     */
    private String serialiseSafely(Map<String, Object> args) {
        try {
            return objectMapper.writeValueAsString(args);
        } catch (JsonProcessingException ex) {
            log.warn("mcp_args_serialisation_failed error={}", ex.getMessage());
            return args.toString();
        }
    }

    /**
     * SHA-256 prefix of the args JSON — short enough for log lines, long
     * enough to deduplicate identical calls within a session. The full
     * hash isn't needed (this is a correlation key, not a cryptographic
     * commitment) so {@link #HASH_PREFIX_LEN} hex chars (32 bits) is
     * plenty.
     */
    private String hashPrefix(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return hex.length() > HASH_PREFIX_LEN ? hex.substring(0, HASH_PREFIX_LEN) : hex;
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is in every JVM ; this branch is unreachable but
            // we'd rather degrade to a constant than throw on the request
            // path.
            return "sha-na";
        }
    }

    /**
     * Persists the audit event without ever crashing the call. The audit
     * port's own implementation is async ({@code @Async}), so this method
     * just hands off the row.
     */
    private void recordSafely(String toolName, String argsJson, String outcome) {
        try {
            String detail = "tool=" + toolName + " outcome=" + outcome + " args=" + argsJson;
            auditEventPort.recordEvent(currentUser(), AUDIT_ACTION, detail, null);
        } catch (Exception ex) {
            // Catch is intentional : audit must never break the tool path.
            // Justified per CLAUDE.md "no silent catch" rule by the WARN
            // log line — the failure is observable, just not propagated.
            log.warn("mcp_audit_record_failed tool={} error={}", toolName, ex.getMessage());
        }
    }

    /**
     * Returns the authenticated user's name from the security context, or
     * {@code "anonymous"} when no authentication is established (e.g. in a
     * unit test that calls the service directly).
     */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }
}
