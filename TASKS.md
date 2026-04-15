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
- [ ] **Pipeline history** — appel à l'API GitLab (`GET /projects/:id/pipelines`) pour
      afficher les 10 derniers pipelines avec statut et durée dans la page quality
- [ ] **Branches actives** — `git branch -r` avec date du dernier commit, affiché dans
      la page about ou quality

## Pending — autres demandes non traitées

- [ ] **Kafka ACLs** — "quand je clique sur ACLS sur la vue Kafka UI il affiche No Authorizer
      is configured on the broker" → documenter pourquoi (KRaft sans authorizer en dev) et/ou
      activer l'authorizer dans la config Kafka du docker-compose
- [ ] **Pyroscope** — "je ne vois que 3 profiles type liés à l'application, un pour la CPU
      et 2 pour la mémoire" → vérifier si les profils wall-clock et goroutine sont configurés

## Recently Completed

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
