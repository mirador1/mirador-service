# ADR-0019: Resilience4J (CB + Retry) + Bucket4J rate-limit + idempotency filter

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

Three independent resilience concerns sit in
`com.mirador.resilience`:

1. **Outbound call protection** — Ollama LLM inference + JSONPlaceholder
   HTTP API. Both are slow/unreliable in the demo.
2. **Inbound rate limiting** — prevent one client from starving the
   service on shared infrastructure (public demo at
   `mirador1.duckdns.org`).
3. **Idempotency** — a common real-world POST/PUT problem (client
   retries, double-submit). The service should compute the effect
   once.

Each concern has an off-the-shelf Java library with a clearly
different philosophy. Choosing three libraries that work together
well was a real decision — the naive path (e.g. "let Resilience4J do
all three") produces a brittle setup.

## Decision

**Resilience4J for Circuit Breaker + Retry, Bucket4J for per-IP rate
limiting, a handmade `IdempotencyFilter` keyed on
`Idempotency-Key` + Redis for idempotency.**

### Why Resilience4J for CB + Retry

- Annotation-based: `@CircuitBreaker(name="ollama", fallbackMethod=…)`
  lives next to the method it protects, so the degraded-mode
  behaviour is discoverable at the call site.
- Per-instance named configuration in `application.yml` —
  `resilience4j.circuitbreaker.instances.ollama.failureRateThreshold: 50`
  etc. No code change to tune.
- Micrometer-friendly: the `resilience4j-micrometer` module exports
  state + transition counters out of the box, wired into the Grafana
  "Golden Signals" dashboard.

### Why Bucket4J for rate limiting

- **Distributed by default**: Bucket4J has a Redis-backed distributed
  bucket primitive, so multi-replica rate limits just work without
  sticky sessions.
- Token-bucket semantics match the "burst-friendly but bounded"
  policy we want (100 req/min per IP, 429 + `Retry-After` header on
  exhaustion).
- Pure Java (no native deps), ~60 KB jar.

### Why a custom IdempotencyFilter

Idempotency support is rarely provided by generic resilience
libraries — the RFC draft
(<https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/>)
is very recent. The implementation is ~150 lines and tested with 8
unit tests:

- Only activates on `POST /customers` (the only currently
  non-idempotent endpoint; `shouldNotFilter` otherwise).
- Key: `Idempotency-Key` header (client-supplied UUID).
- Store: Redis with 24 h TTL.
- Hit path: replays the cached response body + status.
- Miss path: runs the controller, caches the response, continues.

Chose "build it" over:
- **Spring Cloud Feign idempotency** — only applies to outbound calls.
- **Reverse-proxy idempotency (Envoy/nginx)** — shifts responsibility
  to the platform layer; demo wants to show application-layer
  handling.

## Alternatives considered

- **Resilience4J for rate limit too**: has a `RateLimiter` primitive,
  but it's process-local — multi-replica deploys lose the global
  bound. Rejected.
- **Netflix Hystrix**: in maintenance mode. Explicitly the reason
  Resilience4J exists. Rejected.
- **Spring Retry**: simpler API but no circuit breaker. We already
  pull in Resilience4J for CB, so adding Spring Retry would double up.
- **Spring Cloud Gateway rate-limit**: service-to-service pattern;
  Mirador isn't behind an explicit gateway in the demo. Rejected for
  now, documented as a future option if an ingress gateway lands.

## Consequences

Positive:
- **Separation of concerns**: each library does one thing, and the
  three-way composition is explicit (three separate filters / aspects
  in the chain).
- **Failure modes are inspectable**: `/actuator/health` exposes
  circuit-breaker state; `/actuator/metrics` carries the rate-limit
  counters; structured logs include `idempotency_hit` / `_store`.
- **Three libraries on the dependency list, each with excellent docs
  + tests** — not a bespoke mess.

Negative:
- Three libraries × three upgrade paths × three mental models. The
  glossary + this ADR are the tax the project pays to keep them
  legible.
- `IdempotencyFilter` is in-house code that the team must own
  (compared to a vendored library). ~8 unit tests offset this.
- Rate-limit + idempotency both depend on Redis. Redis outage
  degrades behaviour (rate limit falls open, idempotency replay
  falls open). Documented in `package-info.java`.

## References

- `com.mirador.resilience.package-info` — high-level overview.
- `com.mirador.resilience.IdempotencyFilter` — the custom code.
- `application.yml` → `resilience4j.*`, `app.rate-limit.*`
- ADR-0018 — JWT strategy (LoginAttemptService is a fourth
  resilience pattern: brute-force protection, close cousin of rate
  limiting).
