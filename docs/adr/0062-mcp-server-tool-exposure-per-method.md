# ADR-0062 — MCP server : `@Tool` per-method on the service layer

**Status** : Proposed
**Date** : 2026-04-26
**Sibling docs** :
- [shared ADR-0059 — Customer/Order/Product/OrderLine data model](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md)
- [common ADR-0001 — polyrepo via submodule](https://gitlab.com/mirador1/mirador-common/-/blob/main/docs/adr/0001-shared-repo-via-submodule.md)

## Context

We want to expose the Mirador domain (Order / Product / Customer / Chaos / SLO)
to LLM clients via the [Model Context Protocol](https://modelcontextprotocol.io/).
A `claude` CLI session pointed at our backend should be able to query, summarise,
and trigger demos in plain English.

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

## References

- [Anthropic — Tool use guidance](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)
- [Spring AI — MCP Server starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [MCP specification](https://modelcontextprotocol.io/specification)
- shared ADR-0059 (data model) — the entities the tools wrap
