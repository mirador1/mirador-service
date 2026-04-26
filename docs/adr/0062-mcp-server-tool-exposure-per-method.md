# ADR-0062 — MCP server : `@Tool` per-method on the service layer

**Status** : Accepted
**Date** : 2026-04-26
**Sibling docs** :
- [shared ADR-0059 — Customer/Order/Product/OrderLine data model](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md)
- [common ADR-0001 — polyrepo via submodule](https://gitlab.com/mirador1/mirador-common/-/blob/main/docs/adr/0001-shared-repo-via-submodule.md)

## Context

We want to expose the Mirador domain (Order / Product / Customer / Chaos / SLO)
to LLM clients via the [Model Context Protocol](https://modelcontextprotocol.io/).
A `claude` CLI session pointed at our backend should be able to query, summarise,
and trigger demos in plain English.

### Why MCP over the alternative — Bash through CLI tools

The default Claude-Code workflow today is to spawn shell commands :
`glab mr list`, `kubectl get pods`, `docker exec postgres-demo psql -c "…"`,
`curl localhost:8080/actuator/health | jq`. This works but pays a steep tax
on every operation :

| Aspect | Shell-via-Bash | MCP server |
|---|---|---|
| Output | Raw text — LLM parses ad-hoc, hallucinates structure | Typed JSON, schema-driven |
| Errors | Exit code + stderr, conventions vary per tool | Structured `code` + `message` per the MCP spec |
| Injection | Shell escaping is fragile, risky | Params typed by signature, no eval |
| Auth | Inherits the shell user's full perms | Per-tool `@PreAuthorize`, role-scoped |
| Audit | None by default | One `audit_event` row per call (action + args + user) |
| Rate-limit | None | Inherits the existing Bucket4j filter |
| Streaming | Buffered stdout, no progress | MCP `progress notification` mid-call |
| Cross-platform | BSD vs GNU divergence (sed, date, …) | Same JSON contract everywhere |
| Composability | Pipes + intermediate parsing | Clean JSON chaining |
| Caching | Hand-rolled per call | Caffeine TTL declared on the tool |
| New-team-member ramp-up | Must learn N CLI syntaxes | Asks in English, MCP bridges |

The shell-only path can be made to work, but every operation pays the
tax. MCP is the "stop paying that tax" decision : the contract is typed,
auth is enforced once, audit is automatic, and the LLM picks the tool
from a structured catalogue instead of constructing brittle shell
incantations.

This is the **central motivation** for MCP, beyond the demo wow-factor :
it lifts shell-level operations into a typed, audited, secured API
without writing a new REST endpoint for every single tool.

[Spring AI 1.0.0+](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
ships an MCP server starter that auto-wires the HTTP/SSE endpoint and registers
methods marked with `@Tool` as MCP tools.

Three orthogonal design choices to make :

1. **Where to put `@Tool`** : per-method, per-class (auto-expose all methods),
   or globally on every Spring bean ?
2. **Which layer** : controllers, services, or repositories ?
3. **What to return** : entities or DTOs ?

This ADR addresses choice #1 specifically. Choices #2 and #3 are settled
quickly in §"Implications" below.

## Decision

**Annotate individual methods with `@Tool` on the service layer**, NOT
classes. Use `MethodToolCallbackProvider.builder().toolObjects(svc1, svc2, ...)`
with an **explicit allowlist** of beans, not `.beanFactory().all()`.

```java
@Service
public class OrderService {

    @Tool(description = "Lists recent orders, optionally filtered by status. "
                      + "Returns up to `limit` newest-first.")
    public List<OrderDto> listRecentOrders(
        @ToolParam(description = "Max results, 1..100") int limit,
        @ToolParam(description = "Status filter; one of PENDING, CONFIRMED, "
                              + "SHIPPED, CANCELLED. Omit to include all.",
                   required = false) OrderStatus status
    ) { … }

    // No @Tool — internal helper, not LLM-facing
    BigDecimal recomputeTotal(Order o) { … }
}

@Configuration
public class McpConfig {
    @Bean
    public ToolCallbackProvider mirador(
        OrderService orderSvc,
        ProductService productSvc,
        CustomerService customerSvc,
        ChaosService chaosSvc
    ) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(orderSvc, productSvc, customerSvc, chaosSvc)
            .build();
    }
}
```

## Why per-method, not per-class

### 1. LLM accuracy drops with auto-exposed surface

A service like `OrderService` has 8-15 public methods : the customer-facing
ones (`listRecentOrders`, `getOrder`, `cancelOrder`), helper-ish ones
(`computeTotal`, `validateStatusTransition`), and Spring-data passthroughs
(`existsById`). An LLM picking the right tool from 5 well-distinguished
ones is reliable ; from 15 — including 3 generic `findById` overloads — it
hesitates, mixes them, or asks the user a clarification it shouldn't have
to. Anthropic's tool-use guidance recommends keeping the active tool count
**under 20**, ideally **5-10**. Per-method opt-in keeps the surface tight.

### 2. Each tool needs its own description

A class-level "this service handles orders" is too vague for the model to
**choose between** the service's methods. The whole point of `@Tool(description=…)`
is to give the LLM enough specifity to disambiguate :

> ❌ Class-level : "This bean handles order management."
> ✅ Per-method  : "Lists orders for a single customer, newest-first, capped at 100. Use this when the user asks 'what did customer 42 buy?'."

The second sentence cuts the LLM's decision space in half.

### 3. Method names ≠ good tool names

Java convention is short verbs : `list()`, `get()`, `update()`. LLM tools
want long, descriptive identifiers : `list_recent_orders_for_customer`,
`find_low_stock_products`. `@Tool(name = …)` lets us decouple the wire-name
from the Java method name, which a class-level switch would not.

### 4. Filtering happens at the right layer

There are methods we **never** want exposed :

- Helpers like `OrderService.recomputeTotal(Order)` — internal invariant
  enforcement, not customer-facing.
- Spring-data passthroughs (`existsById`, `count`) — low LLM value, noise
  in the tool catalogue.
- Method overloads — Java allows `findByCustomer(Long)` + `findByCustomer(Long, Pageable)`,
  but the LLM tool registry needs unique names.

Per-method opt-in puts the filter exactly where the decision matters :
"is this useful to LLM users ?" — asked once, on the method, by the author.

### 5. Future-proof against refactors

When we add a new helper method to a service, it doesn't accidentally
become an LLM-facing tool. A class-level `@ExposeAllMethods` would silently
broaden the contract on every commit. `@Tool` per-method is the explicit
intent : adding it = decision to commit.

### 6. The "decision space cut in half" framing

A class-level "this service handles orders" leaves the LLM with N candidate
methods, no internal ordering. Per-method `@Tool(description=…)` carves the
input space :

> "Lists ORDERS FOR A CUSTOMER, newest-first, capped at 100. Use this when
> the user asks 'what did customer 42 buy?'."

That single sentence eliminates `getOrderById`, `cancelOrder`, `addLine`,
`getOrdersForToday` from consideration. The LLM picks faster, picks more
reliably, and stops asking for clarification it shouldn't have to ask.

### 7. Tool name decoupling

Java idioms favour terse verbs : `list()`, `get()`, `update()`. MCP tool
names benefit from longer snake_case identifiers : `list_recent_orders_for_customer`,
`find_low_stock_products`. `@Tool(name=…)` lets us decouple wire-name from
Java method name — a class-level switch wouldn't.

## Alternatives considered

| Option | Rejected because |
|---|---|
| `MethodToolCallbackProvider.builder().beanFactory(bf).all()` | Exposes EVERY `@Tool`-annotated method across all beans, including future ones added by transitive dependencies. Loses the deliberate-allowlist property. |
| Annotate controllers with `@Tool` | Controllers carry HTTP concerns (`HttpServletRequest`, `Authentication`, raw response codes). LLM tools should consume / produce plain DTO and stay HTTP-agnostic. Service layer is the right boundary. |
| Hand-rolled `Tool` wrapper classes (per tool, one class) | Originally proposed in design draft — rejected for boilerplate. Spring AI's annotation discovery already does the registration. |
| Class-level `@McpExposed` annotation that auto-includes all public methods | Implementing it ourselves would re-create the per-method `@Tool` design but with weaker controls (no per-method description, no opt-out). Spring AI explicitly opts for per-method. |

## Implications

### Layer choice : service, not controller, not repository

- **Controllers** are HTTP-coupled (request, auth context, headers). Bad fit
  for tool methods that take simple parameters and return DTOs.
- **Repositories** are too low-level (return entities with lazy proxies,
  no business logic). Bad fit for direct LLM exposure.
- **Services** sit at the domain boundary, take/return DTOs, are already
  transactional and validated. **Right fit**.

### Return type : DTO, never entity

JPA entities can carry lazy collections (`@OneToMany`) that explode into
hundreds of rows when serialised — the LLM would receive a 50 KB JSON for
"give me a customer". Always return a flat DTO with explicit fields. This
also means the `@Tool` return signature is part of the **public API
contract** — same discipline as REST DTOs.

### Auth : same `SecurityContext` as REST

The MCP endpoint (`POST /mcp/message`) goes through `JwtAuthenticationFilter`.
A `@Tool` method annotated `@PreAuthorize("hasRole('ADMIN')")` enforces the
role for LLM callers exactly as for HTTP callers. Tested in
`McpAuthIntegrationTest`.

### Idempotency on writes

Write tools (e.g. `createOrder`, `cancelOrder`) inherit the existing
`IdempotencyFilter` because they share the HTTP path. An LLM that retries
a failed call doesn't double-create. The idempotency key is generated
client-side (Claude session ID + tool call ID).

### Tool naming convention

```
list_recent_orders            — read, plural
get_order_by_id               — read, singular
get_customer_summary          — read, aggregate
find_low_stock_products       — read, predicate
create_order                  — write, single resource
cancel_order                  — write, state change
trigger_chaos_experiment      — side effect, named operation
```

snake_case, verb-first, present tense, no Java-style "Get" prefix.

### Documentation hygiene

Every `@Tool` description must answer : **what does this do, when should
the LLM pick it, what's distinctive vs siblings ?** No marketing fluff.
Test : if you can swap the description with the description of a different
tool and not notice, both are too generic.

## Consequences

**Positive** :
- Tight tool catalogue ⇒ better LLM accuracy
- Explicit opt-in ⇒ no accidental exposure
- Per-method descriptions ⇒ LLM disambiguation
- Service-layer ⇒ HTTP-agnostic, idiomatic Java

**Negative** :
- Two annotations to add when shipping a new tool (`@Tool` + `@ToolParam`s)
- Description fields drift if not reviewed (mitigation : `@Tool description="…"`
  is checked by `eslint`-equivalent at PR review time, not enforced)
- Each `@ToolParam(required = false)` Java parameter must be Optional or nullable

## Tool catalogue (initial scope)

The first MCP server release exposes 5 tool families. Each `@Tool` lives
in the corresponding service ; signatures are sketched here, full Javadoc
+ `@ToolParam` descriptions in the implementation.

### Domain tools (Order/Product/Customer/Chaos)

| Tool | Description (LLM-facing) |
|---|---|
| `list_recent_orders` | Newest-first list of orders, capped at 100. Optional `status` filter, optional `customerId` filter. |
| `get_order_by_id` | Full order header + lines for a single ID. Returns 404-style empty if absent. |
| `create_order` | Creates an empty order for a customer. Returns the new ID. Idempotency-Key reuse via the existing IdempotencyFilter. |
| `cancel_order` | Marks an order CANCELLED + cascades line removal. |
| `find_low_stock_products` | Products below a stock threshold ; default threshold 10. |
| `get_customer_360` | Customer + count of their orders + total revenue. Aggregate read. |
| `trigger_chaos_experiment` | Wraps `/chaos/{scenario}` — slow-query, db-failure, kafka-timeout, etc. |

### Observability tools — backend-LOCAL only (added 2026-04-26)

**Architectural constraint** : the Mirador backend MUST stay agnostic of
infrastructure tools (Mimir, Grafana, Loki, GitLab, GitHub, kubectl, …).
Coupling the domain backend to specific infra tools would :
- Couple deploys (Spring Boot can't ship without Loki reachable),
- Bloat the dependency tree (`grafana-rest-client`, etc.),
- Leak deployment-environment knowledge into the application,
- Force every consumer (kind, GKE, AWS, on-prem) to ship the same observability stack.

So the Mirador MCP server only exposes what the backend **already owns
in-process** :

| Tool | Description | Backing source (in-process only) |
|---|---|---|
| `tail_logs` | Last N log lines with level / MDC filter ; default N = 50, max 500. | Logback ring-buffer appender attached at startup. NO Loki call. |
| `get_metrics` | Returns specific Micrometer registry samples by name + tags filter. | `MeterRegistry` injected directly. NO Mimir call. |
| `get_health` | Composite + sub-indicator status. | Spring Boot Actuator's `HealthEndpoint` bean. |
| `get_health_detail` | Same, with details. Admin-gated. | Same. |
| `get_actuator_env(prefix)` | Spring environment props matching a prefix, redacting secrets. | Actuator `EnvironmentEndpoint`. |
| `get_actuator_info()` | Build / git / version info. | Actuator `InfoEndpoint`. |
| `get_openapi_spec(summary)` | Full OpenAPI 3.1 OR paths-only summary. | `springdoc-openapi` in-process. |

**External infra MCP servers** (Mimir, Grafana, Loki, GitLab, GitHub) live
**outside** the Mirador codebase :
- Use community / official MCP servers (Anthropic ships some,
  [github.com/modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers)).
- Each Claude Code user adds them independently via `claude mcp add`.
- The application's session ends up with N MCP servers ; Claude composes
  across them (e.g., `mirador.get_health` + `prometheus.query` in the
  same prompt) ; Mirador's backend never imports any infra-tool client.

This split keeps the deploy unit (Spring Boot jar) decoupled from the
deploy environment (which observability stack, which CI vendor).

### Decision rule : "produces vs accesses"

The single criterion for "in app MCP" vs "external infra MCP" :

> Does the application **produce** these data already, as part of its
> normal runtime ? **Yes** → expose via app MCP (free, no infra coupling).
> **No, the application merely *accesses* an external service** → keep
> outside, use a community / DIY external MCP server with its own scoped auth.

| Concern | Status | Why |
|---|---|---|
| Logback ring buffer | ✅ in app MCP | App produces its own log lines |
| Micrometer registry | ✅ in app MCP | App produces its own metrics |
| Actuator (health/info/env) | ✅ in app MCP | App self-introspection |
| OpenAPI spec | ✅ in app MCP | App describes its own surface |
| Domain (Order/Product/Customer/Chaos) | ✅ in app MCP | App owns the domain logic |
| **Raw SQL query** on the DB | ❌ excluded | App accesses the DB ; the DB is the owner. Raw SQL bypasses the app's `@PreAuthorize` checks, leaks the schema, exposes auth tables (`app_user`, `refresh_token`) and admin tables (`flyway_schema_history`, `shedlock`). Granularity wrong : DBA-level, not application-view. |
| Mimir / Prometheus query | ❌ excluded | App reports metrics TO Mimir but doesn't own it. External community MCP. |
| Grafana panel render | ❌ excluded | Grafana is a separate service Mirador happens to feed. External MCP. |
| Loki tail | ❌ excluded | App ships logs TO Loki ; Loki is the aggregator. External MCP. |
| K8s pod inspection | ❌ excluded | Platform concern, not application |
| Kafka topic listing | ❌ excluded | Messaging infra, cross-applicative |
| GitLab MR / GitHub PR | ❌ excluded | Project-management / VCS layer |

**Why the rule matters** : when an app MCP starts exposing things it
merely *accesses*, you :
1. Bloat the deploy unit with N infra clients.
2. Couple the deploy to the deploy environment (no Mimir reachable → app boots in a degraded mode that nobody documented).
3. Break auth scoping (one JWT can do app stuff AND DB queries — too much surface for one token).
4. Re-implement what community MCP servers already do well.

Stick to "produces" → small, focused, deploy-portable app MCP.

### Corollary : prefer community MCP servers over custom

Whenever a community MCP server already covers a concern (DB, Mimir,
Grafana, Loki, GitLab, GitHub, k8s, Slack, Filesystem, etc.), **use it**.
Do NOT re-build the same thing in the application MCP. Reasons :

- **Battle-tested** : maintained by people who know the wrapped tool deeply (Postgres MCP by people who know JDBC + the libpq edge cases ; Grafana MCP by people who know the dashboards API).
- **Stays current** : when the underlying tool ships v2, the MCP package updates ; we don't have to track upstream changes in our codebase.
- **Isolated dependency** : a versioned npm/pip/Docker package, not a transitive in our `pom.xml`. CVE in the GitLab MCP doesn't bleed into our build.
- **Scoped auth** : each MCP has its own credential (Postgres MCP gets a read-only DB user ; GitLab MCP gets a project-scoped PAT ; GitHub MCP gets an SSO-restricted token). Auth boundaries match the tool boundaries.
- **Less maintenance for us** : every line of code we don't write is a line we don't have to test, secure, or upgrade.

What stays custom in the app MCP :
- Things truly **specific to our domain** that no community MCP covers (Order, Product, Customer, Chaos — mirroring our business model).
- Things that need our **app's runtime context** (Logback ring buffer with our request-id MDC, Micrometer registry with our custom meters — only WE can serve these).
- Things that wrap our **internal endpoints** the community can't see (Actuator, our OpenAPI spec).

If a community MCP exists for a concern → use it.
If not, evaluate :
- Is it specific to our domain ? → custom in app MCP.
- Is it cross-cutting infra ? → write a STANDALONE MCP server (separate repo / npm package), don't bake into the backend jar.

These backend-local observability tools are gated by **role** (read-only
role can call all of them except `get_health_detail` and
`trigger_chaos_experiment`, which are admin-only). Role check uses
Spring Security's `@PreAuthorize` — `@Tool` does NOT bypass auth.

### Implications for the implementation

- **3 new service classes** : `LogsService`, `MetricsService`, `GrafanaService`,
  each with `@Tool`-annotated methods. Plus `OpenApiService` (very thin —
  delegate to springdoc).
- **Configuration** : `MIRADOR_GRAFANA_URL`, `MIRADOR_GRAFANA_TOKEN`,
  `MIRADOR_LOKI_URL`, `MIRADOR_MIMIR_URL` env vars (env-aware so kind/prod
  use the in-cluster service names, local uses the LGTM container at 3000).
- **Caching** : `query_metric` results cached 5s via Caffeine — LLM often
  asks same query twice in a row when reasoning, no point hitting Mimir
  twice.
- **Rate limiting** : MCP endpoint inherits `RateLimitingFilter` (existing
  Bucket4j 100 req/min per IP). LLM agents that go into a tool-call storm
  get 429-throttled, not crash the backend.
- **Audit** : every tool call writes a `audit_event` row (action =
  `MCP_TOOL_CALL`, detail = JSON of args, user_id from JWT). Same table
  as login audits — single trail.

## References

- [Anthropic — Tool use guidance](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)
- [Spring AI — MCP Server starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [MCP specification](https://modelcontextprotocol.io/specification)
- shared ADR-0059 (data model) — the entities the tools wrap
