![Mirador Service](docs/assets/banner.svg)

<sub>[English](README.md) · **Français**</sub>

<!-- Badges : les badges GitLab (canonical) + GitHub (mirror) restent en anglais
     par définition. Ils pointent les mêmes endpoints que la version EN. -->
[![pipeline](https://gitlab.com/mirador1/mirador-service/badges/main/pipeline.svg)](https://gitlab.com/mirador1/mirador-service/-/pipelines)
[![coverage](https://gitlab.com/mirador1/mirador-service/badges/main/coverage.svg)](https://gitlab.com/mirador1/mirador-service/-/pipelines)
[![latest release](https://gitlab.com/mirador1/mirador-service/-/badges/release.svg)](https://gitlab.com/mirador1/mirador-service/-/releases)
[![CodeQL](https://github.com/mirador1/mirador-service/actions/workflows/codeql.yml/badge.svg)](https://github.com/mirador1/mirador-service/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/mirador1/mirador-service/badge)](https://scorecard.dev/viewer/?uri=github.com/mirador1/mirador-service)

# Mirador — le mirador d'un système qui tourne

> **Observer. Comprendre. Agir.**
>
> _Construit avec les bons outils et les bonnes méthodes._

**Mirador** — *watchtower* en espagnol — est un point d'observation.
Le projet prend un backend concret de gestion de clients (API Customer)
et l'observe sous tous les angles en même temps : **le code, les métriques
runtime, les pipelines CI/CD, et l'outillage industriel standard câblé
autour**. Tout ce que tu vois dans l'UI associée
([`mirador-ui`](https://gitlab.com/mirador1/mirador-ui)) et dans Grafana
est le même système live observé par deux fenêtres différentes.

Ce repo contient le **backend Spring Boot 4 / Java 25**. C'est celui qui
est observé.

Ce que le projet met réellement en œuvre :

- **Outillage industriel de référence** : GitLab CI avec runner local,
  manifestes K8s en Kustomize (pas Helm), OpenTelemetry (traces + logs +
  metrics) vers la LGTM, Sonar, Semgrep, Trivy / Grype / Syft / cosign /
  Dockle, OWASP Dependency-Check, tests de mutation PIT, circuit-breakers
  Resilience4j + rate-limiting Bucket4j, Flyway, Testcontainers, Workload
  Identity Federation, release-please. Chaque choix est justifié par un
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

## Table des matières

- [Pourquoi ceci, pas cela — les arbitrages](#pourquoi-ceci-pas-cela--les-arbitrages)
- [Leviers de simplification](#leviers-de-simplification)
- [Intégration assistée par IA — où elle a contribué, où elle n'a pas](#intégration-assistée-par-ia--où-elle-a-contribué-où-elle-na-pas)
- [Limites connues](#limites-connues)
- [Détails techniques](#détails-techniques) → renvoi au README EN

---

## Pourquoi ceci, pas cela — les arbitrages

Chaque pattern industriel dans ce repo répond à un problème concret ;
la liste ci-dessous dit ce que j'ai **rejeté** et pourquoi. Le détail
complet avec contexte + alternatives + conséquences vit sous
[`docs/adr/`](docs/adr/) — 30+ ADRs à ce jour (rédigés en anglais
pour rester accessibles aux contributeurs internationaux).

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
| **Cloud** | GKE Autopilot sur GCP | **AWS EKS** éliminé mécaniquement : le control-plane coûte €66/mois = 33× le budget. **Azure AKS Automatic** — viable, rejeté sur tie-break pragmatique (voir [ADR-0030](docs/adr/0030-choose-gcp-as-the-kubernetes-target.md)). |

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
- L'outillage CI supply-chain (SBOM, Grype, cosign) — ~30 s d'exécution
  et détecte de vrais CVE ; retirer cet outillage retire un invariant.
- Le jeu d'ADRs — un journal de décisions qui ne coûte rien à maintenir
  et empêche de relitiger les mêmes trade-offs plus tard.

---

## Intégration assistée par IA — où elle a contribué, où elle n'a pas

Le projet a été construit en collaboration serrée avec un LLM de
raisonnement. Le partage entre ce qui vient du modèle et ce qui vient
d'une revue humaine vaut d'être explicite, parce qu'il change la
façon dont chaque partie doit être lue.

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

- **Le cold start est lent** — un `bin/demo-up.sh` à froid prend
  ~8 min (provision cluster 5 min + installs opérateurs 2 min + sync
  app 1 min). L'accès nécessite une étape en plus : `bin/pf-prod.sh`
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

---

## Détails techniques

Les sections techniques purement informatives (architecture, quick
start, référence des ports, CI/CD, screenshots, liens vers la doc
détaillée) vivent dans la version anglaise à partir de la section
[Architecture — dev (Docker Compose)](README.md#architecture). Elles
sont identiques en anglais parce qu'elles contiennent quasi-exclusivement
des commandes, des tables de ports et des chemins de fichiers.

Pour aller plus loin :
- [`docs/adr/`](docs/adr/) — les 30+ décisions architecturales
- [`docs/reference/technologies.md`](docs/reference/technologies.md) — glossaire complet
- [`docs/ops/`](docs/ops/) — runbooks, politique coûts, mirror CI, CI philosophy
- [`docs/architecture/`](docs/architecture/) — vue détaillée par couche

---

<sub>_Mirador_ — espagnol pour _mirador_ — se tient au-dessus d'un
système réel qui tourne et répond à : "que fait le code là, tout de
suite ?"</sub>
