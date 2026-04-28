# TASKS — mirador-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 📊 SLO/SLA backlog (post Quick wins ADR-0058 + iterations 2 + 3)

Quick wins SHIPPED 2026-04-25 : 3 SLOs as code (Sloth) + multi-burn-rate
alerting + Grafana SLO dashboard + ADR-0058 + sla.md.

Iteration 2 SHIPPED 2026-04-27 : SLO breakdown by endpoint dashboard,
latency heatmap dashboard, Apdex dashboard, 3 runbooks (availability /
latency / enrichment), chaos-driven Grafana annotations on all 3
repo-local SLO dashboards, Java-specific review-cadence addendum.

Iteration 3 SHIPPED 2026-04-27 :
- ✅ Dedicated chaos endpoints `POST /customers/db-failure` +
  `POST /customers/kafka-timeout` ([!233](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/233))
- ✅ SLO dashboard annotation `expr` switched to deterministic URI
  filters + 4th 'Real 5xx (catch-all)' annotation
  ([!235](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/235))

RTO measured 2026-04-28 :
- ✅ **RTO** : 7 seconds for postgres pod-kill on GKE Autopilot
  (chaos scenario + measurement procedure documented in
  [shared/docs/runbooks/rto-rpo-measurement.md](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/runbooks/rto-rpo-measurement.md),
  shared [!8](https://gitlab.com/mirador1/mirador-service-shared/-/merge_requests/8)).
  Comfortably beats the 30s SLA target.

Remaining :

- 🟢 **RPO measurement** : same procedure but with steady-state write
  traffic during the chaos window (k6 at 50 req/s POSTing /customers,
  count post-recovery `SELECT id FROM customer WHERE id IN (...)`
  holes). Needs the Java app deployed alongside postgres.

## 🎨 README polish

Major sync wave **shipped 2026-04-27** :
- ✅ README.fr.md mastery block + 8-row matrix +
  "Customer onboarding" reframing + URL fixes
  ([!237](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/237))

Remaining (low priority) :

- 🟢 **GitHub mirror push verification** : confirm the GitHub mirror at
  github.com/mirador1/mirador-service mirrors correctly (badge URLs,
  Wayback caching, anchor links). Manual check, ad-hoc.

- 🟢 **Mini-domain rename consideration** : the README narrative
  describes "Customer onboarding & enrichment service" but the CODE
  still uses `Customer*` classes. Refactor 50+ files when there's a
  real recruiter signal that the term feels generic.

- 🟢 **Banner.svg refresh** : `docs/assets/banner.svg` predates the
  multi-repo split. Update to mention the polyrepo structure + the
  SLO/SLA addition + the conservative LTS target.

- 🟢 **Add screenshots to "Screenshots" section** of new SLO dashboard
  + the architect matrix rendered. Use `record-demo.sh` (in shared)
  once the SLO dashboard is connected to a live LGTM stack.

## 🎯 Surface fonctionnelle — entités e-commerce

Foundation **shippée 2026-04-26** dans [stable-v1.2.3](https://gitlab.com/mirador1/mirador-service-java/-/tags/stable-v1.2.3) :
- ✅ V7 (`product`), V8 (`orders`), V9 (`order_line`) migrations + JPA + repositories
- ✅ Feature-sliced sous `com.mirador.{order,product}.*` (ADR-0008)
- ✅ REST controllers minimaux : `/products`, `/orders`, `/orders/{id}/lines/{lineId}`

Wave 2 **shippée 2026-04-27** :
- ✅ ADR data model — [shared ADR-0059](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md)
- ✅ Server-side product search ([!236](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/236))
- ✅ PUT /orders/{id}/status state-machine endpoint ([!238](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/238))
- ✅ GET /products/{id}/orders ([!241](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/241))
- ✅ JaCoCo HTML artifact + cobertura widget on MR pipelines ([!239](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/239))
- ✅ `bin/dev/api-smoke.sh` (Hurl) PUT /orders/{id}/status flow ([!240](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/240))

### Reste à compléter (scheduled)

The following items are bundled into the
[`java-ecommerce-coverage-batch`](file:///Users/benoitbesson/.claude/scheduled-tasks/java-ecommerce-coverage-batch/SKILL.md)
scheduled task (2026-05-04 14:00) :

- ☐ **JaCoCo coverage ≥ 90 %** sur `com.mirador.{order,product}.*`
- ☐ **Property-based tests (jqwik)** : invariants `total_amount`,
  `stock_quantity ≥ 0`, immutabilité de `unit_price_at_order`
- ☐ **Spring Boot integration tests** (`@SpringBootTest` + Testcontainers
<<<<<<< HEAD
  Postgres) : full HTTP roundtrip (create → read → update → delete),
  rollback, contraintes JPA.
- ☐ **PIT mutations score ≥ 75 %** sur le nouveau code.
- ☐ **`bin/dev/api-smoke.sh`** : ajouter POST /orders avec 2 OrderLines,
  GET, DELETE, vérifier total recalculé.
- ☐ **`bin/dev/sections/code.sh`** : inclure les nouveaux modules.
=======
  Postgres) : full HTTP roundtrip + rollback + contraintes JPA
- ☐ **PIT mutations score ≥ 75 %** sur le nouveau code
- ☐ **`bin/dev/sections/code.sh`** : inclure les nouveaux modules
>>>>>>> b51ef7e (chore(tasks): strike 2026-04-27 wave + scheduled-batch pointer for coverage)

### Cross-repo coordination (ADR-0001 polyrepo)

OpenAPI contract doit matcher [Python](https://gitlab.com/mirador1/mirador-service-python)
(même paths, schemas, response codes). UI ([mirador-ui](https://gitlab.com/mirador1/mirador-ui))
doit pouvoir basculer entre les 2 backends transparently.
