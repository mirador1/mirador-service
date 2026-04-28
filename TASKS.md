# TASKS — iris-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 📊 RPO measurement (paused)

RTO measured 2026-04-28 : **7 seconds** for postgres pod-kill on
GKE Autopilot ([shared/docs/runbooks/rto-rpo-measurement.md](https://gitlab.com/iris-7/iris-service-shared/-/blob/main/docs/runbooks/rto-rpo-measurement.md)).
Beats 30 s SLA target.

Remaining : **RPO measurement** with steady-state write traffic
during chaos window (k6 at 50 req/s POSTing /customers, count
post-recovery `SELECT id FROM customer WHERE id IN (...)` holes).
Cluster #2 was torn down to save costs — restart via
`bin/cluster/demo/up.sh` when ready, deploy iris-service-java alongside
postgres for write traffic.

## 🎯 e-commerce coverage (scheduled `java-ecommerce-coverage-batch` 2026-05-04 14:00)

- ☐ JaCoCo coverage ≥ 90 % on `org.iris.{order,product}.*`
- ☐ jqwik property tests : `total_amount`, `stock_quantity ≥ 0`,
  immutability `unit_price_at_order`
- ☐ Spring Boot integration tests (`@SpringBootTest` + Testcontainers
  Postgres) : full HTTP roundtrip + rollback + JPA constraints
- ☐ PIT mutations score ≥ 75 % on the new code
- ☐ `bin/dev/api-smoke.sh` : POST /orders + 2 OrderLines + GET +
  DELETE + total recalculé
- ☐ `bin/dev/sections/code.sh` : include the new modules

## 🤔 Customer\* mini-domain rename (chip spawned, awaiting click)

ADR-0064 chip spawned 2026-04-28 — analysis-only, documents 3-5
alternative names + phased plan. Waiting user click in UI to start
dedicated session. Independent of the Iris rebrand : if the project
is "Iris" and the domain is "Customer onboarding", should the
entity classes still be called `Customer*` or move to e.g.
`Subscriber` / `Member` / `Lead` ?

## 🎨 SLO dashboard screenshots

5 panels to capture (SLO overview / breakdown by endpoint / latency
heatmap / Apdex / chaos demo) once cluster restart + Grafana up.
Procedure documented around 2026-04-28 08:55.
