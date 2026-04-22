# ADR-0047 — Auth0 consent screen stays for social logins (Google OAuth2)

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Related: `docs/how-to/auth0-tenant-setup.md` (mirador-ui repo),
  ADR-0018 (JWT strategy), ADR-0044 (hexagonal-lite)

## Context

Our Auth0 tenant is configured to match Auth0's published conditions
for **skipping the "Authorize Mirador" consent screen**:

1. The Auth0 Application is **first-party** (default for apps created
   in our own tenant, and the toggle is grayed-out because changing
   ownership would break the connection). ✅
2. The Auth0 API `https://mirador-api` has **Access Settings → "Allow
   Skipping User Consent"** toggled ON. ✅
3. The `/authorize` request includes an `audience` parameter matching
   the API identifier. ✅
4. The flow uses PKCE with `response_type=code`. ✅

And yet: **every first login still shows the "Authorize Mirador" consent
screen**. Observed in this session for the user
`google-oauth2|102974457466870759534` (benoit.besson@gmail.com signed in
through the Google Social Connection).

### Why

Per [Auth0's official doc](https://auth0.com/docs/get-started/applications/confidential-and-public-applications/user-consent-and-third-party-applications),
consent is skipped **only when the user authenticates via a first-party
connection**. Auth0 classifies connections as first-party / third-party:

- **First-party**: Database (username+password stored in Auth0),
  Passwordless (email/SMS magic link), Enterprise (SAML / ADFS / AD /
  LDAP / etc.).
- **Third-party**: **Social connections** (Google, Facebook, GitHub,
  Microsoft, Apple, LinkedIn, …) by default.

A Social Connection can be upgraded to first-party via Auth0 Dashboard
→ Authentication → Social → [connection] → **Advanced** → trust level,
BUT this is restricted to Enterprise plans. On the free / Essentials /
Professional tiers (we are on the Free trial), social connections are
always third-party → consent always required.

So the consent screen is **not a bug** — it's Auth0 enforcing its
trust model for social identity providers.

## Decision

**Accept the consent screen for social logins. Do NOT invest in
workarounds.**

Rationale:

- The consent is shown **once per (user, application) pair**. Auth0
  remembers the click. Subsequent logins skip it.
- For a portfolio demo, a recruiter who signs up once accepts the
  consent, then navigates freely on every return visit.
- Upgrading to Enterprise purely to flip social connections to
  first-party is disproportionate (~€240/user/month).
- The consent screen itself is branded with the Auth0 Application
  name ("Mirador") and lists the requested scopes — it's a
  security-positive UX, not a bug.

### What stays wired

The three tenant-side toggles are configured correctly (and stay
configured) because they DO take effect for users authenticating via
a Database Connection. A test user created via Auth0 Dashboard →
User Management → Users → Create User + Database Connection
`Username-Password-Authentication` would skip consent as expected.

## Alternatives considered

### A) Force Database Connection, disable social

**Rejected.** Social login (Google, GitHub) is a demo feature — it
shows "this app supports OIDC federation". Removing it to save one
consent click per user trades a bigger feature for a smaller UX nit.

### B) Upgrade to Enterprise plan just for this

**Rejected.** €240/user/month is a disproportionate cost. Even at the
smallest tier (~€1.5k/year), it's 100× the value of the UX gain.

### C) Write a custom Action that auto-accepts consent

**Rejected (tried, doesn't exist).** Auth0 Actions run AFTER consent,
not before — there's no API to programmatically click Accept. The
Post-Login action can tweak the token but can't skip consent.

### D) Use `prompt=none` on silent re-auth

**Partially adopted** (default behaviour of `getAccessTokenSilently`).
Once the user has clicked Accept once, silent iframe re-auth never
shows consent again (the user has already consented, Auth0 caches
it). So the pain is strictly a first-login cost.

### E) Accept the consent screen and move on (this ADR)

Current state. Consent is a one-time UX artifact per user. Never
blocks, never confuses — the Auth0 page clearly says "Authorize
Mirador to access your dev-ksxj46zlkhk2gcvo account".

## Consequences

### Positive

- No code to write, no Auth0 plan to upgrade.
- Matches Auth0's documented security model — social IDPs are
  inherently third-party to the relying app.
- The consent screen doubles as a privacy-transparency page — the
  user sees exactly what access they're granting.

### Negative

- First-time portfolio recruiter sees a "Authorize Mirador to access
  your account" prompt that a naive user might bounce on. Partially
  mitigated by the existing README + demo script making it clear
  this is an Auth0 demo, not an attack.

### Neutral

- The three tenant toggles (Application first-party, API Allow
  Skipping Consent, Post-Login Action) stay configured. They DO skip
  consent for users who sign in via the Database Connection (even
  though no such users exist in the current tenant).

## Revisit criteria

- We upgrade to Auth0 Enterprise for any reason (Organizations, RBAC
  extended, etc.) → re-evaluate whether social connection trust-level
  flip becomes available + worth activating.
- Social identity providers start supporting
  [Rich Authorization Requests (RAR)](https://datatracker.ietf.org/doc/html/rfc9396)
  natively for first-party scopes → consent skip may become possible
  without Enterprise.
- We drop social logins entirely in favour of Database Connection
  only (demo pivot) → consent skip becomes automatic.

## References

- [Auth0 — User consent and third-party applications](https://auth0.com/docs/get-started/applications/confidential-and-public-applications/user-consent-and-third-party-applications)
- [Auth0 — Connection types](https://auth0.com/docs/authenticate/identity-providers/social-identity-providers)
- [`docs/api/auth0-current-tenant-state.md`](../api/auth0-current-tenant-state.md)
  — live snapshot of the Mirador dev tenant (domain, client ID,
  audience, roles, Post-Login Action wiring, disaster recovery).
- [`docs/api/auth0-action-roles.js`](../api/auth0-action-roles.js) —
  the Post-Login Action that injects `https://mirador-api/roles` into
  the access token (works regardless of consent flow).
- mirador-ui [`docs/how-to/auth0-tenant-setup.md`](https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/how-to/auth0-tenant-setup.md)
  — generic end-to-end setup guide for creating a fresh tenant.
