# Auth0 tenant — current live state (2026-04-22)

**What this doc is**: a snapshot of the actual dev Auth0 tenant
configuration used by Mirador, as it was left after the 2026-04-21
end-to-end debug session. Complements the generic
[`auth0-tenant-setup.md`](https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/how-to/auth0-tenant-setup.md)
in the UI repo (which explains HOW to create a fresh tenant; this
doc records WHAT exists on the current one).

Useful for: re-debugging login without re-reading the full setup
guide, onboarding a reviewer who needs to see the tenant state,
avoiding "wait, what's the audience again?" lookups.

## Tenant identity

| Key | Value | Used by |
|---|---|---|
| Domain | `dev-ksxj46zlkhk2gcvo.us.auth0.com` | `AUTH0_DOMAIN` (UI constant + backend env) |
| Region | US | inherent to the domain suffix |
| Environment | Development | Auth0 free tier (up to 7 500 MAU — monthly active users) |

## Application (SPA)

One SPA registered — the Mirador Angular UI.

| Key | Value | Set in |
|---|---|---|
| Name | `Mirador UI` | Auth0 dashboard |
| Client ID | `DZKCwZ9dqAk3dOtVdDfc2rLJOenxidX6` | `AUTH0_CLIENT_ID` UI constant |
| Client Secret | *(SPA — no secret, PKCE flow)* | N/A |
| Type | Single Page Application | mandatory for the Angular SDK |
| Allowed Callback URLs | `http://localhost:4200` | dev only — prod host added when GKE ingress ships |
| Allowed Logout URLs | `http://localhost:4200` | idem |
| Allowed Web Origins | `http://localhost:4200` | CORS allowlist for silent token renewal |
| Allowed Origins (CORS) | `http://localhost:4200` | same value, different field (Auth0 quirk — both must be set) |
| JWT signature alg | RS256 | public-key (backend fetches from JWKS — JSON Web Key Set — auto-pulled at startup) |
| Refresh token rotation | ON | tokens rotate on every use, one-shot replay protection |
| Refresh token expiration | Absolute 30 days / Inactivity 14 days | aligned with Spring Security default session |

### Client ID visibility

SPA Client IDs are **public by design** — they ship in every browser
bundle via PKCE (Proof Key for Code Exchange). The `.gitleaks.toml`
allowlist was updated (commit `dac848b`) to whitelist all three
`AUTH0_DOMAIN`/`AUTH0_CLIENT_ID`/`AUTH0_AUDIENCE` constants so
gitleaks doesn't false-flag them as generic API keys.

## API (Resource Server)

| Key | Value |
|---|---|
| Name | `Mirador API` |
| Identifier (audience) | `https://mirador-api` |
| JWT profile | Auth0 |
| JWT signing alg | RS256 |
| Token expiration | 86400 seconds (24 h — default) |
| Allow offline access | ON (enables refresh tokens) |
| Skip consent for first-party | ON (Mirador UI is first-party — no consent screen when using email/password) |

## Connections enabled

- **Username-Password-Authentication** (default Auth0 database) — used
  by test signups during dev.
- **Google OAuth2** — social login. Consent screen cannot be suppressed
  for third-party social providers (documented in
  [ADR-0047](../adr/0047-auth0-consent-for-social-logins.md) — we
  accept the consent as a UX cost).

Other social providers (GitHub, Apple, Microsoft) are OFF. The
underlying Auth0 settings permit enabling them later with one toggle
each.

## Roles (User Management → Roles)

3 roles created on the tenant:

| Role | Purpose | Mirador authority |
|---|---|---|
| `ROLE_ADMIN` | full access (CRUD + chaos + admin endpoints) | `ROLE_ADMIN` |
| `ROLE_USER` | read + write (no delete) | `ROLE_USER` |
| `ROLE_READER` | read-only (GET endpoints) | `ROLE_READER` |

Assign via Auth0 dashboard → User Management → Users → `<user>` → Roles.

## Post-Login Action (Actions → Flows → Login)

Exactly one custom Action wired to the Login flow: `mirador-inject-roles`.
Source lives at [`docs/api/auth0-action-roles.js`](auth0-action-roles.js)
in this repo — committed so the Auth0 dashboard isn't the sole source
of truth.

Effect: every access token issued by the tenant includes a
`https://mirador-api/roles` claim holding the list of Auth0 roles the
user was assigned. The Mirador backend
(`JwtAuthenticationFilter.authenticateKeycloak`) reads this claim and
grants the matching Spring Security authorities.

Example access token payload (decoded):

```json
{
  "iss": "https://dev-ksxj46zlkhk2gcvo.us.auth0.com/",
  "sub": "auth0|abc123",
  "aud": ["https://mirador-api", "https://dev-ksxj46zlkhk2gcvo.us.auth0.com/userinfo"],
  "exp": 1745000000,
  "scope": "openid profile email",
  "https://mirador-api/roles": ["ROLE_ADMIN"]
}
```

## Backend wiring (mirador-service)

Three env vars in `docker-compose.yml` + `application.yml`:

```yaml
AUTH0_DOMAIN: dev-ksxj46zlkhk2gcvo.us.auth0.com
AUTH0_ISSUER_URI: https://dev-ksxj46zlkhk2gcvo.us.auth0.com/   # TRAILING SLASH MANDATORY
AUTH0_AUDIENCE: https://mirador-api
```

