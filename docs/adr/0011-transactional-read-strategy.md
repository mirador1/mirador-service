# ADR-0011: Minimal `@Transactional` surface, no `@Transactional(readOnly = true)`

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

A common Spring idiom is to annotate service classes with
`@Transactional(readOnly = true)` at the class level and override with
`@Transactional` on write methods. The claim is:

- Marks the transaction read-only so Hibernate skips dirty checking at
  flush time.
- Documents which operations are read-only at the call site.
- Lets Hibernate route reads to a replica when configured.

For our service the tradeoffs are different:

- **Most of our reads are Spring Data repository methods**
  (`repository.findById`, `repository.findByX`). Each repository method
  runs in its own Spring-auto-configured transaction already — the
  readOnly flag is applied transparently without an annotation on our
  code.
- **We do not run a Postgres read replica.** Cloud SQL HA has a single
  writer primary; readOnly routing would have nowhere to go.
- **Hibernate's dirty-check savings are negligible** at our read volume
  (hundreds of req/s, not tens of thousands). The wall-clock cost of
  annotating every read service with `@Transactional(readOnly = true)`
  would show up in `perf top` as noise, not signal.

## Decision

We use **the smallest possible `@Transactional` surface**:

1. **No class-level `@Transactional(readOnly = true)`** anywhere.
2. **No method-level `@Transactional(readOnly = true)`** anywhere.
3. **Method-level `@Transactional`** (read/write) only on the handful of
   operations that span **multiple** repository calls and need
   atomicity — currently the refresh-token CRUD in
   `src/main/java/com/mirador/auth/JwtTokenProvider.java`.
4. Simple read paths rely on the default per-repository-method
   transaction Spring Data JPA provides out of the box.

If a new write path lands, author it with `@Transactional` only if it
touches more than one repository call or needs rollback semantics. Do
not preemptively sprinkle `@Transactional` on single-repo-call methods
— Spring Data already handles them.

## Consequences

### Positive

- **Less noise in service code.** Reviewers see `@Transactional` and
  know the method has multi-call atomicity semantics, not that the
  author followed a template.
- **One pattern to onboard on.** We avoid the "why is this method
  read-only but that one is not?" question during code review.
- **Transaction boundaries match business operations.** A boundary
  starts where the business operation starts, not on every service
  method.

### Negative

- **Hibernate runs dirty checks on read paths by default.** At our
  throughput, immeasurable. If we ever land on an N+1 query on a large
  result set, we reconsider *just for that path*.
- **Harder to cut over to a read replica** if we ever add one. We would
  need either class-level readOnly on the relevant services or routing
  at a different layer (e.g. per-repository `@DataSource` selection).
  Accepted — we prefer to pay this cost once if/when it becomes
  relevant instead of annotating thousands of methods preemptively.

### Neutral

- Spring Data JPA repositories still auto-configure their own
  transactions. We are not turning transactions off — we are avoiding
  redundant annotations on top of them.
- Filter-driven cross-cutting behaviour (rate limiting, idempotency,
  JWT validation) does not need `@Transactional` — filters run before
  the service layer and read from Redis, not the DB.

## Alternatives considered

### Alternative A — Class-level `@Transactional(readOnly = true)` on every `@Service`

Rejected. Adds a line to every service for a benefit we do not measure
(no read replica, no measured dirty-check overhead). Encourages cargo-
culting on every new service.

### Alternative B — Per-method `@Transactional(readOnly = true)` on all read methods

Rejected for the same reason, plus amplifies the "which annotation
matches this method?" cognitive tax on every reader.

### Alternative C — `spring.jpa.open-in-view = true` and no manual transactions at all

Rejected. `open-in-view = false` in `application.yml` makes session
boundaries explicit; re-enabling OIV would mask lazy-loading exceptions
until the response is serialized, which is exactly the kind of bug
this codebase is allergic to.

## References

- `src/main/java/com/mirador/auth/JwtTokenProvider.java` — the only
  class today that uses `@Transactional`, on four write methods.
- `src/main/resources/application.yml` — `spring.jpa.open-in-view: false`
  (the inverse of this ADR's "but not OIV" reminder).
- [Spring reference — Transaction management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html).
- [Vlad Mihalcea — The best way to use Spring's @Transactional(readOnly=true)](https://vladmihalcea.com/spring-transactional-readonly/) — the argument *for* readOnly; we disagree with the premise at our scale, not the mechanics.
