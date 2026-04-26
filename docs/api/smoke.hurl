# docs/api/smoke.hurl — Mirador API golden-path smoke test (Hurl).
#
# What this file is:
#   A plain-text HTTP script that exercises the main API flow end-to-end:
#   health → login → customer CRUD → logout. Each request asserts the status
#   code and (where useful) response body shape, so Hurl's `--test` mode
#   turns the whole file into a pass/fail smoke test.
#
# Why Hurl and not Postman/Bruno:
#   1. Plain text → git-friendly diffs, reviewers see intent at a glance.
#   2. Single binary, no GUI needed — copy-pasteable for a dev reviewer.
#   3. The same file runs interactively (`hurl smoke.hurl`), as a test suite
#      (`hurl --test smoke.hurl`), or as a post-deploy CI probe.
#
# Run it:
#   hurl --test \
#        --variable base=http://localhost:8080 \
#        --variable run_id=$(date +%s%N) \
#        docs/api/smoke.hurl
#
#   Or use the wrapper that fills run_id for you:
#   bin/dev/api-smoke.sh
#
# `run_id` gives each request a unique suffix for emails so re-runs don't
# collide with the UNIQUE constraint on customer.email (the service returns
# 409 Conflict on duplicate). Any monotonic unique string works; `date +%s%N`
# (nanoseconds) is plenty unique for a single-machine smoke test.
#
# Install (macOS): `brew install hurl`. Linux: https://hurl.dev/docs/installation.html
# Backend must be up: `docker compose up -d db keycloak` then `./mvnw spring-boot:run`.

# ───────────────────────────────────────────────────────────────────────
# 1. Infrastructure readiness — no auth needed.
# ───────────────────────────────────────────────────────────────────────
# The actuator health endpoint reports UP when DB + Kafka + Redis are wired.
# A 503 here means a dependency is down — no point running the rest.
GET {{base}}/actuator/health
HTTP 200
[Asserts]
jsonpath "$.status" == "UP"

# ───────────────────────────────────────────────────────────────────────
# 2. Acquire an admin JWT via the built-in login flow.
# ───────────────────────────────────────────────────────────────────────
# Demo credentials seeded by DataInitializer.java — admin/admin has
# ROLE_ADMIN (full CRUD + /chaos). See docs/api/api.md for the role matrix.
POST {{base}}/auth/login
Content-Type: application/json
{
  "username": "admin",
  "password": "admin"
}
HTTP 200
[Captures]
access_token: jsonpath "$.accessToken"
refresh_token: jsonpath "$.refreshToken"
[Asserts]
jsonpath "$.accessToken" exists
jsonpath "$.refreshToken" exists

# ───────────────────────────────────────────────────────────────────────
# 3. Create a customer (POST /customers) as the authenticated admin.
# ───────────────────────────────────────────────────────────────────────
# Captures the generated id — reused by the GET/PUT/DELETE steps below.
# The timestamp in the email keeps the email column unique across re-runs
# (unique constraint on app_user.email equivalent; CustomerService.create
# throws 409 on duplicate email).
POST {{base}}/customers
Authorization: Bearer {{access_token}}
Content-Type: application/json
{
  "name": "Hurl Smoke Test",
  "email": "hurl-smoke-{{run_id}}@example.com"
}
HTTP 200
[Captures]
customer_id: jsonpath "$.id"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.name" == "Hurl Smoke Test"

# ───────────────────────────────────────────────────────────────────────
# 4. Read the customer back by id.
# ───────────────────────────────────────────────────────────────────────
# GET /customers/{id} — served from the Caffeine L2 cache on hit. The
# first call after create misses; subsequent calls hit the cache.
GET {{base}}/customers/{{customer_id}}
Authorization: Bearer {{access_token}}
HTTP 200
[Asserts]
jsonpath "$.id" == {{customer_id}}
jsonpath "$.name" == "Hurl Smoke Test"