The trailing slash on `AUTH0_ISSUER_URI` matters — Auth0 puts it in the
JWT `iss` claim and Spring's `NimbusJwtDecoder` does string-compare.
One-off typo = every token rejected with "Invalid issuer".

Backend code paths:

- `src/main/java/com/mirador/auth/Auth0Config.java` — bean wiring
- `src/main/java/com/mirador/auth/JwtAuthenticationFilter.java` —
  `authenticateAuth0()` reads the `https://mirador-api/roles` claim
- `src/main/resources/application.yml` — `spring.security.oauth2.resourceserver.jwt.issuer-uri`

## UI wiring (mirador-ui)

Three constants at the top of `src/app/app.config.ts`:

```typescript
const AUTH0_DOMAIN = 'dev-ksxj46zlkhk2gcvo.us.auth0.com';
const AUTH0_CLIENT_ID = 'DZKCwZ9dqAk3dOtVdDfc2rLJOenxidX6';
const AUTH0_AUDIENCE = 'https://mirador-api';
```

Key UI files:

- `src/app/app.config.ts` — AuthModule.forRoot() configuration
- `src/app/core/auth/auth0-bridge.service.ts` — bridges Auth0 ID
  token into Mirador's signal-based `AuthService`
- `src/app/core/auth/auth.interceptor.ts` — Auth0-aware 401 handler
  with silent token refresh + `X-Auth0-Retry` guard against infinite
  loops
- `src/app/core/auth/auth.service.ts` — `isAdmin` computed reads THREE
  claim shapes: Mirador-builtin `role`, Keycloak `realm_access.roles`,
  Auth0 `https://mirador-api/roles`

## End-to-end login round-trip (sanity check)

1. Open `http://localhost:4200/login`
2. Click **Continue with Auth0** button
3. Auth0 universal login appears (either email/password form, or Google
   social → consent screen → back)
4. On success, Auth0 redirects to `http://localhost:4200?code=...&state=...`
5. `@auth0/auth0-angular` SDK exchanges the code for an access token
   + ID token + refresh token (PKCE flow)
6. `Auth0BridgeService` extracts the access token, sets it in
   `AuthService.token()` signal
7. Dashboard loads, `/actuator/health` + `/customers/aggregate` + any
   other protected call passes with `Authorization: Bearer <token>`
8. Backend's `JwtAuthenticationFilter.authenticateAuth0()`:
   - Validates signature via JWKS (public key auto-pulled)
   - Validates `iss` matches `AUTH0_ISSUER_URI`
   - Validates `aud` contains `AUTH0_AUDIENCE`
   - Reads `https://mirador-api/roles` claim → grants authorities
   - Request proceeds with the right role

## Troubleshooting

See the generic setup guide's Troubleshooting section
([UI repo](https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/how-to/auth0-tenant-setup.md#troubleshooting)):
"Oops! something went wrong" vs 401 after sign-in vs 401 from
protected endpoints.

Session-specific gotchas learned 2026-04-21:

1. **Google consent screen always appears** — ADR-0047 explains why
   Auth0 can't suppress consent for third-party social providers.
   We accept it. Not a bug.
2. **Backend race condition at first dashboard load** — the UI used
   to fire API calls BEFORE `Auth0BridgeService` set the token.
   Fixed in commits 79530e7 / 7a77eef (interceptor uses
   `getAccessTokenSilently()` + `X-Auth0-Retry` header guard against
   infinite loops).
3. **isAdmin missed Auth0 claims** — the UI's `isAdmin` signal only
   read `payload.role` (Mirador-builtin). Fixed in commit 3d28e11 to
   also read `realm_access.roles` (Keycloak) + `https://mirador-api/roles`
   (Auth0) — multi-format detection.

## Rotation / disaster recovery

If the tenant is lost (deleted, unrecoverable dashboard error, etc.):

1. Follow [`auth0-tenant-setup.md`](https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/how-to/auth0-tenant-setup.md) to create a fresh tenant.
2. Re-create the 3 roles (`ROLE_ADMIN`, `ROLE_USER`, `ROLE_READER`)
   on the new tenant.
3. Re-deploy the Post-Login Action by pasting
   [`auth0-action-roles.js`](auth0-action-roles.js).
4. Update the 3 UI constants in `src/app/app.config.ts` to the new
   tenant domain + client id.
5. Update the backend env vars (`AUTH0_DOMAIN`, `AUTH0_ISSUER_URI`,
   `AUTH0_AUDIENCE`) in `docker-compose.yml` + deployment manifests.
6. Re-authorize the SPA on the API (Settings → Machine-to-Machine
   Applications → toggle Authorized).
7. Re-test the round-trip from the section above.

The audience string (`https://mirador-api`) stays unchanged across
tenants by design — it's the API identifier, not a URL. Keeping it
identical avoids a code change in the backend's `spring.security.oauth2
.resourceserver.jwt.audiences` config.

## References

- [`docs/api/auth0-action-roles.js`](auth0-action-roles.js) — the
  Post-Login Action source
- [ADR-0047](../adr/0047-auth0-consent-for-social-logins.md) — why
  Google login keeps showing consent
- [UI — auth0-tenant-setup.md](https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/how-to/auth0-tenant-setup.md) — generic setup guide
- [ADR-0018 — JWT strategy](../adr/0018-jwt-strategy-hmac-refresh-rotation.md) — Mirador's broader JWT design
