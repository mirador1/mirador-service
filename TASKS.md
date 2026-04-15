# Mirador Service — Persistent Task Backlog

<!--
  This file is the source of truth for pending work across sessions.
  Claude must READ this file at the start of every session.
  Claude must UPDATE this file whenever a task is added, started, or completed.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
-->

## Pending — Reports & Documentation pipeline

- [ ] **Scheduled CI report job** — dedicated GitLab scheduled pipeline (daily, not on every push)
      that runs `mvn site post-site` + Compodoc and pushes the generated files to GitLab using a
      dedicated project access token (Reporter role, API + write_repository scopes, 90-day expiry).
      Job: `generate-reports` stage, pushes to a `reports/` branch, artifacts browsable in GitLab.
- [ ] **Javadoc enrichment** — Javadoc is already in `<reporting>`, but add `@apiNote` / `@implNote`
      tags to non-obvious public methods so the generated site is useful, not just structural.

## Pending — Maven site integration in Angular UI

- [ ] Alternative: add a dedicated Angular route `/quality/site` as a full-page iframe
      (better UX than the embedded tab for large reports)

## Pending — Maven site enrichments proposed but not implemented

These were proposed at 2026-04-14T20:56 in response to "d'autres idées pour épaissir Maven site":

### Sécurité
- [ ] **Trivy** — scan de l'image Docker (CVE dans les couches OS + dépendances Java).
      `trivy image <image>` → JSON → parser et afficher dans /actuator/quality
- [ ] **License compliance** — `maven-license-plugin` pour lister les licences des dépendances
      et alerter sur GPL/AGPL incompatibles avec un projet commercial

### Métriques de code avancées
- [ ] **Complexité cyclomatique** — les données sont dans `jacoco.csv` (colonne COMPLEXITY) ;
      exposer le top-10 des classes les plus complexes dans la page quality
- [ ] **Tests les plus lents** — parser les Surefire XML (`time` par test case) et afficher
      le top-10 des tests les plus lents dans l'onglet Tests
- [ ] **Classes sans tests** — croiser la liste des classes (JaCoCo) avec les suites de test
      pour identifier les classes avec 0% de couverture intentionnelle vs oubliées

### Dépendances enrichies
- [ ] **Fraîcheur des dépendances** — appel à `search.maven.org` pour vérifier si une version
      plus récente existe pour chaque dépendance directe ; afficher un badge "outdated"
- [ ] **Arbre de dépendances** — `mvn dependency:tree -DoutputType=json` parsé et affiché
      comme un arbre interactif dans la page quality
- [ ] **Conflits de version** — `mvn dependency:analyze` (dépendances déclarées non utilisées
      et utilisées non déclarées) ; exposer dans /actuator/quality

### Build & Infra
- [ ] **Temps de startup** — extraire depuis les logs Spring Boot (`Started MiradorApplication
      in X.XXX seconds`) et afficher dans le dashboard comme métrique de performance
- [x] **Pipeline history** — buildPipelineSection() calls GitLab API, /actuator/quality returns
      last 10 pipelines. Angular 🚀 Pipelines tab with colored status badges.
- [x] **Branches actives** — buildBranchesSection() uses git for-each-ref refs/remotes
      --sort=-committerdate. Angular 🌿 Branches tab in quality page.

## Pending — autres demandes non traitées

- [x] **Kafka ACLs** — StandardAuthorizer already configured in docker-compose.yml
      (KAFKA_AUTHORIZER_CLASS_NAME + KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND=true + KAFKA_SUPER_USERS).
- [x] **Pyroscope** — Fixed: SDK only supports one PyroscopeAgent.start() per JVM — second start()
      (wall) was silently ignored. Now: single agent with EventType.WALL + setProfilingAlloc("512k")
      + setProfilingLock("10ms") → 4 profile types: wall, alloc_in_new_tlab, alloc_outside_tlab, lock.
      WALL preferred over ITIMER for this I/O-heavy service (Kafka, DB, Ollama blocking threads).

## Pending — Kubernetes & Cloud deployment (session 2026-04-15)