# ───────────────────────────────────────────────────────────────────────
# 5. Update the customer (PUT /customers/{id}) — full replacement.
# ───────────────────────────────────────────────────────────────────────
PUT {{base}}/customers/{{customer_id}}
Authorization: Bearer {{access_token}}
Content-Type: application/json
{
  "name": "Hurl Smoke Test (updated)",
  "email": "hurl-smoke-{{run_id}}@example.com"
}
HTTP 200
[Asserts]
jsonpath "$.name" == "Hurl Smoke Test (updated)"

# ───────────────────────────────────────────────────────────────────────
# 6. Versioned GET — demonstrates the Spring Boot 4 @RequestMapping
#    version feature. Same path, different shape based on X-API-Version.
# ───────────────────────────────────────────────────────────────────────
# v1 returns the flat DTO.
GET {{base}}/customers
Authorization: Bearer {{access_token}}
X-API-Version: 1.0
HTTP 200
[Asserts]
jsonpath "$.content" exists

# v2 returns the enriched DTO (adds `fullName`, `createdAtIso`). The
# `2.0+` matcher in CustomerController responds to any version ≥ 2.0.
GET {{base}}/customers
Authorization: Bearer {{access_token}}
X-API-Version: 2.0
HTTP 200
[Asserts]
jsonpath "$.content" exists

# ───────────────────────────────────────────────────────────────────────
# 7. Aggregation endpoint — fans out to two parallel sub-tasks on virtual
#    threads (see AggregationService + AggregationServicePropertyTest).
# ───────────────────────────────────────────────────────────────────────
GET {{base}}/customers/aggregate
Authorization: Bearer {{access_token}}
HTTP 200
[Asserts]
jsonpath "$.customerData" == "customer-data"
jsonpath "$.stats" == "stats"

# ───────────────────────────────────────────────────────────────────────
# 8. Role enforcement — viewer (ROLE_READER) must be denied POST/PUT/DELETE.
# ───────────────────────────────────────────────────────────────────────
# Short-lived viewer token; reused only for the next assertion.
POST {{base}}/auth/login
Content-Type: application/json
{
  "username": "viewer",
  "password": "viewer"
}
HTTP 200
[Captures]
viewer_token: jsonpath "$.accessToken"

# viewer can read…
GET {{base}}/customers/{{customer_id}}
Authorization: Bearer {{viewer_token}}
HTTP 200

# …but cannot create. 403 = role enforcement works.
POST {{base}}/customers
Authorization: Bearer {{viewer_token}}
Content-Type: application/json
{
  "name": "Viewer should not be able to create me",
  "email": "viewer-denied-{{run_id}}@example.com"
}
HTTP 403

# ───────────────────────────────────────────────────────────────────────
# 9. Refresh-token rotation — old refresh token is consumed single-use.
# ───────────────────────────────────────────────────────────────────────
POST {{base}}/auth/refresh
Content-Type: application/json
{
  "refreshToken": "{{refresh_token}}"
}
HTTP 200
[Captures]
rotated_access_token: jsonpath "$.accessToken"
rotated_refresh_token: jsonpath "$.refreshToken"
[Asserts]
jsonpath "$.accessToken" exists
# Rotation MUST produce a new refresh token, never echo the old one.
jsonpath "$.refreshToken" != "{{refresh_token}}"

# Using the OLD refresh token again must now fail — it was consumed.
POST {{base}}/auth/refresh
Content-Type: application/json
{
  "refreshToken": "{{refresh_token}}"
}
HTTP 401

# ───────────────────────────────────────────────────────────────────────
# 9bis. E-commerce surface (ADR-0059) — Product + Order + OrderLine.
# ───────────────────────────────────────────────────────────────────────
# Create a product, create an order, add 2 lines, verify total recomputed,
# delete a line, delete the order. Exercises the FK CASCADE / RESTRICT rules.

# Create Product P1 — Widget @ 10.00 € stock 100
POST {{base}}/products
Authorization: Bearer {{rotated_access_token}}
Content-Type: application/json
{
  "name": "smoke-widget-{{run_id}}",
  "description": "Smoke-test product",
  "unitPrice": 10.00,
  "stockQuantity": 100
}
HTTP 201
[Captures]
product_id: jsonpath "$.id"

