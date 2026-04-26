# TASKS — mirador-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 📊 SLO/SLA backlog (post Quick wins ADR-0058)

Quick wins SHIPPED 2026-04-25 : 3 SLOs as code (Sloth) + multi-burn-rate
alerting + Grafana SLO dashboard + ADR-0058 + sla.md. Below = next iterations.

- 🟢 **Dashboard "SLO breakdown by endpoint"** : current dashboard is
  service-wide. Add a panel sliced by `uri` to identify which endpoints
  contribute most to the budget burn. When SLO breaches happen — answers
  "which endpoint is dragging us down ?".

- 🟢 **Chaos-driven SLO demo** : wire `/customers/diagnostic/{slow-query,
  db-failure,kafka-timeout}` to intentionally burn budget for demo
  purposes. A "demo mode" Grafana annotation that overlays the burn rate
  timeseries with the chaos test markers. Sells the observability story
  in 30 seconds.

- 🟢 **Runbook section "What to do when SLO breached"** :
  `docs/runbooks/slo-availability.md`, `slo-latency.md`, `slo-enrichment.md`
  (URLs already referenced in `slo.yaml` annotations). Each : symptoms,
  first investigation steps, common root causes, escalation path,
  rollback procedure. Currently empty — links 404 on Alertmanager.

- 🟢 **Latency heatmap par endpoint** : Grafana panel using
  `http_server_requests_seconds_bucket` series, x=time × y=latency-bucket,
  color=request count. Shows tail-latency distribution in one glance —
  complement to p99 SLO compliance.

- 🟢 **Apdex score dashboard** : add `Apdex(0.5s, 2s)` calculation to the
  SLO dashboard. Apdex = (satisfied + tolerating/2) / total. Single number
  that captures "user satisfaction" — easier to communicate to non-SRE
  stakeholders than 3 separate SLOs.

- 🟢 **RTO/RPO measurement via chaos** : kill DB pod → measure
  time-to-recovery (RTO) + lost-transaction count (RPO). Chaos Mesh
  scenario already exists ; needs a results dashboard + pass/fail
  threshold based on the SLA's documented RTO/RPO.

- 🟢 **Monthly SLO review meeting cadence** : document in
  `docs/slo/review-cadence.md`. What to bring (compliance %, top burn
  contributors, capacity changes, deploy correlation), who attends,
  what's the output (tighten/relax SLO, error budget policy update).

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

## 🎯 Augmenter la surface fonctionnelle — nouvelles entités

☐ Ajouter 4 entités au backend Java pour étendre la surface fonctionnelle
au-delà de `Customer`.

**Scope final (validé utilisateur 2026-04-26)** : Pattern A (e-commerce)
+ relation `User` extraite du Pattern B (SaaS rôles) :

- **`User`** — identité auth avec rôles (USER / ADMIN / MANAGER), `email`,
  `password_hash` (à factoriser avec l'auth JWT existante côté `Customer`
  si applicable). Linked to `Customer` via `customer_id` (le user
  représente un opérateur attaché à un Customer).
- **`Order`** — entité principale, **DEUX FKs** :
  - `customer_id` → `Customer` existant (le buyer / owner de la commande)
  - `created_by_user_id` → `User` (l'opérateur qui a créé la commande)
  - statut (PENDING / CONFIRMED / SHIPPED / CANCELLED), `total_amount`
    calculé
- **`Product`** — entité indépendante : `name`, `description`,
  `unit_price`, `stock_quantity`.
- **`OrderLine`** — jonction Order ↔ Product : `quantity`,
  `unit_price_at_order` (immutable, snapshot du prix au moment de la
  commande pour audit).

### Acceptance criteria

- [ ] 4 migrations Flyway (V8 = user, V9 = order, V10 = product, V11 = order_line)
- [ ] Spring Data JPA repositories par entité
- [ ] Domain feature-slicing per ADR-0008 (`com.mirador.user.*`,
      `com.mirador.order.*`, `com.mirador.product.*`)
- [ ] REST controllers : full CRUD (`/users`, `/orders`, `/products`,
      `/orders/{id}/lines`) + OpenAPI annotations + Spring Security rôles
- [ ] **Auth refactor si applicable** : si `Customer` portait l'auth JWT,
      la déplacer vers `User` (entité dédiée à l'identité avec rôles).
      `Customer` redevient pure entité métier (le buyer).
- [ ] Coverage ≥ 90 % sur le nouveau code
- [ ] Property tests sur invariants (total = Σ lignes, stock ≥ 0,
      OrderLine immutability, role transitions valides)
- [ ] Integration tests end-to-end (Postgres + actuator/health + JWT auth)
- [ ] ADR documentant le modèle de données + relations + le refactor auth
- [ ] Update `bin/dev/api-smoke.sh` avec les nouveaux endpoints
- [ ] CHANGELOG entry au prochain `stable-vX.Y.Z`

### Cross-repo coordination (cf. common ADR-0001 polyrepo)

Doit être implementé **EN PARALLÈLE** dans
[`mirador-service-python`](https://gitlab.com/mirador1/mirador-service-python)
(même API contract OpenAPI) ET visualisé dans
[`mirador-ui`](https://gitlab.com/mirador1/mirador-ui) (pages list / create
/ edit + role-aware visibility). Voir `TASKS.md` de chaque repo.
Acceptance partielle si l'un des 3 repos n'a pas livré.

## 🔧 Other
