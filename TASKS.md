# TASKS — pending work backlog

Source of truth across Claude sessions. Read this first. Update when
adding/starting/finishing a task. Delete when empty (per CLAUDE.md).

## 🔴 Real bugs (fix-now-ish)

### `compat-*` profiles run 0 unit tests on CI

Pipeline #500 (and previous main runs of any compat-* job) report:

```
[INFO] --- surefire:3.5.5:test (default-test) @ mirador ---
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
[INFO] Skipping JaCoCo execution due to missing execution data file.
[ERROR] Failed to execute goal jacoco-maven-plugin:check ... instructions covered ratio is 0.00, but expected minimum is 0.70
```

Compile reports show 85 test sources successfully compiled, but Surefire
discovers ZERO of them. Root cause unknown — investigated 2026-04-20:

- compat profile DOES set `compileSourceRoots` for `maven-compiler-plugin`
  (main → merged-sources/main, target/classes is correct)
- `<excludedGroups>` is on failsafe only, not surefire
- 24 `*Test.java` (excluding `*ITest.java`) exist
- MAVEN_CLI_OPTS contains no test filter
- Same code passes `mvn verify` (default profile, Java 25) on the main pipeline

Hypotheses to test next session:
1. JUnit 5 vs JUnit 4 dependency: junit-vintage-engine missing from compat
   classpath?
2. Spring Boot 3 / SB4 BOM clash with JUnit Platform discovery?
3. `compileSourceRoots` override breaks `testCompileSourceRoots` path?
4. Surefire test classes directory not aligned with `target/test-classes`?

Repro: `mvn $MAVEN_CLI_OPTS verify -Dcompat` in maven:3.9.14-eclipse-temurin-21-noble.

Until fixed: the 4 compat-* jobs stay 🟡 in stability-check reports.

### UI: 15 feature components have 0 unit tests

Coverage gap surfaced by 2026-04-20 audit. Components:

```
src/app/features/{customers,settings,database,maven-site,quality,security,
                  activity,diagnostic,pipelines,chaos,observability,
                  request-builder,about,login}/
```

Plan: write thin smoke specs (component creation + signal init) for each,
~10 lines per component. Not blocking pipelines today (vitest only runs
existing specs) but blocks any future coverage gate.

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
