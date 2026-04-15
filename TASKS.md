# Mirador Service — Persistent Task Backlog

<!--
  This file is the source of truth for pending work across sessions.
  Claude must READ this file at the start of every session.
  Claude must UPDATE this file whenever a task is added, started, or completed.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
-->

## Pending — Reports & Documentation pipeline

- [x] **Scheduled CI report job** — generate-reports job exists in .gitlab-ci.yml (reports stage).
      Runs only when REPORT_PIPELINE=true on schedule. Runs mvn verify + mvn site, pushes to
      reports/ branch (orphan), exposes pipeline artifact (30 days).
      TODO: create GitLab schedule in UI (CI/CD → Schedules, 02:00 UTC, REPORT_PIPELINE=true)
      and GITLAB_REPORTS_TOKEN CI variable (project access token, Reporter + write_repository).
- [x] **Javadoc enrichment** — @apiNote/@implNote added to: LoginAttemptService, JwtTokenProvider,
      AuditService.log(), MaintenanceEndpoint.run(), KafkaHealthIndicator.health(),
      JwtAuthenticationFilter (class + 2 private methods), ApiKeyAuthenticationFilter (class),
      QualityReportEndpoint (class + report()). All other classes already had adequate documentation.

## Pending — Maven site integration in Angular UI

- [x] Alternative: /quality/site full-page route added to mirador-ui. MavenSiteFullComponent
      fills the viewport with maven site iframe + topbar. Link from quality page raw reports header.

## Pending — Maven site enrichments proposed but not implemented

These were proposed at 2026-04-14T20:56 in response to "d'autres idées pour épaissir Maven site":

### Sécurité
- [x] **Trivy** — trivy:scan CI job added to service and UI .gitlab-ci.yml.
      Runs after docker-build on main/tags using aquasec/trivy image.
      Scans for HIGH/CRITICAL CVEs. allow_failure:true. Output: trivy-report.json (30-day artifact).
      Note: CI-only (not in /actuator/quality — Trivy scan happens after JAR is built and cannot
      be embedded in the JAR at build time without running Trivy inside the Maven build).
- [x] **License compliance** — license-maven-plugin:add-third-party generates target/THIRD-PARTY.txt
      at generate-resources phase. Packaged into META-INF/build-reports/THIRD-PARTY.txt.
      QualityReportEndpoint.buildLicensesSection() returns license summary + per-dep details.
      Flags GPL/AGPL/LGPL/CDDL/EPL as incompatible for commercial use.
      UI: ⚖️ License Compliance section with distribution grid and incompatible-dep table.

### Métriques de code avancées
- [x] **Complexité cyclomatique** — buildMetricsSection() now returns topComplexClasses (top 10
      by COMPLEXITY_MISSED+COMPLEXITY_COVERED). UI: table in metrics tab, amber > 15, red > 30.
- [x] **Tests les plus lents** — Already implemented: buildTestsSection() parses Surefire XML
      time attributes, sorts allTestCases by duration desc, returns top-10 slowest tests
      with name + time (formatted) + timeMs fields.
- [x] **Classes sans tests** — buildMetricsSection() returns untestedClasses (METHOD_COVERED=0,
      METHOD_TOTAL>0) + untestedCount. UI: table in metrics tab, sorted alphabetically.

### Dépendances enrichies
- [x] **Fraîcheur des dépendances** — buildDependenciesSection() resolves ${property} references
      from pom.xml <properties>, calls Maven Central Solr API in parallel (25 deps max, 8s timeout).
      Adds latestVersion + outdated to each dep, outdatedCount to section root.
      UI: Latest column, amber row highlight, outdated count badge in section header.
- [x] **Arbre de dépendances** — maven-dependency-plugin:tree generates target/dependency-tree.txt
      at generate-resources phase. Packaged into META-INF/build-reports/dependency-tree.txt.
      QualityReportEndpoint returns dependencyTree.tree (raw text) + totalTransitive count.
      UI: collapsible <pre class="dep-tree"> in Dependencies section.
- [x] **Conflits de version** — maven-dependency-plugin:analyze-only runs at test-compile phase,
      writes target/dependency-analysis.txt. Packaged into META-INF/build-reports/.
      QualityReportEndpoint.parseDependencyAnalysis() returns usedUndeclared + unusedDeclared lists.
      UI: lists with amber (used-undeclared) and grey (unused-declared) styling + count badges.

### Build & Infra
- [x] **Temps de startup** — StartupTimeTracker @Component captures ApplicationReadyEvent timestamp
      minus JVM start time. Exposed as startupDurationMs + startupDurationSeconds in runtime section.
      UI: "Startup Time: X.XXs" row in runtime tab.
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

- [~] **deploy:gke first run** — CI fixes applied: removed saas-linux-medium-amd64 from default
      (quota exhaustion), integration-test now runs on local runner via socket binding (no DinD).
      OIDC Workload Identity Federation applied to all GCP jobs (deploy:gke, terraform, cloud-run).
      WIF pool gitlab-pool + provider gitlab-provider already exist in GCP. gitlab-ci-deployer SA
      has roles/container.admin + storage.admin + iam.serviceAccountUser. GCP_SA_KEY removed.
      Pipeline #280 running (MR !36). URL: https://mirador1.duckdns.org.
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