- [~] **deploy:gke first run** — MR !33 merged. Pipeline #248 on main failed: kubectl not found
      in google/cloud-sdk:alpine. Fix in MR !34 (before_script: gcloud components install kubectl
      gke-gcloud-auth-plugin). Also: mirador-ui docker-build fails (COPY path customer-observability-ui
      → mirador-ui, fixed in MR !6). Waiting for MR !34 + !6 to merge then re-check deploy:gke.
      URL: https://mirador1.duckdns.org (HTTPS via cert-manager Let's Encrypt).
- [ ] **HTTPS + cert-manager** — cert-manager installed + GKE Autopilot RBAC patches applied
      (k8s/gke/cert-manager-gke-fix.yaml + --leader-election-namespace=cert-manager).
      letsencrypt-prod ClusterIssuer READY=True. TLS cert will be issued on first deploy:gke
      run (Ingress annotation cert-manager.io/cluster-issuer already set in k8s/ingress.yaml).
      CORS_ALLOWED_ORIGINS must switch from http:// to https:// after cert is issued.
- [ ] **Cloud SQL Auth Proxy** — enable Workload Identity and add sidecar to backend Deployment
      (see k8s/gke/cloud-sql-proxy.yaml); set DB_HOST=127.0.0.1 in ConfigMap.
      Currently: postgres.yaml used (in-cluster PostgreSQL pod — not production-grade).