# Create empty Order for the customer we already created
POST {{base}}/orders
Authorization: Bearer {{rotated_access_token}}
Content-Type: application/json
{ "customerId": {{customer_id}} }
HTTP 201
[Captures]
order_id: jsonpath "$.id"

# Add 2 lines of qty 2 → backend snapshots unitPrice = 10.00, total = 40.00
POST {{base}}/orders/{{order_id}}/lines
Authorization: Bearer {{rotated_access_token}}
Content-Type: application/json
{ "productId": {{product_id}}, "quantity": 2 }
HTTP 201
[Captures]
line1_id: jsonpath "$.id"

POST {{base}}/orders/{{order_id}}/lines
Authorization: Bearer {{rotated_access_token}}
Content-Type: application/json
{ "productId": {{product_id}}, "quantity": 2 }
HTTP 201

# Verify total = 40.00 (2 × 2 × 10.00)
GET {{base}}/orders/{{order_id}}
Authorization: Bearer {{rotated_access_token}}
HTTP 200
[Asserts]
jsonpath "$.totalAmount" == 40.00

# Cancel one line — total recomputes to 20.00
DELETE {{base}}/orders/{{order_id}}/lines/{{line1_id}}
Authorization: Bearer {{rotated_access_token}}
HTTP 204

GET {{base}}/orders/{{order_id}}
Authorization: Bearer {{rotated_access_token}}
HTTP 200
[Asserts]
jsonpath "$.totalAmount" == 20.00

# DELETE order cascades remaining line ; product NOT touched
DELETE {{base}}/orders/{{order_id}}
Authorization: Bearer {{rotated_access_token}}
HTTP 204

GET {{base}}/products/{{product_id}}
Authorization: Bearer {{rotated_access_token}}
HTTP 200

# Cleanup product
DELETE {{base}}/products/{{product_id}}
Authorization: Bearer {{rotated_access_token}}
HTTP 204

# ───────────────────────────────────────────────────────────────────────
# 9.bis MCP server (Model Context Protocol) — auth boundary check.
# ───────────────────────────────────────────────────────────────────────
# Per ADR-0062 the MCP HTTP/SSE endpoint at /sse + /mcp/message is gated
# by Spring Security exactly like every other protected path. The wire
# protocol itself (handshake, capabilities, tool calls) is the upstream
# Spring AI / MCP SDK's responsibility ; here we only assert the security
# boundary holds. Three checks :
#   1. Un-authenticated /sse → 401 (the security filter blocks).
#   2. Un-authenticated /mcp/message → 401.
#   3. Authenticated request gets through (status MAY vary based on the
#      MCP wire-protocol — anything that's NOT 401 means security passed).
# Note : the MCP backend stays infrastructure-agnostic — NO Loki / Mimir /
# Grafana / GitLab / GitHub clients in this jar. External infra MCP servers
# are SEPARATE community servers added per-developer via `claude mcp add`.

GET {{base}}/sse
HTTP 401

GET {{base}}/mcp/message
HTTP 401

# Authenticated GET /sse — security filter passes ; the MCP framework
# may return 4xx for a malformed handshake but the test only cares that
# the response is NOT 401.
GET {{base}}/sse
Authorization: Bearer {{rotated_access_token}}
[Asserts]
status != 401

# 10. Cleanup — delete the customer we created and logout.
# ───────────────────────────────────────────────────────────────────────
# Use the rotated access token to prove it works.
DELETE {{base}}/customers/{{customer_id}}
Authorization: Bearer {{rotated_access_token}}
HTTP 204

POST {{base}}/auth/logout
Authorization: Bearer {{rotated_access_token}}
HTTP 200

# …and the blacklisted access token must now be rejected.
# This proves logout actually invalidated the JWT (not just deleted the
# refresh token). Requires the Redis-backed blacklist to be wired up.
GET {{base}}/customers/aggregate
Authorization: Bearer {{rotated_access_token}}
HTTP 401
