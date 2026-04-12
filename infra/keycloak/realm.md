# Keycloak realm configuration

Two realm files ship with this project:

| File | Purpose |
|---|---|
| `realm-dev.json` | Imported by `docker compose` for local development |
| `realm-prod.json` | Production reference — review before deploying, replace placeholder secrets |

`realm-dev.json` is also mirrored at `src/test/resources/realm-dev.json` for `KeycloakAuthITest`.
If you modify `realm-dev.json`, update the test copy too.

---

## Architecture

```
                         ┌────────────────────────────────┐
  ┌──────────────────┐   │           Keycloak             │
  │   api-gateway    │   │  (identity provider)           │
  │   caller service │   │                                │
  └────────┬─────────┘   │  realm: customer-service       │
           │              │  clients:                     │
           │ 1. POST /token│    api-gateway  → ROLE_ADMIN │
           │    client_credentials           monitoring   │
           │──────────────►    service      → ROLE_USER   │
           │ 2. JWT (signed)│                             │
           │◄──────────────└──────────────────────────────┘
           │                              ▲
           │                              │ JWKS (startup only)
           │ 3. Authorization: Bearer JWT │ fetches public keys
           ▼                              │
  ┌─────────────────────────────────────────────────────────┐
  │                  customer-service                       │
  │            (OAuth2 resource server)                     │
  │                                                         │
  │  validates JWT locally — never calls Keycloak at        │
  │  request time, only fetches JWKS once on startup        │
  └──────────────────────┬──────────────────────────────────┘
                         │
          ┌──────────────┼──────────────────┐
          ▼              ▼                  ▼
     PostgreSQL        Kafka             Redis
```

**Key point:** `customer-service` is a pure resource server. It fetches Keycloak's JWKS public keys
once at startup and validates all subsequent JWTs locally with no round-trip to Keycloak per request.

---

## Realm settings

| Field | Dev | Prod | Why |
|---|---|---|---|
| `sslRequired` | `none` | `external` | TLS terminated at the reverse proxy in prod; not needed on loopback |
| `accessTokenLifespan` | 3600 s | 300 s | Short-lived tokens limit the blast radius of a leaked credential |
| `bruteForceProtected` | — | `true` | Rate-limits failed login attempts |

---

## Clients

Both environments define the same two clients. The only differences are the secret values and the
security settings above.

### `api-gateway`

Represents a caller with full write access. In a real system this would be the API gateway or
another backend service that aggregates requests.

| Field | Value | Why |
|---|---|---|
| `publicClient` | `false` | Confidential client — authenticates with a client secret |
| `serviceAccountsEnabled` | `true` | Enables the Client Credentials grant; creates a service account user |
| `directAccessGrantsEnabled` | `false` | ROPC disabled — deprecated in OAuth 2.1 |
| `standardFlowEnabled` | `false` | No browser redirect needed for M2M |
| `defaultClientScopes` | `openid`, `roles` | `roles` causes Keycloak to include `realm_access.roles` in the JWT |
| Service account roles | `ROLE_USER`, `ROLE_ADMIN` | Full access |

### `monitoring-service`

Represents a read-only caller (e.g. an observability pipeline or reporting tool).

| Field | Value | Why |
|---|---|---|
| Service account roles | `ROLE_USER` | Read-only access |
| Everything else | same as `api-gateway` | — |

**Get a token (dev only):**
```bash
# Full access
curl -s -X POST http://localhost:9090/realms/customer-service/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=api-gateway&client_secret=dev-secret" \
  | python3 -m json.tool

# Read-only
curl -s -X POST http://localhost:9090/realms/customer-service/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=monitoring-service&client_secret=dev-secret-readonly" \
  | python3 -m json.tool
```

---

## Roles

Roles are defined at the **realm level** (not client level) so they appear in the
`realm_access.roles` claim that `JwtAuthenticationFilter` reads.

| Role | Purpose |
|---|---|
| `ROLE_USER` | Read access — permits `GET /customers` |
| `ROLE_ADMIN` | Write access — permits `POST /customers`; also holds `ROLE_USER` (both assigned together) |

The `ROLE_` prefix is already present so `SimpleGrantedAuthority` works without a
`GrantedAuthorityDefaults` converter.

---

## Production secrets

`realm-prod.json` ships with `"REPLACE_WITH_VAULT_SECRET"` as the client secret placeholder.
**Never commit a real secret.** In production, rotate the secret after import via the Keycloak
admin API or inject it at deploy time through Vault / k8s secret:

```bash
# Example — update secret via Keycloak admin REST API
curl -s -X PUT http://keycloak:8080/admin/realms/customer-service/clients/<client-uuid>/client-secret \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"value":"<new-secret>"}'
```
