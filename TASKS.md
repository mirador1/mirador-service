# TASKS — mirador-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 🌀 IRIS REBRAND (in flight 2026-04-28)

The project is being renamed **Mirador → Iris**. Visual locked,
narrative phase in flight.

### ✅ Decisions verrouillées

- **Name** : `Iris` (sur Prism / Echoes / keep Mirador)
- **7 axes ROYGBIV** : OBSERVE · INFRA·CLOUD · SECURITY · CI·CD · ARCH · AI·ML · QUALITY
- **Visual** : `02o-iris-final.svg` (in `/tmp/mirador-rebranding/`) — robot diaphragm + transparence (opacity 0.55) + refract gradient progressif (5 stops) + HUD overlay (16 ticks · crosshair · `f/2.8` · `● LOCK` · `7-BLADE`)
- **Tagline** : `7 FACETS` (philosophy : "no single truth — a system is composed from partial polished views, like the facets of a gemstone or the blades of a diaphragm")
- **Color mapping** : RED=OBSERVE · ORANGE=INFRA·CLOUD · YELLOW=SECURITY · GREEN=CI·CD · CYAN=ARCHITECTURE · BLUE=AI·ML · VIOLET=QUALITY

### 🔄 Phases du rename (est. 5650 refs / 1033 files cross-5-repos)

| Phase | Scope | Status |
|---|---|---|
| **0. State flush** | Update TASKS.md across 5 repos | ✅ in progress (this commit) |
| **1. Banner SVG** | Deploy `02o-iris-final.svg` → `docs/assets/banner.svg` | ⏳ next |
| **2. README narrative** | Mirador → Iris in copy + new framing "observability-first showcase d'un projet moderne complet (cloud · sécurité · IA · stack tech à jour)" | ⏳ next |
| **3. Project metadata** | `pom.xml` description, repo README badges, GitLab project description | ⏳ next |
| **4. Code-level Java** | `com.mirador.*` → `com.iris.*` (598 files Java, 2705 refs), Maven `groupId`, JPA `@Table`, OTel resource attrs, MicroMeter timer prefixes | 🔴 dedicated session — too risky inline |
| **5. Repo names sur GitLab** | `mirador1/mirador-service-java` → `iris1/iris-service-java` (group rename + project rename) | ⚠️ user UI action |
| **6. External resources** | DuckDNS `mirador1.duckdns.org` → `iris1.duckdns.org`, GCP project display name, Auth0 tenant URL, Helm release names | ⚠️ user action |

### Cross-repo coordination

The rename touches all 5 mirador1 repos. Phase 1-3 done autonomously
in this session ; Phase 4 spawned to a dedicated session ; Phase 5-6
need user UI / API access. Submodule SHAs (`infra/common/`, `infra/shared/`)
will need bumping after the shared/common renames complete.

---

## 📊 SLO/SLA backlog

Quick wins SHIPPED 2026-04-25 : 3 SLOs as code (Sloth) + multi-burn-rate
alerting + Grafana SLO dashboard + ADR-0058 + sla.md.

Iteration 2 SHIPPED 2026-04-27 : SLO breakdown by endpoint dashboard,
latency heatmap, Apdex, 3 runbooks, chaos-driven Grafana annotations.

Iteration 3 SHIPPED 2026-04-27 :
- ✅ Dedicated chaos endpoints `POST /customers/db-failure` +
  `POST /customers/kafka-timeout` ([!233](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/233))
- ✅ SLO dashboard URI annotations + 4th 'Real 5xx' annotation ([!235](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/235))

RTO measured 2026-04-28 :
- ✅ **RTO = 7 seconds** for postgres pod-kill on GKE Autopilot
  ([shared/docs/runbooks/rto-rpo-measurement.md](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/runbooks/rto-rpo-measurement.md), shared [!8](https://gitlab.com/mirador1/mirador-service-shared/-/merge_requests/8)). Beats 30s SLA target.

Remaining :

- 🟢 **RPO measurement** : same procedure but with steady-state write
  traffic during the chaos window (k6 at 50 req/s POSTing /customers,
  count post-recovery `SELECT id FROM customer WHERE id IN (...)`
  holes). **Cluster up #2 was torn down** to save costs ; restart
  via `bin/cluster/demo/up.sh` when ready. Java app needs to be
  deployed alongside postgres for write traffic.

## 🎯 Surface fonctionnelle — entités e-commerce

Foundation **shippée 2026-04-26** dans [stable-v1.2.3](https://gitlab.com/mirador1/mirador-service-java/-/tags/stable-v1.2.3) :
- ✅ V7/V8/V9 migrations + JPA + repositories
- ✅ Feature-sliced sous `com.mirador.{order,product}.*` (ADR-0008) ⚠️ to rename to `com.iris.*`
- ✅ REST controllers `/products`, `/orders`, `/orders/{id}/lines/{lineId}`

Wave 2 **shippée 2026-04-27** :
- ✅ ADR data model — [shared ADR-0059](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md)
- ✅ Server-side product search ([!236](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/236))
- ✅ PUT /orders/{id}/status state-machine ([!238](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/238))
- ✅ GET /products/{id}/orders ([!241](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/241))
- ✅ JaCoCo HTML artifact + cobertura widget ([!239](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/239))
- ✅ Hurl smoke flow ([!240](https://gitlab.com/mirador1/mirador-service-java/-/merge_requests/240))

### Reste à compléter (scheduled `java-ecommerce-coverage-batch` 2026-05-04 14:00)

- ☐ **JaCoCo coverage ≥ 90 %** sur `com.mirador.{order,product}.*`
- ☐ **jqwik property tests** : `total_amount`, `stock_quantity ≥ 0`,
  immutabilité `unit_price_at_order`
- ☐ **Spring Boot integration tests** (`@SpringBootTest` + Testcontainers
  Postgres) : full HTTP roundtrip + rollback + contraintes JPA
- ☐ **PIT mutations score ≥ 75 %** sur le nouveau code
- ☐ **`bin/dev/api-smoke.sh`** : POST /orders + 2 OrderLines + GET +
  DELETE + total recalculé
- ☐ **`bin/dev/sections/code.sh`** : inclure les nouveaux modules

## 🤔 Customer\* mini-domain rename (chip spawned, awaiting click)

Spawned 2026-04-28 07:30 as `mcp__ccd_session__spawn_task` chip —
analysis-only ADR-0064 documenting 3-5 alternative names + phased
plan. **Awaiting user click** in UI to start dedicated session in
isolated worktree.

Independent of the Mirador → Iris rename above (this is a
sub-question : if the project is "Iris" and the domain is "Customer
onboarding", should the entity classes still be called `Customer*`
or move to e.g. `Subscriber`/`Member`/`Lead`?).

## 🎨 README polish (low priority)

- 🟢 **GitHub mirror push verification** : automated check of mirror
  sync + tag completeness. Manually verified 2026-04-28 (5 mirrors
  caught up). Could be wired as a stability-check section.

- 🟢 **Banner.svg refresh** : ✅ resolved by Iris rebrand Phase 1
  above (deploys `02o-iris-final.svg`).

- 🟢 **Add screenshots to "Screenshots" section** of new SLO
  dashboard. Manip côté user : 5 panels à capturer (SLO overview /
  breakdown by endpoint / latency heatmap / Apdex / chaos demo)
  une fois cluster restart + Grafana up. Procedure documented in
  this conversation around 08:55.
