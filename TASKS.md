# TASKS — pending work backlog

Source of truth across Claude sessions. Read this first. Update when
adding/starting/finishing a task. Delete when empty (per CLAUDE.md).

## ✅ Closed this session (2026-04-20)

- UI 15 feature smoke specs (UI !58, commit 088ec90 → squash on main)
- compat-* profiles surefire discovery (svc !98, commit 029dead — root
  cause: plugin-level compileSourceRoots leaked to testCompile)
- smoke-test CI infra (svc !98, commit ebbdb96 — swap k6 image for
  maven + docker CLI, spin compose + spring-boot:run, target
  app@localhost:8080)

## 🟡 Improvements

### Re-enable `oas3-valid-schema-example` Spectral rule

Currently OFF in `.spectral.yaml` because springdoc auto-generates 24
type-mismatched defaults (id: 0 in non-integer schemas, etc.). Two fix
paths documented in the file. Revisit on next springdoc version bump.

### Reduce `allow_failure: true` shields

Inventory from stability-check: svc has 30, UI has 15. Each one needs
a dated exit ticket per CLAUDE.md "Pipelines stay green". Target:
remove or date 5 per session.

### Write integration tests for OAuth2 / Auth0 flow

`auth/JwtAuthenticationFilter.java` and `auth/KeycloakConfig.java`
reference Auth0 (production path) but `src/test/**/Auth0*` is empty.

### Compose-profiles cleanup

`docker-compose.yml` profile labels added in earlier MR but some
nice-to-have services still default-start. Audit + tag.

## 🟢 Nice-to-have

### Extend `bin/dev/stability-check.sh`

Each new section adds ~10 lines. Backlog of ideas:
- ADR "proposed" status check (none today, but watch)
- Lighthouse score regression vs baseline (`docs/audit/lighthouse.html`)
- Mermaid diagram syntax check (escape pitfalls re-occur)
- Trivy CVE delta vs last report (only flag NEW)
- TODO/FIXME age scanner (`>30d`)
- Helm chart lint when `deploy/helm/**` exists

### Group `src/app/features/` into one level of subdirs (UI)

Per CLAUDE.md "Subdirectory hygiene", 15 features ≥ 10 threshold.
Suggested grouping: `customer/`, `observability/`, `ops/`, `auth/`.

### Move root-level files to `config/` (UI + svc)

Both repos over the 15-file root budget. Listed in last stability
check report.
