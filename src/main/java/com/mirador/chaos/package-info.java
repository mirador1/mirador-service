/**
 * Chaos engineering feature slice — triggers Chaos Mesh experiments on demand.
 *
 * <p>The package exposes {@code POST /chaos/{experiment}} which creates a
 * fresh Chaos Mesh custom resource (PodChaos, NetworkChaos, or StressChaos)
 * in the {@code app} namespace via the Fabric8 Kubernetes client. The
 * experiment runs for its declared duration and Chaos Mesh deletes the CR
 * automatically — so the UI button is effectively a one-shot "fire and
 * observe" gesture, not a scheduled recurring experiment.
 *
 * <h2>Why a dedicated feature slice (not `observability` or `resilience`)?</h2>
 *
 * Per ADR-0044, feature-slicing is the default package layout. Chaos
 * engineering is its own concern: it triggers failures (writes to the
 * cluster API), which is different from {@code observability/} (reads
 * telemetry) and {@code resilience/} (handles failures — rate limit,
 * idempotency, circuit breaker). A separate slice makes it obvious where
 * to add new experiments and makes the ADR-0044 port-style pattern easy to
 * apply later (extract {@code ChaosPort} → swap Fabric8 for a mock in
 * tests).
 *
 * <h2>Off-cluster behaviour</h2>
 *
 * The Fabric8 {@code KubernetesClient} bean builds successfully even
 * without a cluster (lazy connection). If the application runs on a
 * laptop without a kubeconfig, the first {@code POST /chaos/...} fails
 * with {@code 503 Service Unavailable} and a message pointing at
 * {@code bin/cluster/demo/up.sh}. The bean never throws at startup.
 *
 * <h2>Security</h2>
 *
 * All endpoints are {@code ROLE_ADMIN}-only via {@link
 * com.mirador.auth.SecurityConfig}. Chaos endpoints are destructive by
 * design — no read-only role can trigger them.
 *
 * <h2>Related</h2>
 * <ul>
 *   <li>{@code deploy/kubernetes/base/chaos/experiments.yaml} — the
 *       declarative version of the same CRs (useful for
 *       {@code kubectl apply -k} without the UI).</li>
 *   <li>{@code deploy/kubernetes/base/backend/rbac.yaml} — RBAC that
 *       grants the backend ServiceAccount {@code create/delete} on
 *       {@code chaos-mesh.org/*}.</li>
 *   <li>ADR-0044 — port-style decoupling this feature anticipates.</li>
 *   <li>README.md "Compromises" section — honesty note on
 *       interactive-only chaos runs (no scheduled SLO gates yet).</li>
 * </ul>
 */
package com.mirador.chaos;
