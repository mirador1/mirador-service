# realm-demo.json — Keycloak realm configuration

This file is imported automatically by Keycloak on first startup via the `--import-realm` flag
(configured in `docker-compose.yml`). It is **idempotent**: re-importing has no effect if the
realm already exists.

> **Duplicate notice** — an identical copy lives at `src/test/resources/realm-demo.json`.
> That copy is loaded from the classpath by `KeycloakAuthITest` via `.withRealmImportFile("realm-demo.json")`.
> If you modify this file, update the other copy too.

---

## Realm

| Field | Value | Why |
|-------|-------|-----|
| `realm` | `spring-api-demo` | Referenced by `application.yml` → `keycloak.issuer-uri` and by the Spring Security resource server JWT validator |
| `sslRequired` | `none` | Disables HTTPS enforcement — acceptable for local development; set to `external` or `all` in production |
| `displayName` | `Spring API Demo` | Label shown in the Keycloak admin console at http://localhost:9090 |

---

## Client: `spring-api`

The application acts as an **OAuth2 resource server** (it validates tokens but never redirects users
to a login page). The client is therefore configured for the **Resource Owner Password Credentials**
(ROPC) grant — suitable for machine-to-machine or curl-based testing, not recommended for browser flows.

| Field | Value | Why |
|-------|-------|-----|
| `publicClient` | `true` | No client secret needed — tokens are obtained directly with username/password |
| `directAccessGrantsEnabled` | `true` | Enables the ROPC grant: `POST /realms/spring-api-demo/protocol/openid-connect/token` with `grant_type=password` |
| `standardFlowEnabled` | `false` | Disables the Authorization Code flow (no browser redirect needed for this demo) |
| `defaultClientScopes` | `openid`, `profile`, `email`, `roles` | `roles` scope causes Keycloak to include `realm_access.roles` in the JWT — required by `JwtAuthenticationFilter` |

**Get a token for testing:**
```bash
curl -s -X POST http://localhost:9090/realms/spring-api-demo/protocol/openid-connect/token \
  -d "grant_type=password&client_id=spring-api&username=admin&password=admin-password" \
  | python3 -m json.tool
```

---

## Roles

Roles are defined at the **realm level** (not client level) so they appear in the
`realm_access.roles` claim that `JwtAuthenticationFilter` reads.

| Role | Purpose |
|------|---------|
| `ROLE_USER` | Read access — permits `GET /customers` |
| `ROLE_ADMIN` | Write access — permits `POST /customers`; also holds `ROLE_USER` (assigned together in the `admin` user) |

The `ROLE_` prefix is already present in the role names so `SimpleGrantedAuthority` works
directly without a `GrantedAuthorityDefaults` converter.

---

## Users (demo only — never use in production)

| Username | Password | Roles |
|----------|----------|-------|
| `user` | `user-password` | `ROLE_USER` |
| `admin` | `admin-password` | `ROLE_USER`, `ROLE_ADMIN` |

`credentials.temporary: false` means Keycloak does not force a password change on first login.
`emailVerified: true` suppresses the email verification required action.

---

## Required Actions

Standard Keycloak actions (`CONFIGURE_TOTP`, `UPDATE_PASSWORD`, etc.) are listed with
`defaultAction: false` so users are not prompted to complete them on login. They are kept in the
export so the realm matches the default Keycloak schema and can be re-imported cleanly.

`delete_account` and `TERMS_AND_CONDITIONS` are `enabled: false` — not needed for this demo.