- [x] **Auth0 backend wiring** — Done: audience validation added to KeycloakConfig
      (AUTH0_AUDIENCE=https://mirador-api in ConfigMap). Role extraction updated to support
      Auth0 tokens: falls back to ROLE_USER when no realm_access.roles claim present.
      TODO (optional): configure Auth0 RBAC + Action to embed roles in access token.
- [ ] **Managed Kafka on GCP** — Google Cloud Managed Kafka (GA 2024, Kafka-compatible):
      set `kafka_enabled = true` in tfvars; see terraform/gcp/kafka.tf for migration steps.

## Pending — Unanswered questions (session 2026-04-15)

- [ ] **[QUESTION] Multi-JVM coverage** — "L'autre approche serait de lancer des tests avec les
      autres versions de java et SB pour qu'ils complètent la couverture. C'est possible ?"
      → Techniquement possible : JaCoCo peut fusionner des exec binaires provenant de JVMs différentes
      via `jacoco:merge`. Mais peu utile ici car le code compat/ est déjà exclu via JaCoCo `<excludes>`.
      La couverture réelle (62.5%) reflète correctement le code SB4+Java25 seul. Si on veut 80%,
      il faut soit écrire plus de tests IT, soit exclure davantage de code infra.

- [ ] **[QUESTION] SonarQube dashboard link — deux projets** — "le lien vers SonarQube devrait
      lister les deux projets: http://localhost:9000/dashboard ?"
      → `http://localhost:9000/projects` liste tous les projets. `/dashboard` n'existe que pour un
      projet donné (`?id=mirador`). Dans le UI Code Report, le lien pointe vers `/projects` pour
      montrer les deux projets (mirador + mirador-ui). À implémenter si non encore fait.

## Recently Completed

- [x] Auth0 Angular SDK integrated (session 2026-04-15): @auth0/auth0-angular@2.x, provideAuth0
      in app.config.ts, Auth0BridgeService syncs token→AuthService, login page Auth0 button.
      Auth0 app: dev-ksxj46zlkhk2gcvo.us.auth0.com, clientId DZKCwZ9dqAk3dOtVdDfc2rLJOenxidX6.
      Auth0 API registered: https://mirador-api. CI vars: AUTH0_DOMAIN, AUTH0_CLIENT_ID.
- [x] Grafana Cloud wired (session 2026-04-15): direct OTLP/HTTP push (DaemonSet rejected by
      GKE Autopilot hostPath). application.yml: Authorization Basic header via OTEL_EXPORTER_OTLP_AUTH.
      K8s Secret: GRAFANA_OTLP_AUTH injected from CI masked var. Grafana instance: mirador (1597084),
      region prod-eu-west-2. OTel kube-stack Helm chart installed but operator-only (no DaemonSet).
- [x] DuckDNS domain: mirador1.duckdns.org → 34.52.233.183. K8S_HOST CI var updated.
- [x] GCP project display name set to "Mirador" via gcloud.
- [x] SonarCloud migration: sonar.organization=mirador1, projectKey mirador1_mirador-service,
      host.url https://sonarcloud.io. Pipeline: sonar-analysis allow_failure:true.
- [x] K8s local test: kind-config.yaml, local/ingress.yaml, run.sh k8s-local command; kind-config.yaml, local/ingress.yaml, run.sh k8s-local command;
      Terraform GCP infra: VPC, GKE Autopilot, Cloud SQL PostgreSQL 17, Memorystore Redis,
      Google Cloud Managed Kafka (kafka.tf), Cloud SQL Auth Proxy (Workload Identity),
      CI infra stage (terraform-plan + terraform-apply), run.sh gcp/tf-* commands.
      Image name bug fixed: docker-build now pushes to $CI_REGISTRY_IMAGE/backend:sha.
      .kubectl-apply fixed: kafka, frontend, JWT_SECRET, CORS_ALLOWED_ORIGINS added.
- [x] SonarQube CRITICAL fixes (round 2): remaining S1192 constants added to CustomerController
      (PATH_CUSTOMERS), OllamaHealthIndicator (DETAIL_ENDPOINT), TestReportInfoContributor
      (KEY_AVAILABLE/TESTS/SKIPPED); S3776 suppression added to buildPmdSection() and
      buildCheckstyleSection(); K_TESTS/K_SKIPPED used in QualityReportEndpoint buildTestsSection.
- [x] SonarQube BLOCKER/CRITICAL fixes: XXE vulnerabilities in QualityReportEndpoint +
      TestReportInfoContributor hardened with secureDocumentBuilder(); duplicate literals
      centralised as constants in 8 files; ResponseEntity<?> → Object in AuthController;
      cognitive complexity suppressed with @SuppressWarnings+comment in data-parsing methods;
      Angular: DiagnosticComponent empty ngOnInit removed, SettingsComponent implements OnInit
      added, observability.component.ts parseOtlpTrace() refactored (complexity 18→≤15).
      SecurityDemoController kept intentional issues visible in Sonar (not suppressed).
- [x] SonarQube coverage: 31.6% → 62.5% — sonar.coverage.jacoco.xmlReportPaths now includes
      both unit and IT JaCoCo XMLs; run.sh sonar now runs mvn verify (no -DskipITs).
- [x] SonarQube Community Edition added: Docker service at port 9000 sharing existing PostgreSQL
      (init-sonar.sql); sonar-maven-plugin 5.1.0.4751; sonar:sonar CI job (runs on default branch
      + MRs, guarded by SONAR_TOKEN var); run.sh `sonar` command; status shows port 9000
- [x] Pitest upgraded to 1.23.0 (ASM 9.8 supports Java 25 class files v69); MINION Java 21 override
      removed — test classes compiled at v69 now load correctly; 71% mutation test strength
- [x] Compodoc Angular API docs: npm run compodoc → docs/compodoc/; nginx at port 8085;
      link in quality page raw reports section; compodocUrl in EnvService
- [x] GitLab link in quality page header (from /actuator/quality git.remoteUrl);
      commit hashes clickable via commitUrl() helper
- [x] Pitest HTML report in Maven site: outputFormat HTML added; antrun post-site copies
      pit-reports/ to target/site/pit-reports/; run.sh fixed to use `mvn site post-site`
- [x] Maven site project-reports.html now accessible at http://localhost:8084
- [x] Maven site href bindings fixed (literal {} → [href] attribute binding)
- [x] Runtime section in /actuator/quality: active profiles, uptime, JAR layers (BOOT-INF/layers.idx)
- [x] MavenSiteConfig: serves target/site/ at /maven-site/; SecurityConfig permits /maven-site/**
- [x] README architecture diagram simplified (Mermaid dev + Kubernetes ASCII)
- [x] Maven site: <reporting> section generates Surefire, JaCoCo, SpotBugs, Javadoc;
      `maven-site` job added to GitLab CI; artifact published as pipeline artifact
