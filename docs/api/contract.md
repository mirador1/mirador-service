# API Contract — Audit Trail, Timeline, Security Demo, Scheduled Jobs

## 1. GET /audit

Returns paginated audit events from the `audit_event` table.

### Request
```
GET /audit?page=0&size=20&action=LOGIN_FAILED&user=alice&from=2024-01-01T00:00:00Z&to=2024-12-31T23:59:59Z
Authorization: Bearer <jwt>
```
All query params optional. Requires authentication (any role).

### Response 200
```json
{
  "content": [
    {
      "id": 42,
      "userName": "alice",
      "action": "LOGIN_FAILED",
      "detail": "Bad credentials",
      "ipAddress": "192.168.1.10",
      "createdAt": "2024-06-15T10:32:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 157,
  "totalPages": 8
}
```

### Action enum values (for filter dropdown)
`LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGIN_BLOCKED`, `CUSTOMER_CREATED`, `CUSTOMER_UPDATED`, `CUSTOMER_DELETED`, `TOKEN_REFRESH`, `API_KEY_AUTH`

---

## 2. GET /customers/stream

Server-Sent Events stream of newly created customers.

### Request
```
GET /customers/stream
Accept: text/event-stream
Authorization: Bearer <jwt>
```

### SSE events
```
event: customer
data: {"id":123,"name":"Alice","email":"alice@example.com","createdAt":"2024-06-15T10:32:00Z"}

event: ping
data: {}
```
Ping sent every 30s to keep connection alive.

---

## 3. GET /scheduled/jobs

Returns current ShedLock entries (last execution state per job).

### Request
```
GET /scheduled/jobs
Authorization: Bearer <jwt>
```

### Response 200
```json
[
  {
    "name": "customerStats",
    "lockUntil": "2024-06-15T10:32:25Z",
    "lockedAt": "2024-06-15T10:32:00Z",
    "lockedBy": "customer-service-1"
  }
]
```

---

## Security Demo Endpoints (already exist, no changes needed)

These are called directly by the frontend — no new backend work needed.

- `GET /demo/security/sqli-vulnerable?name=...`  
  Returns `{ query, vulnerability, results, exploit }`

- `GET /demo/security/sqli-safe?name=...`  
  Returns `{ query, fix, results }`

- `GET /demo/security/xss-vulnerable?name=...`  
  Returns `text/html` with unescaped input

- `GET /demo/security/xss-safe?name=...`  
  Returns `text/html` with HTML-encoded input

- `GET /demo/security/cors-info`  
  Returns `{ currentOriginPolicy, dangerousConfig, risk, attack, fix, yourOrigin }`

All endpoints are permit-all (no auth required).

---

## Notes for implementation

- `AuditService` already exists and writes to `audit_event` table — add read methods + new `AuditController`
- SSE: bridge the existing WebSocket `/topic/customers` broadcast to SSE, or publish directly from `CustomerService` to an `SseEmitter` registry
- ShedLock table is `shedlock(name, lock_until, locked_at, locked_by)` — query it via JDBC
