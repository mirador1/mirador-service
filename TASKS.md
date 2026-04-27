# TASKS — mirador-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 📊 SLO/SLA backlog (post Quick wins ADR-0058 + iteration 2)

Quick wins SHIPPED 2026-04-25 : 3 SLOs as code (Sloth) + multi-burn-rate
alerting + Grafana SLO dashboard + ADR-0058 + sla.md.

Iteration 2 SHIPPED 2026-04-27 : SLO breakdown by endpoint dashboard,
latency heatmap dashboard, Apdex dashboard, 3 runbooks (availability /
latency / enrichment), chaos-driven Grafana annotations on all 3
repo-local SLO dashboards, Java-specific review-cadence addendum.

Remaining :

- 🟢 **RTO/RPO measurement via chaos** : kill DB pod → measure
  time-to-recovery (RTO) + lost-transaction count (RPO). Chaos Mesh
  scenario already exists ; needs a results dashboard + pass/fail
  threshold based on the SLA's documented RTO/RPO. Deferred from
  iteration 2 (needs working Chaos Mesh + DB pod, complex setup).

- 🟢 **Dedicated chaos endpoints** : iteration 2 wired annotations
  to existing `/customers/slow-query` + symptom-based 5xx/504
  detectors. To get distinct annotations for `db-failure` vs
  `kafka-timeout` vs `slow-query`, add explicit endpoints to
  `CustomerDiagnosticsController` (or a new `ChaosController`) and
  update annotation `expr` to filter on the new `uri` values. Source
  changes required, hence deferred.

## 🎨 README polish (post 2026-04-25 review)

Captured from portfolio review session feedback :

- 🟢 **README.fr.md sync** : Java README.md got a major rewrite 2026-04-25
  (badges trim 50+ → 8, TL;DR for hiring managers, "What this proves for
  a senior backend architect" matrix, URL fixes, "Customer onboarding &
  enrichment service" reframing). The French version still reflects the
  old structure — sync needed. Lefthook readme-i18n-sync hook flags it ;
  was bypassed for the initial push.

- 🟢 **GitHub mirror push verification** : after the README rewrite + URL
  fixes (`benoit.besson/mirador-service` → `mirador1/mirador-service-java`),
  verify the GitHub mirror at github.com/mirador1/mirador-service mirrors
  correctly (badge URLs, Wayback caching, anchor links).

- 🟢 **Mini-domain rename consideration** : the README narrative now
  describes "Customer onboarding & enrichment service" but the CODE still
  uses `Customer*` classes/endpoints. Genuine rename to `Onboarding*` or
  `Case*` is a 50+ file refactor — defer until there's a real recruiter
  signal that the term still feels generic. Narrative reframing in the
  README probably enough.

- 🟢 **Banner.svg refresh** : the `docs/assets/banner.svg` was authored
  before the multi-repo split. Update to mention the polyrepo structure
  + the SLO/SLA addition + the conservative LTS target. Same dimensions,
  same color scheme.

- 🟢 **Add screenshots to "Screenshots" section** of new SLO dashboard
  + the architect matrix rendered. Use the existing GIF demo recording
  workflow (record-demo.sh in shared) once the SLO dashboard is connected
  to a live LGTM stack.

## 🎯 Surface fonctionnelle — entités e-commerce

Foundation **shippée 2026-04-26** dans [stable-v1.2.3](https://gitlab.com/mirador1/mirador-service-java/-/tags/stable-v1.2.3) :
- ✅ V7 (`product`), V8 (`orders`), V9 (`order_line`) migrations + JPA + repositories
- ✅ Feature-sliced sous `com.mirador.{order,product}.*` (ADR-0008)
- ✅ REST controllers minimaux : `/products`, `/orders`, `/orders/{id}/lines/{lineId}`

### Reste à compléter (post-foundation)

- ✅ **ADR data model** — landed 2026-04-26 in shared as
  [shared ADR-0059](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md)
  (cross-language : Java + Python + UI). Documents 6 invariants for property tests.
- ☐ **JaCoCo coverage ≥ 90 %** sur `com.mirador.{order,product}.*` :
  configurer `<rule><BUNDLE>` à 90 % dans `jacoco-maven-plugin` — fail
  build si en-dessous. Aujourd'hui min global = 70 %.
- ☐ **Property-based tests (jqwik)** : invariants
  `total_amount == Σ(line.qty × line.unit_price_at_order)`,
  `stock_quantity ≥ 0`, immutabilité de `unit_price_at_order`.
- ☐ **Spring Boot integration tests** (`@SpringBootTest` + Testcontainers
  Postgres) : full HTTP roundtrip (create → read → update → delete),
  rollback, contraintes JPA.
- ☐ **PIT mutations score ≥ 75 %** sur le nouveau code.
- ☐ **Coverage report HTML** publié dans CI artifacts (`target/site/jacoco/`).
- ☐ **Trend tracking** : commenter le delta coverage dans la pipeline MR.
- ☐ **`bin/dev/api-smoke.sh`** : ajouter POST /orders avec 2 OrderLines,
  GET, DELETE, vérifier total recalculé.
- ☐ **`bin/dev/sections/code.sh`** : inclure les nouveaux modules.

### Cross-repo coordination (ADR-0001 polyrepo)

OpenAPI contract doit matcher [Python](https://gitlab.com/mirador1/mirador-service-python)
(même paths, schemas, response codes). UI ([mirador-ui](https://gitlab.com/mirador1/mirador-ui))
doit pouvoir basculer entre les 2 backends transparently.

## 🔧 Other

