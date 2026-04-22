package com.mirador.observability.port;

/**
 * Domain port — describes WHAT the other feature slices need to emit as a
 * security-audit event, without specifying HOW the event is stored.
 *
 * <p>The default implementation is
 * {@link com.mirador.observability.AuditService}, which writes to the
 * {@code audit_event} Postgres table via {@code JdbcTemplate} on the
 * Spring {@code @Async} executor. A test-only in-memory fake can implement
 * this interface without Spring, JDBC, or Testcontainers — that's the whole
 * point of extracting the port.
 *
 * <h2>Why a port (audit version of the ADR-0044 three-question filter)</h2>
 *
 * <ol>
 *   <li><b>Plausible second implementation?</b> Yes. Alternatives already
 *       under consideration: in-memory fake for tests, Kafka-backed audit
 *       trail (to ship off-box), OpenTelemetry log emission, structured
 *       JSON append to a dedicated audit index in Loki.</li>
 *   <li><b>Framework-free unit tests would add value?</b> Yes. Before the
 *       port, {@code CustomerService} / {@code AuthController} unit tests
 *       had to stub {@code AuditService} — a Spring-wired class with a
 *       {@code JdbcTemplate}. With the port, callers depend on a single
 *       4-method interface that takes domain primitives only.</li>
 *   <li><b>Impl leaks framework into callers?</b> Yes. {@code AuditService}
 *       forces {@code CustomerService} to transitively depend on JDBC.
 *       The port cuts that dependency.</li>
 * </ol>
 *
 * <h2>Deliberate design choices</h2>
 *
 * <ul>
 *   <li><b>Domain primitives only.</b> Four {@code String} parameters
 *       instead of an {@code AuditEvent} record. Avoids coupling callers
 *       to any specific DTO shape; the adapter side can evolve the DB
 *       schema without forcing a signature change on every call site.</li>
 *   <li><b>Fire-and-forget, void return.</b> Audit writes must never
 *       block the request path or propagate failures. The adapter's
 *       {@code @Async} + try/catch handles that; callers treat the event
 *       as emitted the moment the method returns.</li>
 *   <li><b>{@code recordEvent} not {@code log}.</b> {@code log} clashes
 *       with SLF4J's {@code Logger} naming in every file that already
 *       has {@code private static final Logger log}. {@code recordEvent}
 *       reads unambiguously at call sites.</li>
 *   <li><b>{@code ipAddress} may be {@code null}.</b> Background tasks
 *       and internal calls don't have a client IP. Callers pass
 *       {@code null} rather than a sentinel string — see the adapter's
 *       handling of the nullable case.</li>
 * </ul>
 *
 * <p>Extracted 2026-04-22 as proposal #3 from the Clean Code + Clean
 * Architecture audit. See
 * {@code docs/audit/clean-code-architecture-2026-04-22.md} and
 * {@code docs/adr/0044-hexagonal-considered-feature-slicing-retained.md}.
 */
public interface AuditEventPort {

    /**
     * Emits a security-audit event. Fire-and-forget — the call returns
     * immediately and exceptions inside the adapter do not propagate.
     *
     * @param userName  the user that triggered the event (never {@code null});
     *                  for failed-login events, the attempted username
     * @param action    action key, e.g. {@code LOGIN_SUCCESS},
     *                  {@code CUSTOMER_CREATED}, {@code TOKEN_REFRESH}
     *                  (never {@code null}; free-form string — an enum
     *                  upgrade is a Phase-4 follow-up)
     * @param detail    human-readable context (never {@code null}; may be
     *                  empty); for customer operations the convention is
     *                  {@code "id=<id> name=<name>"}
     * @param ipAddress client IP or {@code null} when the event originates
     *                  from a background task / internal method (no HTTP
     *                  request)
     */
    void recordEvent(String userName, String action, String detail, String ipAddress);
}
