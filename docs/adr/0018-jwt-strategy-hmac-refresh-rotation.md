# ADR-0018: JWT strategy — HMAC access tokens + single-use refresh rotation + Redis blacklist

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

The service has to authenticate API callers across three paths
(documented in `com.mirador.auth.package-info`): built-in JWT login,
Keycloak/Auth0 OIDC, and machine-to-machine API keys. The built-in
JWT path is the one the demo leans on most (Swagger UI flow, the
Angular SPA login, all integration tests).

Three independent decisions had to be made:

1. **Signing algorithm** for the access token (shared secret vs
   asymmetric key).
2. **Refresh token shape** (JWT vs opaque UUID with DB store).
3. **Revocation strategy** (natural expiry only vs explicit blacklist).

## Decision

**HMAC-SHA256 access tokens (15 min) + opaque UUID refresh tokens
(7 days) persisted in Postgres + Redis-backed blacklist on logout.**

### Why HMAC (HS256) for access tokens

- **Symmetric key** in `jwt.secret` (one env var, rotated via ESO —
  ADR-0016). No key pair to distribute.
- Token validation = single HMAC check, no network round-trip. The
  JWKS fetch that OIDC/Keycloak/Auth0 tokens require is avoided for
  the built-in path.
- Audience + issuer claims (`customer-service-api`, `customer-service`)
  are required on every token → the same secret can't be reused to
  mint tokens for other services on the same key.

The corresponding trade-off is that **any service that can verify
tokens can also mint them** (symmetric keys are symmetric). Accepted
because the built-in JWT path is single-tenant by design; external
issuers use RS256 via JWKS.

### Why opaque refresh tokens in Postgres

Alternatives considered:

- **Long-lived JWTs as refresh tokens**. Rejected: would need a
  separate per-token revocation list anyway (otherwise a leaked
  refresh JWT is valid for its full TTL). At that point, opaque +
  DB row is simpler.
- **Redis-only refresh tokens**. Rejected: Redis is our cache, not
  our source of truth. Losing Redis must not log everyone out.
- **Third-party IdP (Auth0 refresh tokens)**. Deferred: the built-in
  path keeps the demo runnable without Auth0. Auth0 is supported in
  parallel for the OIDC flow.

Rotation: every `/auth/refresh` call deletes the old row and inserts a
new one inside a single `@Transactional` method. This catches replay
attacks — if an attacker steals a refresh token and uses it once, the
legitimate user's next attempt to refresh gets a 401 (token not
found), which is the canonical "refresh token reuse detected" signal.

### Why a Redis blacklist for access tokens

An HMAC JWT is stateless — once issued, it's valid until its `exp`
claim. The only way to invalidate a non-expired access token is to
keep a revocation list and check every request against it.

On `/auth/logout`:
1. The access token is added to Redis with key `jwt:blacklist:<token>`
   and TTL = remaining seconds until `exp`.
2. Every authenticated request calls `isBlacklisted(token)` before
   populating the SecurityContext.

The Redis check adds ~1 ms per request. Acceptable.

When Redis is unreachable (`redisTemplate` is null — unit tests, or
Redis outage):
- `blacklistToken` logs a warning but doesn't fail — the user's logout
  succeeds locally, the token will simply expire naturally on its 15
  min clock.
- `isBlacklisted` returns `false` — which is "fail open". Accepted
  because the alternative (fail closed = Redis outage logs everyone
  out) is worse for a demo service. For a production deployment with
  stricter requirements, flip the return to `true` + alert.

## Consequences

Positive:
- Clear security posture: 15-min blast radius on a stolen access
  token, single-use refresh token, explicit logout revocation.
- No JWKS fetch latency on the built-in path.
- Refresh token theft is detectable (reuse → 401).
- `JwtTokenProvider` is unit-testable end-to-end (23 tests,
  ~100 % method coverage).

Negative:
- Redis is a hard dependency for "immediate revocation on logout".
  Mitigated by fail-open when Redis is unreachable; documented.
- The shared secret in `jwt.secret` must never leak. Handled by
  ESO + Google Secret Manager (ADR-0016).
- Refresh rotation means `DELETE` + `INSERT` on every refresh — two
  SQL calls. Acceptable at demo scale; at 10k refresh/s this would
  need a CAS-style update instead.

## References

- `com.mirador.auth.JwtTokenProvider` — all the logic in one class.
- `com.mirador.auth.JwtAuthenticationFilter` — request-side JWT
  validation + baggage propagation.
- `com.mirador.auth.LoginAttemptService` — complementary brute-force
  protection (5 failures → 15 min IP lockout), so the HMAC secret
  can't be trivially guessed online.
- ADR-0016 — External Secrets Operator for rotating `jwt.secret`
  without a redeploy.
