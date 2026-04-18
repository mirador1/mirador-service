# ADR-0020: API versioning via `X-API-Version` header (Spring Framework 7)

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

The customer API needs to evolve without breaking existing clients —
the typical motivation is adding a field (`createdAt` in
`CustomerDtoV2`) without forcing every consumer to upgrade in
lockstep.

Three canonical versioning strategies exist:

| Strategy | Example | Pros | Cons |
|---|---|---|---|
| URI path | `/v1/customers`, `/v2/customers` | Trivial to reason about; great for CDN caching | Proliferates routes; each version has its own controller tree |
| Query param | `/customers?version=2` | Simple to try in a browser | Breaks URL idempotency; clashes with filter params |
| HTTP header | `X-API-Version: 2.0` | URL stays stable; content negotiation stays pure | Harder to try in a browser; needs tooling |

Spring Framework 7 added **native API versioning** support
(`@RequestMapping(version="2.0+")`) that does header-based matching
with "baseline semantics" (`"2.0+"` means "2.0 and any later version
until a higher handler exists"). This lines up with what the demo
wants to show: a first-class platform capability, not a hand-rolled
version resolver.

## Decision

**Mirador uses the `X-API-Version` request header + Spring Framework 7
native versioning**, configured in `application.yml`:

```yaml
spring:
  mvc:
    apiversion:
      use:
        header: X-API-Version
      default-version: "1.0"
```

Controller methods declare the versions they serve:

```java
@GetMapping(version = "1.0")            // default — matches 1.0 and no-header
public ResponseEntity<Page<CustomerDto>> getAll(...)

@GetMapping(version = "2.0+")           // matches 2.0 and any later version
public ResponseEntity<Page<CustomerDtoV2>> getAllV2(...)
```

The v2 response adds `createdAt`; v1 stays unchanged. A client upgrading
just sends `X-API-Version: 2.0`.

## Alternatives considered

- **URI path (`/v1/customers`)**: tried during a spike; rejected because
  it duplicated route declarations (every endpoint gets two copies of
  its annotations, @Operation summaries, PreAuthorize, etc.). The
  header approach keeps one method per logical operation.
- **Accept-header content negotiation** (`Accept: application/vnd.mirador.v2+json`):
  RESTful-er in theory. Rejected because it's harder to explain to
  demo viewers who aren't steeped in media-type versioning folklore,
  and Spring 7's native support uses a plain header.
- **GraphQL**: no versioning problem at all — clients pick their
  fields. Rejected: out of scope; REST is what we're demonstrating.

## Consequences

Positive:
- **Single controller class per feature** carries all versions —
  keeps security annotations, observability code, and tests
  co-located.
- **Baseline semantics (`2.0+`)**: any future `2.x` → this handler
  still serves it. We only need to ship a `3.0` handler when we
  break the 2.x contract. No dead handlers for minor versions.
- **Testable with plain MockMvc** —
  `get("/customers").header("X-API-Version", "2.0")`.
- **Swagger UI documents both versions** with their own `@Operation`
  summaries + parameter lists. The generated OpenAPI 3.1 spec emits
  two paths with the version as a discriminator.

Negative:
- **Client tooling must know about the header** — `curl` needs `-H
  "X-API-Version: 2.0"`. Browsers don't send it by default (a
  `fetch()` call in the SPA must add it).
- **CDN caching keyed on the URL is blind to the version** — need to
  `Vary: X-API-Version` on the response. The ingress-nginx config
  sets this.
- **Less discoverable than `/v2/customers`** — mitigated by OpenAPI
  docs showing the version dimension explicitly.

## References

- Spring Framework 7 API versioning:
  <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html#mvc-ann-requestmapping-version>
- `com.mirador.customer.CustomerController` — v1/v2 handlers.
- `src/test/java/com/mirador/customer/CustomerRestClientITest.java` —
  integration tests exercising both versions via `RestTestClient.header(...)`.
- ADR-0017 — the Spring Framework 7 decision that made this pattern
  available.
