# Changelog

All notable changes to Mirador — Spring Boot backend + Angular UI.

Format: a lightly-formatted summary per `stable-vX.Y.Z` tag, because
[release-please](https://github.com/googleapis/release-please) is
configured on this repo (`config/release-please-config.json`) but not
yet activated (pending `$RELEASE_PLEASE_TOKEN` CI variable
provisioning). Once activated, release-please takes over maintenance
of this file via automated release-PRs on each `main` merge; until
then, this file is the hand-rolled equivalent.

For commit-level granularity between tags:

```bash
git log --oneline stable-v1.0.10..stable-v1.0.11
```

## [stable-v1.0.11] — 2026-04-22

**Phase A closed + shields retired + docs polish.**

First real post-Phase-A stability checkpoint.

### Highlights

- **CI shields retired** ([`314012f`](https://gitlab.com/mirador1/mirador-service/-/commit/314012f))
  — user raised Docker Desktop VM to 16 GB, both `test:k8s-apply` +
  `test:k8s-apply-prom` back to BLOCKING. First successful exit of the
  [ADR-0049](docs/adr/0049-ci-shields-with-dated-exit-tickets.md)
  "dated exit ticket" pattern (18 h lead time vs 30 d ceiling).
- **SSH-signed commits** end-to-end. GitHub `required_signatures` active
  on both repos. Helper: [`bin/dev/setup-signed-commits.sh`](bin/dev/setup-signed-commits.sh).
- **Custom Checkstyle config** replacing `google_checks.xml` with
  industry size rules (FileLength 1000, MethodLength 100, LineLength 120).
- **New ADRs**:
  [0049](docs/adr/0049-ci-shields-with-dated-exit-tickets.md)
  (CI shields pattern),
  [0050](docs/adr/0050-ci-yaml-modularisation-plan.md)
  (CI YAML modularisation plan — Proposed).
- **New audit docs**:
  [Clean Code + Clean Architecture audit](docs/audit/clean-code-architecture-2026-04-22.md)
  (80 %/70 % posture), [user-actions closure session](docs/audit/session-2026-04-22-user-actions-closed.md).
- **Auth0 tenant state snapshot**:
  [`docs/api/auth0-current-tenant-state.md`](docs/api/auth0-current-tenant-state.md).
- **User-action runbooks** shipped for the 3 actions Claude can't run
  directly: [Docker VM](docs/how-to/docker-desktop-vm-cap.md) +
  signed-commits setup + GitHub admin.

### Meta

- Jargon `cost-bearing` glossed per CLAUDE.md vulgarisation rule.

## [stable-v1.0.10] — 2026-04-22

**Phase A quality enforcement layer.**

The file-length gate + PMD/Checkstyle/ESLint tuning ship as signal-only
(`failOnViolation=false` still — Phase C flips enforcement after
Phase B outlier splits).

### Added

- [`docs/audit/quality-thresholds-2026-04-21.md`](docs/audit/quality-thresholds-2026-04-21.md)
  — 40+ rules × 4 tools (PMD, Checkstyle, ESLint, Sonar), industry
  baseline vs current state, 3-phase sequencing.
- `section_file_length` in [`bin/dev/stability-check.sh`](bin/dev/stability-check.sh)
  — ≥ 1500-line BLOCK gate with documented allowlist.
- PMD industry thresholds in [`config/pmd-ruleset.xml`](config/pmd-ruleset.xml)
  (NcssCount class 1500→750, TooManyMethods 10→25,
  ExcessiveParameterList 10→7, CyclomaticComplexity class 80→60).
- UI ESLint size + complexity rules at WARN (`max-lines` 400,
  `max-lines-per-function` 80, `complexity` 10, `max-params` 5,
  `max-depth` 4, `max-nested-callbacks` 4).
- UI workflow path filter includes `eslint.config.mjs` +
  `.gitleaks.toml` + `.prettierrc` (closes silent-merge gap from
  pre-Phase-A).

### Changed

- Maven PMD + Checkstyle plugins now print violations to console
  (`printFailingErrors=true`, `consoleOutput=true`). Still
  `failOnViolation=false`.

## [stable-v1.0.9] — 2026-04-21

**Phase 2 complete + Phase 3 partial.** Large feature + infrastructure
wave.

### Phase 2 (all 8 items)

- **2.1 S1**: cosign re-verify at deploy time (defense in depth).
- **2.2 O1**: Grafana exemplar→Tempo trace linking.
- **2.3 D1**: OpenAPI → TypeScript types auto-gen + CI drift gate.
- **2.4 T1**: axe-core Playwright a11y smoke tests.
- **2.5 T3**: jqwik property-based AggregationService test.
- **2.6 D3**: Hurl smoke collection + `bin/dev/api-smoke.sh`.
- **2.7 DEMO3**: guided onboarding tour (signals, 0 JS dep).
- **2.8 DOC3**: ADR supersession graph + auto-generated flat index.

### Phase 3 (4/4)

- **3.1 O2**: PrometheusRule + 6 runbooks + ADR-0048 +
  promtool-check-rules CI gate.
- **3.2 T2**: k6 nightly load test against GKE endpoint.
- **3.3 DEMO1**: `/find-the-bug` interactive (3 puzzles).
- **3.4 DEMO2**: `/incident-anatomy` scripted 5-min walkthrough.

### Chaos feature (CHAOS-1)

- `com.mirador.chaos` feature slice with Fabric8 client + 3 experiments
  (PodChaos/NetworkChaos/StressChaos) + RBAC + UI buttons.

### Auth

- Auth0 login end-to-end working — interceptor race fixed, multi-role
  `isAdmin` reads 3 claim shapes (built-in, Keycloak, Auth0).

### CI hygiene

- `test:k8s-apply` + `test:k8s-apply-prom` shields (Docker VM cap,
  Revisit 2026-05-21 — retired in v1.0.11).

### New CLAUDE.md rules

- File length hygiene (≥1000 split plan, ≥1500 split now).
- Docker cleanup cadence (4 triggers).
- Subdirectory hygiene tightened (10/15 from 12-15).

## [stable-v1.0.8] — 2026-04-21

**CI hygiene wave finish.**

- 6 Spectral warnings cleared (0 errors, 0 warnings on `/v3/api-docs`).
- Tag-on-green rule mirrored to both project `CLAUDE.md`.
- `test:k8s-apply-prom` job added (kube-prometheus-stack kind validation).
- `terraform-plan` scoped-out when `TF_STATE_BUCKET` unset.

## [stable-v1.0.7] — 2026-04-21

**CI modularisation prep.**

- Stability-check gains `section_adr_proposed` (flags ADRs stuck in
  Proposed > 30 d) + `section_helm_lint` (no-op until `deploy/helm/`).
- Lighthouse absolute thresholds on a11y/bp/seo (perf already had one).
- Trivy CVE delta by ID.

## [stable-v1.0.6] — 2026-04-21

**`new_security_rating` Sonar driver closed.**

- `.github/workflows/scorecard.yml` permissions narrowed
  (`read-all` → `contents: read`).
- Workflow `.gitlab-ci.yml` `changes:` allowlist widened to cover
  `bin/`, `.github/`, `.spectral.yaml`, localised READMEs, CLAUDE.md.
- 4 stable `allow_failure: true` shields dropped (sonar-analysis,
  code-quality, trivy:scan, dockle, release-please).
- `sonar-analysis` scoped to main only (free-tier PR-analysis limitation).

## [stable-v1.0.5] — 2026-04-21

**ServiceMonitor + GKE kube-prom-stack overlay + ADR-0037 Path B.**

- ServiceMonitor for Mirador in local-prom overlay.
- `gke-prom/` overlay: kube-prom-stack on GKE Autopilot
  (7 d retention, 10 Gi PVC, 6 ServiceMonitors, ADR-0039).
- `OpenApiCustomizer openApiSchemaSanitizer()` — clears 2
  `oas3-valid-*-example` Spectral errors (ADR-0037 Path B).

## [stable-v1.0.4] — 2026-04-21

**Pre-Phase-2 baseline.** Consolidated post-bootstrap state.

## [stable-v1.0.0] — [stable-v1.0.3] — 2026-04-20 / 21

**Initial stability series.** Successive hardening passes:

- Base feature slice set (customer CRUD, Auth, observability, diagnostic,
  chaos, security demos, etc.).
- Initial CI pipeline (lint, test, integration, k8s, package, deploy).
- First wave of `allow_failure: true` shields + documented exits
  (pre-ADR-0049 formalisation).
- Renovate + Dependabot wiring.
- ADR corpus up to ADR-0040 (most architectural decisions).

Rough granularity because these tags pre-date the session-level audit
docs. For detail: `git log --oneline stable-v1.0.0..stable-v1.0.4`.

---

## Unreleased

For the in-flight Phase B refactor targets, see [`TASKS.md`](TASKS.md).

---

[stable-v1.0.11]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.11
[stable-v1.0.10]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.10
[stable-v1.0.9]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.9
[stable-v1.0.8]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.8
[stable-v1.0.7]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.7
[stable-v1.0.6]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.6
[stable-v1.0.5]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.5
[stable-v1.0.4]: https://gitlab.com/mirador1/mirador-service/-/tags/stable-v1.0.4
