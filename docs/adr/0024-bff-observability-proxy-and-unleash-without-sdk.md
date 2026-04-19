# ADR-0024 — BFF pattern for observability + Unleash without the SDK

- **Status**: Accepted
- **Date**: 2026-04-18
- **Supersedes**: n/a
- **Related**: ADR-0015 (GitOps / Argo CD), ADR-0014 (single-replica demo policy)

## Context

The Angular UI currently makes direct browser calls to several operator
tools when running in cluster mode:

- **Loki** (`:3100`) for LogQL queries on the Observability page
- **Tempo** (via Grafana datasource proxy) for trace lookups
- **Chaos Mesh dashboard** (`:2333`) — only reachable via `kubectl
  port-forward` today
- **Prometheus / Mimir** (via `/actuator/prometheus`) — same-origin
- **Keycloak** for login

Each of these is its own host, its own auth scheme, its own CORS
configuration. Wiring them all to work from the browser requires one
Ingress per service, an oauth2-proxy (or equivalent) per Ingress, and
CORS headers maintained in multiple places. That is a lot of moving
parts for a portfolio demo, and the result is worse than the sum of its
parts: exposing Loki/Tempo/Chaos directly to the public internet is a
security smell even with auth in front.

Separately, we want UI feature flags so the same Angular build can render
either the customer view or the full operator dashboard. Unleash is the
obvious fit, but its Java SDK wants a background polling thread, its own
metrics, and a ~1 MB jar dependency — overkill for two flags read once
per UI render.

## Decision

**Two narrow decisions:**

1. **BFF proxy for observability.** A new
   `BffObservabilityController` in the Spring Boot backend exposes a
   small, read-only subset of Loki and Tempo endpoints under `/obs/**`.
   The UI calls the backend; the backend calls Loki/Tempo internally
   using the cluster DNS. No additional Ingress, no extra oauth2-proxy,
   no CORS beyond what the main UI already needs.

2. **Unleash via RestClient, not SDK.** A
   `FeatureFlagController` polls Unleash's client API
   (`/api/client/features`) on demand with a 30-second in-memory cache.
   No SDK dependency, no background threads, no metric feedback loop
   back to Unleash.
   **Note (superseded by ADR-0026)**: this whole `FeatureFlagController`
   was subsequently removed. The UI now calls `unleash-proxy` directly.
   The single remaining flag in use is `mirador.bio.enabled` (kill-switch
   for the LLM-backed /bio endpoint); the initially-planned
   `mirador.ui.ops-mode` was dropped — the project has a single user
   (developer), always operator, so a customer-persona gate had no
   real effect.

## Consequences

### Positive

- **One auth surface for the UI.** JWT to `/api/**` covers observability
  queries, feature flags, and customer data. No extra login flows.
- **No direct browser → Loki/Tempo exposure.** The cluster can keep
  observability services internal; only the backend reaches them.
- **Topology can change without touching the UI.** If Loki moves to
  Grafana Cloud, only the backend's `LOKI_URL` env var changes.
- **No Unleash SDK weight.** One RestClient call per 30 s, one class,
  ~90 lines.

### Negative

- **BFF scope creep risk.** Every time someone wants a new observability
  query in the UI, they'll ask for a new BFF endpoint. This is real
  ownership cost. Mitigation: document the proxy scope in the controller
  javadoc — "these six endpoints, no more."
- **No streaming.** Loki and Tempo support gRPC streaming for live
  tailing; the BFF does plain request/response. Acceptable for the demo,
  but a real-world deployment would add WebSocket or SSE passthrough.
- **Unleash flag evaluation is server-side only.** Per-user rollout
  strategies (`gradualRolloutUserId` etc.) don't work because the
  controller doesn't pass user context. If that's needed later, move to
  the real SDK.
- **Chaos Mesh dashboard stays port-forward-only.** It's not yet proxied
  through the BFF. For the demo that's fine; the Chaos page in the UI
  relies on the user running `bin/port-forward-chaos.sh` first.

## Alternatives considered

| Option                                                | Why rejected                                                           |
|-------------------------------------------------------|------------------------------------------------------------------------|
| One Ingress per tool + oauth2-proxy                   | 5× the Ingress/config surface; leaks topology to the browser.          |
| Service mesh (Istio) with per-service authN policies  | Single-node Autopilot demo; mesh overhead not justified for 10 pods.   |
| Grafana reverse-proxy (Grafana's `?orgId` API proxy)  | Requires UI to embed Grafana session cookies; gets weird with iframes. |
| Unleash Java SDK                                      | Background thread + metric feedback + 1 MB jar for 2 flags evaluated once per page render. |
| Unleash front-end proxy (`unleash-proxy`)             | Another pod to operate and secure; same auth problem as Loki.          |

## Implementation notes

- BFF endpoints live under `/obs/**` — already covered by
  `SecurityConfig` via `anyRequest().authenticated()`.
- URLs for Loki/Tempo/Unleash come from `app.observability.*` and
  `app.features.*` in `application.yml`, overridable via env vars for
  local docker-compose.
- Unleash fetch failures return the last-known-good cached flags — a
  30-s-stale flag is better than a crashed UI page.
<!-- Superseded by ADR-0026: the UI gates features via unleash-proxy
     (client-side), not a Spring Boot BFF. ops-mode gate was dropped;
     only mirador.bio.enabled kill-switch remains. -->

## Revisit this when

- The UI needs live log-tailing or streaming traces → add SSE passthrough
  or move that specific feature to a direct authed Loki Ingress.
- Unleash grows past 5 flags or we need per-user rollout → move to the
  SDK.
- A third deployment target (not GKE) exposes Loki/Tempo differently →
  the env-var indirection already handles this, but revisit
  `BffObservabilityController`'s hardcoded `http://` scheme.
