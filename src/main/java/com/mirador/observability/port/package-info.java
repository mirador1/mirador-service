/**
 * Domain ports for the observability feature slice.
 *
 * <p>Ports are interfaces the observability domain owns that describe WHAT
 * external capability is needed without specifying HOW. Implementations live
 * in adapter packages (today the impls happen to live alongside in
 * {@code com.mirador.observability.*}, because the default persistence
 * strategy — JDBC + Postgres — is an acceptable intra-feature default;
 * nothing stops a future Kafka or OpenTelemetry impl from living in its
 * own adapter package).
 *
 * <h2>Port-layer invariant</h2>
 *
 * Classes in this package MUST NOT depend on any framework type — no
 * Spring, no JPA, no Jackson, no JDBC. This mirrors the invariant
 * documented for {@code com.mirador.customer.port} and is enforced at
 * build time by {@code ArchitectureTest}.
 *
 * <h2>Scope (as of 2026-04-22)</h2>
 *
 * <ul>
 *   <li>{@link com.mirador.observability.port.AuditEventPort} — write-side
 *       port for security-audit events (LOGIN_*, CUSTOMER_*, TOKEN_*).
 *       Read-side (page/customer-id lookup) remains on
 *       {@link com.mirador.observability.AuditService} directly; a
 *       read-side port would be its own decision and is currently not
 *       justified (only one consumer in a sibling feature).</li>
 * </ul>
 *
 * <p>Second port under the hexagonal-lite pattern (after
 * {@code com.mirador.customer.port.CustomerEventPort}). Both passed the
 * three-question filter from ADR-0044 on: (1) plausible second impl,
 * (2) framework-free unit tests add value, (3) current impl leaks framework
 * into callers.
 */
package com.mirador.observability.port;
