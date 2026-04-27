![Mirador Service](docs/assets/banner.svg)

<sub>[English](README.md) · **Français**</sub>

> **Ce que ce projet démontre comme maîtrise**
>
> _Un survol 30 secondes des thèmes centraux de la maîtrise backend actuelle —
> chaque axe est vérifié à chaque tag `stable-v*`. Source de vérité pour
> "ce que cette révision garantit" : `git show stable-vX.Y.Z`._
>
> - 🤖 **IA** — Spring AI 1.1.4 + LLM local Ollama (llama3.2) + 14 outils MCP en in-process (annotations `@Tool` par méthode, ADR-0062) + transport streamable-http compatible claude (`spring.ai.mcp.server.protocol=STREAMABLE`) + AI Observability (spans OTel `gen_ai.*` → Tempo) + log d'audit par appel d'outil.
> - 🔒 **Sécurité** — JWT HS256 (15 min, rotation refresh-token) + X-API-Key statique en repli + OAuth2/OIDC (Auth0 prod / Keycloak dev) + RBAC (`ROLE_ADMIN` / `ROLE_USER`) + rate-limit Bucket4j (100 req/min/IP) + IdempotencyFilter (POST/PATCH) + SecurityHeadersFilter (CSP/HSTS/X-Frame-Options) + portails sécurité par MR : grype + trivy + cosign sign+verify + dockle + OWASP dependency-check + secret-detection + semgrep-sast — tous verts.
> - 🧠 **Fonctionnel** — Onboarding & enrichissement client (génération de bio par Spring AI Ollama, observable via spans `gen_ai.*`) + domaine Order / Product / OrderLine (6 invariants vérifiés via tests de propriétés `jqwik`, gates JaCoCo par paquet) + endpoints Chaos Mesh (`/customers/diagnostic/{slow-query, db-failure, kafka-timeout}`).
> - ☁️ **Infrastructure & Cloud** — Cluster GKE de production `mirador-prod` (europe-west1) + IaC Terraform + cibles de déploiement multi-cloud (AKS, EKS, Cloud Run, Fly.io — jobs manuels CI) + cert-manager + ingress-nginx + GitOps Argo CD + pattern éphémère (ADR-0022) ciblant ≤ 2 €/mois en idle + Workload Identity Federation (zéro clé JSON de service account) + alertes budget via `bin/budget/budget.sh`.
> - 📊 **Observabilité** — Traces + logs + métriques OpenTelemetry → stack LGTM (Tempo / Loki / Mimir / Grafana) + 3 SLOs as code via Sloth (disponibilité / latence / enrichissement) + alerting multi-burn-rate + 4 dashboards (vue d'ensemble SLO, Apdex, heatmap latence, breakdown par endpoint) + annotations chaos-driven sur les SLO + 3 runbooks (slo-availability, slo-latency, slo-enrichment) + cadence de revue mensuelle documentée.
> - ✅ **Qualité** — Coverage JaCoCo unit+IT mergée à 70 % min + gates par paquet sur `com.mirador.{order,product}` + tests de mutation PIT + quality gate SonarCloud + lint OpenAPI 3.1 Spectral + hadolint + Checkstyle + SpotBugs + findsecbugs + tests de propriétés jqwik + tests d'intégration Testcontainers (Postgres + Kafka + Redis).
> - 🔄 **CI/CD** — GitLab CI 19+ jobs sur `lint / test / integration / k8s / package / sonar / native / compat / deploy` + matrice de compat SB3/SB4 × Java17/21/25 (5 combos) + Conventional Commits enforced (Lefthook + commitlint) + auto-merge avec `--remove-source-branch=false` + cosign sign+verify + SBOM (syft) + Renovate hebdo + allowlist `changes:` du workflow.
> - 🏛 **Architecture** — Hexagonal Lite (ADR-0044, `port/` uniquement quand le couplage cross-feature émerge) + Feature-slicing (ADR-0008, `com.mirador.{customer, order, product, mcp, …}`) + sous-modules polyrepo flat α (ADR-0060) + exposition MCP `@Tool` par méthode (ADR-0062, règle "produces vs accesses") + 7 non-négociables Clean Code (binding, audités dans `docs/audit/clean-code-architecture-*.md`) + 60+ ADR.
> - 🛠 **DevX** — Renovate hebdo + hooks Lefthook commit-msg + pre-push + `bin/dev/stability-check.sh` (gate complet sectionné) + dispatcher `./run.sh` (28 cas : `app`, `db`, `obs`, `kafka`, `k8s-local`, `clean`, `nuke`, …) + `bin/dev/api-smoke.sh` (flows Hurl) + `bin/budget/*` discipline coût + tâches programmées pour TODO datés (ex. revisite CVE mcp-core 2026-05-26) + ADR drift checker + template CI Conventional Commits (partagé via `infra/common/`).

<!-- NOTE 2026-04-27 : ce README.fr.md est en retard sur la version anglaise
     (1118 lignes EN vs 342 lignes FR — la majorité du corps EN n'est pas
     encore traduite). Le bloc "Ce que ce projet démontre comme maîtrise"
     ci-dessus est synchronisé. Le reste du fichier reflète encore la
     structure pré-2026-04-25, à resynchroniser dans une session de
     traduction dédiée (tracé dans TASKS.md). -->

<!-- Bandeau de badges : 8 essentiels en haut. Couverture techno exhaustive
     plus bas dans la section "Couverture technologique". -->
[![pipeline](https://gitlab.com/mirador1/mirador-service-java/badges/main/pipeline.svg)](https://gitlab.com/mirador1/mirador-service-java/-/pipelines)
[![coverage](https://gitlab.com/mirador1/mirador-service-java/badges/main/coverage.svg)](https://gitlab.com/mirador1/mirador-service-java/-/pipelines)
[![SonarCloud](https://img.shields.io/badge/SonarCloud-quality_gate-F3702A?logo=sonarcloud&logoColor=white)](https://sonarcloud.io/project/overview?id=mirador1_mirador-service)
![Java 21 LTS · 25](https://img.shields.io/badge/Java-21_LTS_+_25_compat-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot 3.x · 4](https://img.shields.io/badge/Spring_Boot-3.x_LTS_+_4_compat-6DB33F?logo=springio&logoColor=white)
![GitOps Argo CD](https://img.shields.io/badge/Argo_CD-GitOps_+_canary-EF7B4D?logo=argo&logoColor=white)
![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-traces_+_logs_+_metrics-7F52FF?logo=opentelemetry&logoColor=white)
![SLO 99.5%](https://img.shields.io/badge/SLO-99.5%25_+_burn_rate-2D7FF9)

## Ce que ce projet démontre

Mirador est un démonstrateur de backend Java de niveau production centré sur des préoccupations
industrielles :
- diagnostiquer les incidents via logs, métriques et traces ;
- sécuriser les API avec JWT/OIDC, rate limiting et audit logs ;
- valider les décisions d'architecture via des ADR ;
- faire tourner des quality gates dans GitLab CI ;
- montrer comment un backend peut évoluer entre versions Java/Spring sans réécrire le système.

La branche par défaut utilise les versions récentes pour explorer la stack future.
**Une cible production conservatrice serait Java 21 LTS + Spring Boot 3.x** — la matrice de
compatibilité en CI prouve que les deux stacks compilent + testent vert depuis le même code,
donc un déploiement réel pourrait geler sur le couple LTS sans aucun changement de code.

## TL;DR pour les recruteurs (lecture 60 sec)

- **Pattern backend industriel** : pipeline d'onboarding client avec enrichissement style KYC,
  événements d'audit Kafka, traçabilité réglementaire, et endpoints de diagnostic d'incident —
  pas une démo CRUD.
- **Observability-first** : chaque couche (HTTP, JVM, DB pool, Kafka, Redis) émet traces OTel +
  métriques + logs structurés. **3 SLOs définis-as-code** (Sloth) avec alerting multi-window
  multi-burn-rate (Google SRE Workbook) et un dashboard Grafana SLO.
- **Supply chain sécurité** : JWT + rotation refresh-token, OWASP Dep-Check + Trivy + Grype +
  Syft + cosign + SBOM, policies cluster Kyverno, External Secrets Operator sur GSM.
- **Quality gates** : SonarCloud + PIT mutation + JaCoCo coverage + Testcontainers ITs +
  Spotless/Checkstyle/SpotBugs/PMD tous bloquants en CI. ArchUnit force le layering hexagonal.
- **Opérations résilientes** : Argo CD GitOps + Argo Rollouts canary, Resilience4j circuit-
  breaker + retry, endpoints chaos, alertes liées à des runbooks. Cycle de vie cluster via Terraform.

# Mirador — le mirador d'un système qui tourne

> **Observer. Comprendre. Agir.**
>
> _Construit avec les bons outils et les bonnes méthodes._

**Mirador** — *watchtower* en espagnol — est un point d'observation.
Le projet prend un backend concret de gestion de clients (API Customer)
et l'observe sous tous les angles en même temps : **le code, les métriques
runtime, les pipelines CI/CD, et l'outillage industriel standard câblé
autour**. Le même backend live est visible à travers deux « fenêtres »
complémentaires :

- l'UI associée ([`mirador-ui`](https://gitlab.com/mirador1/mirador-ui))
  le montre du **côté métier** — endpoints REST, données clients,
  payloads requête/réponse, couche UX ;
- Grafana le montre du **côté observabilité** — métriques Prometheus,
  traces Tempo, logs Loki, le tout via OpenTelemetry.

Les deux vues regardent exactement la même instance `mirador-service` ;
rien n'est mocké entre les deux.

Ce repo contient le **backend Spring Boot 4 / Java 25**. C'est celui qui
est observé.

Ce que le projet met réellement en œuvre :

- **Outillage industriel de référence** : GitLab CI avec runner local,
  manifestes K8s en Kustomize (pas Helm), OpenTelemetry (traces + logs +
  metrics) vers la LGTM, Sonar, Semgrep, Trivy / Grype / Syft / cosign /
  Dockle, OWASP Dependency-Check, tests de mutation PIT, circuit-breakers
  Resilience4j + rate-limiting Bucket4j, Flyway, Testcontainers, Workload
  Identity Federation. Chaque choix est justifié par un
  ADR dans [`docs/adr/`](docs/adr/) ou dans le glossaire
  [`docs/reference/technologies.md`](docs/reference/technologies.md).
- **Observabilité live d'un système qui tourne** : chaque couche (JVM,
  HTTP, pool DB, Kafka, Redis, Tomcat, compteurs métier) émet métriques
  et traces pour que l'UI associée (et Grafana) montrent ce que le code
  et le runtime font réellement.
- **Intégration assistée par IA** : la sélection, le câblage et la doc
  de la majorité de cet outillage — les ADRs, le glossaire, le hardening
  CI, la baseline K8s, le setup observabilité — ont été produits en
  collaboration serrée avec un LLM, et la même technique garde docs,
  tests et config alignés quand le système évolue.

Le scénario démo initial ("que faut-il pour diagnostiquer un incident ?")
reste le principe directeur — la stack est construite autour de ce cas
d'usage plutôt qu'autour des technologies elles-mêmes.

### Architecture Decision Records (ADRs) — le « pourquoi » canonique

Chaque trade-off non-trivial du repo est capturé comme un Architecture
Decision Record dans [`docs/adr/`](docs/adr/) (39 ADRs à ce jour, au
[format de Michael Nygard](https://github.com/joelparkerhenderson/architecture-decision-record/blob/main/locales/en/templates/decision-record-template-by-michael-nygard/index.md) :
contexte → décision → conséquences). Les ADRs sont rédigés en anglais
pour rester accessibles aux contributeurs internationaux.

Les deux glossaires complètent les ADRs côté « quoi » :
- [`docs/reference/technologies.md`](docs/reference/technologies.md) — chaque
  techno utilisée par le backend, ce qu'elle fait, pourquoi elle a été retenue.
- [`docs/reference/methods-and-techniques.md`](docs/reference/methods-and-techniques.md)
  — les pratiques (TDD, Conventional Commits, etc.) et leur rationale.

Quand ce README mentionne une décision spécifique en inline `(voir
ADR-NNNN)`, le lien va à l'enregistrement complet.

## Table des matières

- [Pourquoi ceci, pas cela — les arbitrages](#pourquoi-ceci-pas-cela--les-arbitrages)
- [Leviers de simplification](#leviers-de-simplification)
- [Intégration assistée par IA — où elle a contribué, où elle n'a pas](#intégration-assistée-par-ia--où-elle-a-contribué-où-elle-na-pas)
- [Limites connues](#limites-connues)
- [Détails techniques](#détails-techniques) → renvoi au README EN

---

## Pourquoi ceci, pas cela — les arbitrages

Chaque pattern industriel dans ce repo répond à un problème concret ;
la liste ci-dessous dit ce qui a été **rejeté** et pourquoi. Les liens
inline `(voir ADR-NNNN)` pointent vers l'enregistrement complet — voir
la [section ADR ci-dessus](#architecture-decision-records-adrs--le--pourquoi--canonique)
pour l'index complet.

| Décision | Ce que j'ai retenu | Ce que j'ai considéré et pourquoi ça a perdu |
|---|---|---|
| **Bus de messages** | Apache Kafka (KRaft, in-cluster) | **RabbitMQ** — plus simple mais ne démontre pas la rétention log-structurée pour le replay d'événements. **Kafka managé sur GCP** — €1k/mois, disproportionné pour une démo (voir [ADR-0005](docs/adr/0005-in-cluster-kafka.md)). |
| **Packaging K8s** | Overlays Kustomize (`local`/`gke`/`eks`/`aks`) | **Helm** — excellent pour distribuer des charts, mais la démo n'a qu'un seul chart ; Kustomize gagne sur le "pas de debug de langage de templating" (voir [ADR-0002](docs/adr/0002-kustomize-over-helm.md)). |
| **Base de données (overlay GKE)** | StatefulSet Postgres in-cluster | **Cloud SQL** — commencé là, reverti après avoir réalisé que PITR / backups / Query Insights ne sont pas dans le scope démo (voir [ADR-0003 remplacé → ADR-0013](docs/adr/0013-in-cluster-postgres-on-gke-for-the-demo.md)). |
| **Gestion des secrets** | External Secrets Operator + Google Secret Manager | **HashiCorp Vault** — plus puissant mais overkill pour 5 secrets. **Sealed Secrets** — met quand même les secrets dans git. **Secret K8s créé par la CI** (le pattern initial) — pas d'histoire de rotation, la CI obtient un accès write au cluster (voir [ADR-0016](docs/adr/0016-external-secrets-operator.md)). |
| **GitOps** | Argo CD (sous-ensemble : server + app-controller + repo-server + redis) | **Flux v2** — plus léger mais pas d'UI. **ApplicationSet + Dex + Notifications** — abandonné car la démo n'a qu'une seule app (voir [ADR-0015](docs/adr/0015-argocd-for-gitops-deployment.md)). |
| **Stratégie JWT** | HS256 + refresh tokens opaques en Postgres + blacklist Redis | **RS256 + JWKS** — nécessaire pour le chemin Keycloak, pas pour celui embarqué. **JWT refresh stateless** — nécessiterait quand même une liste de révocation, donc opaque + single-use est plus simple (voir [ADR-0018](docs/adr/0018-jwt-strategy-hmac-refresh-rotation.md)). |
| **Ingestion observabilité** | Push OTLP vers un collecteur (LGTM in-cluster) | **Scrape Prometheus** — pull-based nécessite un accès nœud par pod, pénible sur Autopilot. **Grafana Cloud direct** — marche bien mais coûte de l'argent dès qu'on sort du free tier (voir [ADR-0010](docs/adr/0010-otlp-push-to-collector.md)). |
| **Runner CI** | MacBook local (m1) | **Minutes SaaS** — épuise les 400 minutes gratuites en deux jours. **Auto-hébergé sur GKE** — chicken-and-egg si la CI construit le cluster (voir [ADR-0004](docs/adr/0004-local-ci-runner.md)). |
| **Coût cluster** | Autopilot éphémère (up seulement pendant les démos) | **GKE Standard 1 × e2-small always-on** — €30/mois vs €2/mois pour un cluster qui ne sert pas de trafic 99 % du temps (voir [ADR-0022](docs/adr/0022-ephemeral-demo-cluster.md)). |
| **Cloud** | GKE Autopilot sur GCP (cible par défaut) **+ OVH Managed K8s** (2ᵉ cible canonique pour souveraineté française + HDS — voir [ADR-0053](docs/adr/0053-ovh-canonical-target.md)) | **AWS EKS** éliminé mécaniquement : le control-plane coûte €66/mois = 33× le budget. **Azure AKS Automatic** — viable, rejeté sur tie-break pragmatique (voir [ADR-0030](docs/adr/0030-choose-gcp-as-the-kubernetes-target.md)). **Scaleway Kapsule** — souverain EU mais pas HDS, donc resté en module de référence (voir [ADR-0036](docs/adr/0036-multi-cloud-terraform-posture.md) amendée par 0053). |

Principe directeur : si une technologie est retenue, il doit être
possible d'articuler pourquoi une alternative *spécifique* a été
rejetée. Une raison de rejet qui n'existe pas est un avertissement
que le choix n'a pas été fait délibérément.

---

## Leviers de simplification

Si la stack devait rétrécir sans perdre la démonstration principale,
voici l'ordre dans lequel les éléments sortiraient, du coût le plus bas
(plus gros gain par LOC retirée) au plus haut :

1. **Keycloak.** L'auth JWT embarquée couvre le scénario démo. Keycloak
   n'existe que pour exercer le chemin OIDC-via-JWKS — utile pour
   montrer la *capacité*, mais la première chose à sacrifier si la
   stack doit se réduire à "ce qui sert du trafic". Le
   JwtAuthenticationFilter dégrade déjà gracieusement quand Keycloak
   est absent.
2. **Kafka.** La création, la mise à jour et la suppression de clients
   fonctionnent toutes sans bus de messages. Kafka est là pour exercer
   deux patterns (fire-and-forget + request-reply), nice-to-have, pas
   core. Le package `com.mirador.messaging` entier pourrait disparaître
   et l'app passerait encore 80 % des tests.
3. **Ollama + Spring AI.** L'endpoint `/customers/{id}/bio` est une
   vitrine pour circuit-breaker + retry + fallback — ces mêmes patterns
   sont exercés sur l'intégration HTTP JSONPlaceholder, plus simple.
   Ollama est la dépendance la plus coûteuse à faire tourner (1-8 GB
   RAM, 1 CPU, ou GPU).
4. **La deuxième version d'API (v2).** `@RequestMapping(version = "2.0+")`
   est une feature Spring 7 que je voulais démontrer — elle ajoute des
   méthodes controller dupliquées et des tests. Retirer v2 réduit de
   moitié le code controller sans perte de valeur métier.
5. **Trois des quatre overlays Kubernetes.** `local`, `gke`, `eks`,
   `aks` sont majoritairement le même manifeste avec un patch TLS +
   storage class différent. Pour un vrai déploiement single-cloud j'en
   garderais un seul.

Conservés quelle que soit la pression, avec la raison pour chacun :
- Observabilité (OTel, logs structurés) — sans ça chaque incident prod
  devient une enquête détective à partir de timestamps de logs.
- L'outillage CI supply-chain (**syft + Trivy + Grype + cosign**) — ~30 s
  d'exécution et détecte de vrais CVE ; retirer cet outillage retire un
  invariant. Trivy et Grype sont **complémentaires, pas redondants** :
  syft génère le SBOM ; Trivy scanne l'IMAGE Docker (OS + JRE, DB
  aquasec/trivy-db) ; Grype scanne le SBOM (coordonnées Maven, DB
  GitHub Advisory + NVD). Détail dans la
  [section EN du glossaire des technos](docs/reference/technologies.md#-container-cve-scanning--syft--trivy--grype-the-3-tool-sandwich).
- Le jeu d'ADRs — un journal de décisions qui ne coûte rien à maintenir
  et empêche de relitiger les mêmes trade-offs plus tard.

---

## Intégration assistée par IA — où elle a contribué, où elle n'a pas

Le projet a été construit en collaboration serrée avec un LLM de
raisonnement — en l'occurrence **[Claude Opus 4.7](https://www.anthropic.com/claude)
d'Anthropic** (fenêtre contextuelle d'un million de tokens), piloté
depuis le CLI
[Claude Code](https://docs.anthropic.com/claude/docs/claude-code).
Chaque commit porte un trailer `Co-Authored-By:` qui nomme le modèle
exact, de sorte que le log git fait office de piste d'audit sur les
zones où l'assistant a contribué.

Le partage entre ce qui vient du modèle et ce qui vient d'une revue
humaine vaut d'être explicite, parce qu'il change la façon dont
chaque partie doit être lue.

**Division du travail, en une phrase** :

> L'assistant énumère les options ; l'arbitrage — quelle option convient
> à ce contexte spécifique et laquelle est rejetée — est un appel
> humain, et les ADRs sous [`docs/adr/`](docs/adr/) en sont la trace
> d'audit.

Les propositions de technologies viennent d'un système qui a lu un
grand corpus de post-mortems de platform engineering et peut énumérer
des options plus vite qu'un humain. L'énumération est bon marché. Le
choix ne l'est pas.

**Zones où l'IA a apporté un fort levier avec un coût de vérification faible** :
- ADRs rédigés depuis un brief en bullet-points — structure cohérente
  contexte/décision/alternatives/conséquences produite en minutes.
- YAML boilerplate (NetworkPolicies, Ingresses, CRs SecretStore) à
  partir d'une description d'intention d'une ligne, puis revue
  ligne-à-ligne.
- Refactorings de classes pour matcher un nouveau pattern (annotations
  JSpecify, pattern underscore pour catch inutilisés, pattern matching
  pour switch) — travail mécanique avec critères d'acceptation clairs.
- Messages de commit et descriptions de MR rédigés depuis le diff.

**Zones où la première sortie LLM était fausse et a dû être corrigée** :
- Estimation de coût dans ADR-0021. Le "€0–3/mois" initial pour le
  cluster GKE Autopilot était deux ordres de grandeur à côté une fois
  la facturation réelle par pod-hour mesurée (~€190/mois), ce qui a
  conduit à ADR-0022 (pattern cluster éphémère, ~€2/mois réel).
- Suppression des shims Spring AI. Une suggestion initiale que Spring
  AI 1.1 GA n'avait plus besoin des classes de compat SB3-package
  s'est avérée fausse en CI — les shims restent load-bearing.
- NetworkPolicy pour le DNS. Le premier draft autorisait l'egress vers
  `kube-system` ; GKE Autopilot route le DNS via NodeLocal DNS Cache
  à `169.254.20.10`, ce qui nécessitait de lire `/etc/resolv.conf`
  sur un vrai pod pour le découvrir.

**Décisions restées humaines, avec l'assistant fournissant les inputs** :
- Le scope. Chaque proposition "ajoutons X" est passée au filtre
  "est-ce que ça résout un problème concret que la démo exerce ?"
  (ADR-0021 + règle éditoriale ADR-0022).
- Les arbitrages dans le tableau ci-dessus. L'assistant peut lister
  les alternatives ; sélectionner une option et documenter pourquoi
  les autres ont perdu est un appel de jugement qui appartient aux ADRs.
- Les éléments délibérément exclus — la section nice-to-have d'ADR-0022
  enregistre ce qui a été considéré et rejeté.

---

## Limites connues

Les éléments ci-dessous sont des caveats qu'une session live
révélera de toute façon. Les documenter en amont est moins coûteux
que de les découvrir au milieu d'une démo, et clarifie aussi quelles
limitations sont des trade-offs délibérés (liés à un ADR) plutôt que
des trous non-intentionnels.

- **Le cold start est lent** — un `bin/cluster/demo/up.sh` à froid prend
  ~8 min (provision cluster 5 min + installs opérateurs 2 min + sync
  app 1 min). L'accès nécessite une étape en plus : `bin/cluster/port-forward/prod.sh`
  pour ouvrir les tunnels locaux vers chaque service (ADR-0025). Je
  chauffe le cluster 10 min avant toute démo live et laisse
  `pf-prod.sh --daemon` tourner en arrière-plan.
- **`/actuator/health` rend DOWN quand un upstream est down** — et la
  démo tourne souvent sans Ollama (optionnel ; le CircuitBreaker gère
  l'absence). C'est voulu mais ça surprend les spectateurs : la probe
  readiness rejette le trafic même si l'API core fonctionne.
- **La sémantique du tag `:stable` est faible** — Argo CD suit
  le HEAD de `main`, ce qu'une démo fraîche utilise, mais rien ne
  garantit que l'image HEAD a été k6-smoke-tested. Un setup propre
  épinglerait un tag de release signé.
- **Réplicas unitaires partout** — si le pod JVM OOM en pleine démo
  il y a 30-60 s d'indisponibilité pendant que Spring Boot warmup. Voir
  [ADR-0014](docs/adr/0014-single-replica-for-demo.md) pour le
  raisonnement complet.
- **Pas de chaos engineering planifié avec SLO gates** — Chaos Mesh
  est installé, la page "chaos" de l'UI déclenche de vrais CRs
  PodChaos / NetworkChaos / StressChaos via le backend
  `ChaosController` ([`com.mirador.chaos`](src/main/java/com/mirador/chaos))
  avec Fabric8. Mais les runs restent interactifs (clic → expérience
  one-shot → auto-delete après la durée). Un vrai setup prod
  planifierait des expériences chaos hebdomadaires avec des gates
  SLO *(seuils chiffrés type "99 % des appels < 200 ms")* Prometheus
  qui feraient échouer la pipeline si le dashboard golden-signals
  dévie trop.

---

## Détails techniques

Les sections techniques purement informatives (architecture, quick
start, référence des ports, profils `docker compose`, CI/CD, screenshots,
liens vers la doc détaillée) vivent dans la version anglaise à partir
de la section [Architecture — dev (Docker Compose)](README.md#architecture).
Elles sont identiques en anglais parce qu'elles contiennent
quasi-exclusivement des commandes, des tables de ports et des chemins
de fichiers.

> **Profils `docker compose`** (2026-04-20) — `docker compose up -d`
> sur un clone neuf démarre uniquement le core (db + kafka + redis +
> app). Les extras (keycloak/ollama → `--profile full`, outils
> d'admin → `--profile admin`, docs statiques → `--profile docs`,
> stack observabilité → `--profile observability`) sont opt-in. Voir
> la table détaillée dans le README anglais.

> **Où vit la donnée — Caffeine vs Redis vs PostgreSQL** (2026-04-21) —
> trois couches non-recouvrantes : **Caffeine** (cache JVM in-process,
> ~µs, perdu au restart, NON partagé entre réplicas — pour les reads
> chauds tolérant à la perte) ; **Redis** (~1 ms, survit au restart,
> partagé entre réplicas — pour l'état coordonné inter-pods et les
> TTL : blacklist JWT, idempotency, rate-limit) ; **PostgreSQL** (~5-10 ms,
> durable, partagé — pour l'état qui doit survivre au cluster). Détail
> + matrice de décision dans la [section anglaise "Where data lives"](README.md#where-data-lives--caffeine-vs-redis-vs-postgresql).

Pour aller plus loin :
- [`docs/adr/`](docs/adr/) — les 35+ décisions architecturales
- [`docs/reference/technologies.md`](docs/reference/technologies.md) — glossaire complet
- [`docs/ops/`](docs/ops/) — runbooks, politique coûts, mirror CI, CI philosophy
- [`docs/architecture/`](docs/architecture/) — vue détaillée par couche
- [`deploy/terraform/`](deploy/terraform/README.md) — IaC : **deux cibles
  canoniques** GCP (par défaut, appliqué en CI) et **OVH** (appliqué à la
  demande, certifié HDS pour les données de santé — voir
  [ADR-0053](docs/adr/0053-ovh-canonical-target.md)) + modules de
  référence AWS, Azure, Scaleway (prêts à être revus, pas à être
  appliqués — voir [ADR-0036](docs/adr/0036-multi-cloud-terraform-posture.md)
  amendée par 0053). Tous les modules sont **dual-compatibles** Terraform
  par défaut et OpenTofu en option (`TF_BIN=tofu` pour basculer).

---

<sub>_Mirador_ — espagnol pour _mirador_ — se tient au-dessus d'un
système réel qui tourne et répond à : "que fait le code là, tout de
suite ?"</sub>
