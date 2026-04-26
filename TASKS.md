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

☐ Ajouter 3 entités au backend Java pour étendre la surface fonctionnelle
au-delà de `Customer`.

**🚩 État au flush 2026-04-26 12:14** : Claude a démarré le foundation
work (V7+V8+V9 migrations) mais l'utilisateur a interrompu les writes.
Raison de l'interruption pas explicite — possiblement scope trop large
d'un coup, ou souhait de redémarrer la session avec contexte propre.
**Action au prochain reprise** : confirmer avec l'utilisateur si on
reprend le foundation tel quel OU si on doit cadrer plus serré (ex :
juste Product + Order sans OrderLine, ou plus petite incrémentation).

**Scope final (validé utilisateur 2026-04-26)** : Pattern A simplifié —
`Customer` reste tel quel (continue à porter l'auth/identité existante),
3 nouvelles entités pour la surface e-commerce :

- **`Order`** — entité principale, FK `customer_id` → `Customer` (existant),
  statut (PENDING / CONFIRMED / SHIPPED / CANCELLED), `total_amount`
  calculé.
- **`Product`** — entité indépendante : `name`, `description`,
  `unit_price`, `stock_quantity`.
- **`OrderLine`** — entité (PAS un simple join — porte un état propre :
  quantité + prix snapshot + statut individuel + cycle de vie). Relation
  Order ↔ Product avec : `quantity`, `unit_price_at_order` (immutable,
  snapshot du prix au moment de la commande pour audit), statut individuel
  (PENDING / SHIPPED / REFUNDED).

**Contexte schéma existant** (vérifié 2026-04-26) : migrations Flyway
courent V1-V6. V6 = `app_user` (auth, séparé de `customer`). Donc les
nouvelles migrations sont **V7 (product), V8 (orders), V9 (order_line)**.
Tables existantes à NE PAS toucher : `customer`, `app_user`,
`audit_event`, `refresh_token`, `shedlock`.

**Pourquoi OrderLine entité et pas join pur** : carries snapshot du prix
(perdu sinon quand le produit change de prix), refund/cancel par ligne,
inventory tracking précis, possibilité de discount par ligne plus tard.
Test "est-ce une entité ?" : ID propre + état mutable au-delà des FKs → OUI.

### Acceptance criteria

#### Code & schéma

- [ ] 3 migrations Flyway (V7 = product, V8 = orders, V9 = order_line —
      next available numbers ; V1-V6 already used)
- [ ] Spring Data JPA repositories par entité (Order, Product, OrderLine)
- [ ] Domain feature-slicing per ADR-0008 (`com.mirador.order.*`,
      `com.mirador.product.*`)
- [ ] REST controllers : full CRUD (`/orders`, `/products`,
      `/orders/{id}/lines/{lineId}`) + OpenAPI annotations
- [ ] ADR documentant le modèle de données + relations (justifie OrderLine
      comme entité plutôt que join pur)

#### Tests (cf. ADR-0007 industrial best practices)

- [ ] **JUnit unit tests** (`src/test/java/com/mirador/{order,product}/`) :
      ≥ 1 test par méthode publique, AAA pattern, tests edge cases
      (null inputs, empty collections, boundary values).
- [ ] **Spring Boot integration tests** (`@SpringBootTest` + `@Testcontainers`
      Postgres) : full HTTP roundtrip (create → read → update → delete),
      validation des contraintes JPA, transactions rollback.
- [ ] **Property-based tests** (jqwik ou similaire) sur invariants :
      `total_amount == Σ(line.quantity × line.unit_price_at_order)`,
      `stock_quantity ≥ 0`, OrderLine.unit_price_at_order immutability.
- [ ] **Test PIT mutations** : score ≥ 75 % sur le nouveau code
      (existing PIT config dans pom.xml).

#### Couverture (gate explicite)

- [ ] **JaCoCo coverage ≥ 90 %** (lignes + branches) sur le nouveau code
      (`com.mirador.{order,product}.*`). Configurer `jacoco-maven-plugin`
      `<rule>` avec `BUNDLE` minimum 90 % — fail le build si en-dessous.
- [ ] **Coverage report HTML** publié dans CI artifacts (`target/site/jacoco/`).
- [ ] **Trend tracking** : intégrer le delta de coverage dans la CI MR
      pipeline (commenter le %.

#### Update outils

- [ ] Update `bin/dev/api-smoke.sh` avec les nouveaux endpoints
      (POST /orders avec 2 OrderLines, GET, DELETE, vérifier le total)
- [ ] Update `bin/dev/sections/code.sh` pour inclure les nouveaux modules
      dans les checks
- [ ] CHANGELOG entry au prochain `stable-vX.Y.Z`

### Cross-repo coordination (cf. common ADR-0001 polyrepo)

Doit être implementé **EN PARALLÈLE** dans
[`mirador-service-python`](https://gitlab.com/mirador1/mirador-service-python)
(même API contract OpenAPI) ET visualisé dans
[`mirador-ui`](https://gitlab.com/mirador1/mirador-ui) (pages list / create
/ edit). Voir `TASKS.md` de chaque repo. Acceptance partielle si l'un des
3 repos n'a pas livré.

## 🤔 Awaiting clarification (flush 2026-04-26)

- ☐ User wrote "Pertinent du projet l99" mid-session — interpretation
  unclear (was it questioning whether the bump-common-everywhere.sh
  effort is relevant to the project? referencing some issue #l99 or
  #199?). Ask at session restart what that meant.

- ☐ User interrupted my Write of `V7__create_product.sql` +
  `V8__create_orders.sql` + `V9__create_order_line.sql` shortly after.
  Possibly related to the "Pertinent" question. Don't restart the
  foundation work without confirming :
    1. Scope still A (3 entities = Order + Product + OrderLine), OR
       a smaller increment ?
    2. Migration numbering V7/V8/V9 (verified next-available) OK ?
    3. Begin with which entity first (Product is most independent, no
       FK dependencies) ?

## 🔧 Other

