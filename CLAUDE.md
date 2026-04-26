# Mirador Service — Claude Instructions

## Git Safety

- NEVER `git reset --hard` without explicit user confirmation. Prior sessions wiped demo edits this way. Run `git status` + check unpushed commits first.
- Before pushing : verify current branch (`dev` not `main`) + `git fetch` + check `HEAD..origin/<branch>` ; pull rebase if behind. See "Git Workflow" section for the full 2-second preflight.

## CI/CD Scope

- **GitLab CI exclusively** — do NOT modify `.github/workflows/*` unless explicitly requested.
- When fixing a failing pipeline, read the **actual failure log** (`glab ci trace <job>`) before exploring the whole CI config. Theoretical config review misses the real bug.
- Never add comments to `.mvn/jvm.config` (Maven reads each line as a JVM flag, `#` breaks the build).
- Docker builds on Kaniko : avoid Maven recompilation in multi-stage builds (OOM). Pre-build jar in a `build-jar` stage, `COPY` into thin Dockerfile.

## Project Verification

- State explicitly at the start of each response : (1) which repo (mirror-service here), (2) current branch, (3) remote state (ahead/behind). Prevents wrong-branch rework.
- When resuming mid-session, `git fetch` + `glab mr list` + `glab ci list` before editing.

## Verify commands before suggesting

- Before suggesting any CLI flag, run `<cmd> --help | grep <flag>` to confirm it exists. If you can't verify, say **"I'm not sure this exists"** rather than guess. See ~/.claude/CLAUDE.md → "Verify commands before suggesting them" for the full rule (meta-questions about Claude Code itself get extra skepticism).

## CI failures : surgical fixes, not `allow_failure` bypasses

When a CI job fails, NEVER reach for `allow_failure: true` as the fix. Pick (a) fix the root cause, (b) tag-gate the test with JUnit `@Tag` + Maven profile so it only runs in the environment that makes sense, or (c) scope-out via `rules: when: never`. Always explain the chosen approach in the commit message. `allow_failure: true` is a SHORT-TERM bridge only, per ADR-0049 (dated-TODO required). See ~/.claude/CLAUDE.md → "Surgical fixes, not allow_failure bypasses" for the full rule.

## Persistent task backlog

**`TASKS.md`** (at the repo root) is the source of truth for pending work across sessions.
- **Read it at the start of every session** — before doing anything else.
- **Update it immediately** when a task is added, started, or completed.
- This file survives context window resets; the conversation history does not.
- **When all tasks are done**: delete `TASKS.md` and commit the deletion. Do not keep an empty file.
- **When new tasks arrive**: recreate `TASKS.md` from scratch and commit. The file either exists with real pending work, or it does not exist at all.
- **When adding a general rule** (workflow, style, architecture): also add it to `~/.claude/CLAUDE.md` so it applies globally across all projects.

## Claude workflow rules (apply to every session)

- **Start every response with the current time** in `HH:MM` format, no timezone suffix. Run `date "+%H:%M"` if uncertain — context-carried times can be stale after scheduled wakeups.
- **Do not stop** between tasks — chain all pending work without asking "shall I continue?".
- **Never go silent**: when no background work is in flight, say `⏸  Idle. No background work.` then re-list pending tasks and restart them. Same when polling — explicit "waiting for X, next check at Y", never silent. **Waiting on a pipeline counts as idle**: schedule a wakeup at most every 10 min so a green pipeline doesn't sit unmerged. See `~/.claude/CLAUDE.md` → "Never go silent" for the full rule.
- **Regularly display the pending task list** — after completing a task, show what remains so the user can track progress without opening TASKS.md.
- **Act directly** — read only what is strictly necessary, then make the change. No long exploration before acting.
- **One commit per logical change** — do not batch unrelated fixes into one commit.
- **Run the build after every change** (`./mvnw verify -q`) and fix errors before committing.
- **Comments explain why**, not what. Write comments that a future Claude session with no conversation history can understand.
- After significant feature work, **do a code review pass**: unused imports, broad catches, missing Javadoc, test gaps.
- **Never modify files outside this project** unless explicitly asked.
- **Reference pipelines/MRs/files as clickable URLs.** When a status update or commit message mentions an MR, pipeline, tag, ADR or audit report, emit it as a markdown link (`[!110](https://gitlab.com/mirador1/mirador-service/-/merge_requests/110)`, `[#564](https://gitlab.com/mirador1/mirador-service/-/pipelines/<id>)`, `[ADR-0037](file:///<repo>/docs/adr/0037-…md)`) so the user can open it in one click. Bare IDs (`!110`, `#564`) are fine in subsequent prose if a clickable form already appeared earlier in the same turn. See `~/.claude/CLAUDE.md` → "Reference pipelines, MRs and config files as clickable URLs" for the full pattern list.
- **Architectural decisions get an ADR.** Anything that locks in a pattern — new tool, replaced library, contract change — goes in `docs/adr/NNNN-*.md` using the Michael Nygard format. Code style / bug fixes / patch bumps do NOT get an ADR (see `docs/adr/README.md` for the criteria). This prevents the same decisions being relitigated in every new session.
- **Conventional Commits are mandatory.** Every commit message must start with one of `feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert`, optionally followed by a scope in parens, then `: subject` in lowercase. Enforced by `.config/lefthook.yml` commit-msg hook + `config/commitlint.config.mjs`. Enables automatic CHANGELOG + semver bump on main via release-please.
- **Versions-freshness pass — weekly, or at the start of any session that touches dependencies.** Renovate (`renovate.json`) runs the automated weekly sweep and opens MRs. If you add a new dependency manually, check Maven Central / npm for the latest stable BEFORE pinning — don't paste an old version. For properties already in `pom.xml`, `npm outdated` / `mvn versions:display-property-updates` gives the current lag. Security-sensitive libs (`@auth0/*`, `sonar-scanner`, `findsecbugs-plugin`, `dependency-check-maven`, Spring AI) are always worth checking manually. Archived/deprecated packages must be replaced **the same session they're discovered**.
- **Budget watch — at session start, and after any `bin/cluster/demo/up.sh` / live cluster action.** Run `bin/budget/budget.sh status` (current cap + thresholds) and `bin/budget/gcp-cost-audit.sh` (structural idle-cost scan — orphaned PVCs, reserved IPs, NAT, LBs, snapshots). The ephemeral pattern (ADR-0022) targets ≤€2/month idle; drift is the single biggest project-health risk. `bin/budget/gcp-cost-audit.sh --yes` is cron-safe for unattended monthly cleanup. Full doc: `docs/ops/cost-control.md`.

## Submodule pattern (2-tier flat α — see common ADR-0060)

This repo has **2 git submodules** (since 2026-04-26 split) :

- `infra/common/` → [mirador-common](https://gitlab.com/mirador1/mirador-common) — universal cross-repo conventions (release scripts, ADR drift tooling, Conventional Commits CI template, Renovate base). Consumed by all 4 mirador1 repos including UI.
- `infra/shared/` → [mirador-service-shared](https://gitlab.com/mirador1/mirador-service-shared) — backend infrastructure (clusters, terraform, K8s, OTel collector, postgres+kafka+redis dev stack, observability dashboards, backend ADRs). Consumed by java + python only (NOT ui).

**Pattern α (flat 2-submodule)** chosen over β (transitive nested) for : independent SHA pinning per consumer (java can pin `common@SHA-X` while python pins `common@SHA-Y`), symmetric path everywhere (`infra/common/bin/...` works in all 4 repos), standard clone (`git submodule update --init`, no `--recursive` needed). Full rationale : [common ADR-0060](https://gitlab.com/mirador1/mirador-common/-/blob/main/docs/adr/0060-flat-vs-transitive-submodule-inheritance.md).

**Where to find what** :
- Universal scripts (pre-sync, changelog, gitlab-release, regen-adr-index) → `infra/common/bin/...`
- Backend infra scripts (cluster lifecycle, budget, runner-healthcheck) → `infra/shared/bin/...`
- Backend deploy manifests (K8s, terraform) → `infra/shared/deploy/...`
- Backend dev stack (compose Postgres+Kafka+Redis+LGTM) → `infra/shared/compose/dev-stack.yml`
- Cross-cutting backend ADRs (observability, SLO, ESO, multi-cloud) → `infra/shared/docs/adr/`
- Universal cross-repo ADRs (submodule pattern, release engineering, Renovate) → `infra/common/docs/adr/`

**Bumping submodule SHAs** : each is independent. To bump common only, `cd infra/common && git pull origin main && cd ../.. && git add infra/common && git commit`. Same for shared.

**Tag prefix for this repo** : `stable-v` (default ; per [common ADR-0061](https://gitlab.com/mirador1/mirador-common/-/blob/main/docs/adr/0061-per-repo-tag-namespace-pattern.md)). Run release scripts as : `infra/common/bin/ship/changelog.sh` (no `--tag-prefix` flag needed).

## Project overview

Spring Boot 4 + Java 25 backend API with full observability stack.

- **Main package:** `com.mirador`
- **Entry point:** `src/main/java/com/mirador/MiradorApplication.java`
- **Tests:** `src/test/java/com/mirador/`
- **Migrations:** `src/main/resources/db/migration/` (Flyway, V1–V7)
- **Config:** `src/main/resources/application.yml`

## Build — Maven profiles

| Command | Stack |
|---|---|
| `./mvnw verify` | SB4 + Java 25 (default) |
| `./mvnw verify -Dcompat` | SB4 + Java 21 |
| `./mvnw verify -Dcompat -Djava17` | SB4 + Java 17 |
| `./mvnw verify -Dsb3` | SB3 + Java 21 |
| `./mvnw verify -Dsb3 -Djava17` | SB3 + Java 17 |

Always run the default `./mvnw verify` after any change unless testing a specific compatibility profile. Do not ask for permission to run Maven.

## Git workflow

- **Always run this BEFORE making changes**:
  ```
  git fetch --all
  git log --oneline origin/dev..HEAD    # commits ahead
  git log --oneline HEAD..origin/dev    # commits behind — if non-empty, git pull --rebase
  git status
  ```
- Branch: `dev`. One commit per logical change.
- Push with `git push origin dev`.
- If an MR exists: `glab mr merge <id> --auto-merge --squash=false --remove-source-branch=false`.
  **Always pass `--remove-source-branch=false`** — GitLab deletes the source branch by default,
  which would destroy `dev`. The `dev` branch must never be deleted.
- Never push directly to `main`.
- Resolve merge conflicts by `git pull --rebase`, never by force-push.
- **Tag stable-vX.Y.Z ONLY after the post-merge `main` pipeline goes green.** Don't tag right after the merge while main pipeline is still running with a "I'll move the tag if it goes red" recovery plan — that pattern silently produces tags on red commits when the recovery is forgotten or interrupted. The MR pipeline succeeding is NOT enough; the post-merge main pipeline runs the full main-branch ruleset (deploy steps, scheduled-only jobs) which can fail even when the MR pipeline passed. See `~/.claude/CLAUDE.md` → "Tag every green stability checkpoint, never tag on red" for the operational pattern (Monitor on the post-merge main pipeline).
- **Surface pending decisions on your own initiative** — when a session accumulates real forks-in-the-road (choice changes WHAT gets built, not just WHEN), list them at the next natural checkpoint (batch landed, MR merged, idle moment). Don't wait for the user to ask "quoi à décider ?". Format: dense one-liner per decision with concrete trade-off; the user should answer "A1, B2, C: skip" in one line. See `~/.claude/CLAUDE.md` → "Surface pending decisions proactively — don't wait to be asked" for the full pattern.
- **Vulgariser le jargon avec une parenthèse** — every technical term that appears in a status message or decision write-up (SIGPIPE, Server-Side Apply, annotated-tag SHA, ReDoS, CRD Establishment, StatefulSet, Autopilot admission, etc) gets a short plain-language gloss in parens on first mention per turn. Format: `<term> (<what it means in this context>)`. Keep the term (it's precise), just ADD the gloss. Skip for second+ mentions in the same turn, when the user explicitly asks for the technical deep-dive, or when echoing the user's own phrasing. See `~/.claude/CLAUDE.md` → "Write in plain language — jargon gets a parenthetical".
- **Réduire les vagues CI** — quand plusieurs changements indépendants sont en cours, les **batcher en UN commit-set sur dev → UN push → UNE MR → UN cycle CI** plutôt que d'enchaîner N MRs séquentielles (économie : (N-1) × 10-15 min de wall-time par CI cycle évité). Le build local (`mvn verify` ou `npm run build`) catch déjà 80 % des fails AVANT push ; les fails CI restants sont des patterns reconnaissables. Si un fail apparaît sur la batch, on identifie le coupable parmi les N commits en quelques minutes — beaucoup moins coûteux que d'attendre N CIs séquentielles. Anti-pattern : "1 micro-change = 1 MR = 1 CI" appliqué mécaniquement. Limite : sortir en MR seul un changement vraiment risqué (premier push image runner, refonte job critique). See `~/.claude/CLAUDE.md` → "Réduire les vagues CI — batch the changes per MR" pour la justification complète.

## CI workflow rules

The pipeline runs ONLY when code/build/infra files change (see `workflow:rules` in `.gitlab-ci.yml`).
Pure documentation commits (`**/*.md`, `docs/**`) do NOT trigger a pipeline — this is intentional.
If a doc change really needs CI validation, use GitLab UI → "Run pipeline" on the branch.

Expensive jobs (`docker-build`, `terraform-apply`, `deploy:gke`) have `interruptible: false` —
they survive a new push mid-run. Don't remove this flag without understanding the cost.

## GCP Production Environment

| Resource | Value |
|---|---|
| Project ID | `project-8d6ea68c-33ac-412b-8aa` |
| Project display name | `Mirador` |
| Project number | `32654862595` |
| GKE cluster | `mirador-prod` (europe-west1) |
| Ingress IP | `34.52.233.183` |
| Domain | `mirador1.duckdns.org` (DuckDNS free — A record → 34.52.233.183) |
| App URL | https://mirador1.duckdns.org (HTTP until cert-manager wired) |
| GCP Console | https://console.cloud.google.com/home/dashboard?project=project-8d6ea68c-33ac-412b-8aa |
| WIF Provider | `projects/32654862595/locations/global/workloadIdentityPools/gitlab-pool/providers/gitlab-provider` |
| CI Service Account | `gitlab-ci-deployer@project-8d6ea68c-33ac-412b-8aa.iam.gserviceaccount.com` |

## Managed Services (Production)

Keycloak and LGTM are replaced by managed services in production — no self-hosted auth or observability stack to maintain.

| Service | Provider | Free tier | Notes |
|---|---|---|---|
| OAuth2/OIDC | Auth0 | 7 500 MAU | Replaces Keycloak :8888; same Spring Security config, only issuer URI changes |
| Traces | Grafana Cloud (Tempo) | 50 GB/month | OTLP/HTTP push from Spring Boot via `OTEL_EXPORTER_OTLP_ENDPOINT` |
| Metrics | Grafana Cloud (Mimir) | 10 k active series | Prometheus remote_write |
| Logs | Grafana Cloud (Loki) | 50 GB/month | OTLP/HTTP push |
| Dashboards | Grafana Cloud | Free | Same dashboards as local Grafana |

**GitLab CI variables to set:**
- `GRAFANA_OTLP_ENDPOINT` — e.g. `https://otlp-gateway-prod-eu-west-0.grafana.net/otlp`
- `GRAFANA_OTLP_TOKEN` — base64(`instanceId:apiKey`)
- `AUTH0_DOMAIN` — e.g. `mirador.eu.auth0.com`
- `AUTH0_CLIENT_ID` / `AUTH0_CLIENT_SECRET` / `AUTH0_AUDIENCE`

**Grafana Cloud region**: EU (Germany / Frankfurt) — closest to GKE europe-west1 (Belgium).

**Auth0 tenant region**: EU — create tenant with region `EU` at signup to keep data in Europe.

## Port map (local Docker)

| Port | Service | Notes |
|------|---------|-------|
| 8080 | Spring Boot API | |
| 8084 | Maven Site | nginx serving `target/site/` |
| **8085** | **gcloud auth login** | **Reserved — OAuth callback for Google Cloud CLI. Do NOT assign any service to this port.** |
| 8086 | Compodoc | Angular API docs (moved from 8085) |
| 9000 | SonarQube | |
| 8888 | Keycloak | Migrated 2026-04-26 from 9090 (elgato-mcp host conflict). Realm `mirador-service`, admin/admin |

## Known gotchas

- **Port 8085 reserved for gcloud** — `gcloud auth login` uses `localhost:8085` as its default OAuth redirect. Assigning any service to this port will intercept the OAuth flow and block Google Cloud CLI authentication. Compodoc was moved to 8086 for this reason.
- **`/mvn/jvm.config`** — never add comments (`#`), they break Maven.
- **Flyway** — migration versions must be unique. Never add `V_N` if `V_N` already exists.
- **Spring AI** — version pinned at `1.0.0-M6`. DO NOT upgrade to `1.0.0` GA: the Ollama starter was renamed (`spring-ai-ollama-spring-boot-starter` → `spring-ai-starter-model-ollama`) and the pom would need manual migration. See the comment in `pom.xml`.
- **Page serialisation** — `Page<T>` is returned as-is (flat JSON: `totalElements`, `totalPages` at root). Do NOT add `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` — it changes the JSON shape to nested (`page.totalElements`) which breaks both integration tests and the Angular `Page<T>` interface in `api.service.ts`. The warning is suppressed via `logback-spring.xml`.
- **SB3 overlay files** — files under `src/main/java-sb3-compat/` and `src/test/java-sb3-compat/` are compiled only when `-Dsb3` is active. Do not cover them in tests for the default (SB4) profile.
- **MCP server (ADR-0062)** — the backend ships its own MCP (Model Context Protocol) server via `spring-ai-starter-mcp-server-webmvc`. 14 tools wired in `com.mirador.mcp.McpConfig` (4 domain services + 4 backend-local observability services). NO env vars to set externally — the constraint is the opposite of "needs config" : the jar must run identically with NO infra around it. NO Loki / Mimir / Grafana / GitLab / GitHub HTTP clients allowed in this repo (those are SEPARATE community MCP servers added per-developer via `claude mcp add`). When adding a new tool : annotate the @Service method with `@Tool(name="…", description="…")`, register the service in `McpConfig.miradorToolProvider(...)`, write a unit test in `com.mirador.mcp.<slice>.<>Test`, update the table in README.md "AI integration via MCP" section.

## Security architecture

Request lifecycle (filter order matters — do not reorder without understanding the impact):

```
Request
  → RateLimitingFilter         (Bucket4j, 100 req/min per IP)
  → RequestIdFilter            (generates X-Request-Id, uses ScopedValue for virtual threads)
  → IdempotencyFilter          (LRU cache, 10k entries, POST/PATCH only)
  → SecurityHeadersFilter      (CSP, HSTS, X-Frame-Options, etc.)
  → JwtAuthenticationFilter    (validates JWT, sets SecurityContext)
  → ApiKeyAuthenticationFilter (fallback for Prometheus scraper and admin tools)
  → Spring Security chain
```

## File length hygiene (segmenter les fichiers trop longs)

When a hand-written source file crosses **~1 000 lines**, plan a split at
the next touch; at **1 500+**, split NOW before shipping any other change.
**Plancher : 500 LOC.** Décidé 2026-04-22 — un fichier sous 500 LOC
ne se redécoupe PAS, même si certains standards stricts (MISRA aérospatial,
banking interne) suggèrent 300-500. Sur-fragmenter un fichier cohérent de
480 LOC en 8 fichiers de 60 LOC noie le concept et casse git blame sans
gain de lisibilité. Voir `~/.claude/CLAUDE.md` → "File length hygiene"
pour la justification complète.

Current offenders to address over upcoming sessions:

- `.gitlab-ci.yml` (2 619 lines) — modularise into `ci/includes/*.yml`
  (lint, test, security, k8s, quality, package, native, deploy, release).
- `src/main/java/com/mirador/observability/QualityReportEndpoint.java`
  (1 934) — 7 parsers (Surefire, Jacoco, SpotBugs, OWASP, PMD, Checkstyle,
  Pitest) each in their own class; the endpoint becomes a thin aggregator.
- `bin/dev/stability-check.sh` (1 362) — split into
  `bin/dev/stability/sections/*.sh` + a driver.

Exceptions (length is inherent — don't split): `pom.xml` (Maven monorepo
constraints), `README.md`, `docs/reference/*.md`, auto-generated manifests
(`kube-prom-stack-rendered.yaml`, etc.).

How to split — one commit per responsibility move, keep the public
entrypoint small (~150 lines), grep-friendly names (`CustomerReadController`
not `ReadController`), ADR if the dependency graph changes.

Subdirectory side of the same rule: when a flat folder crosses **10
entries**, group by purpose (bin/, features/, api/, port/, etc.); **15**
is the hard ceiling. See `~/.claude/CLAUDE.md` → "Subdirectory hygiene".

## Test conventions

- Unit tests mock collaborators via Mockito; use `MockHttpServletRequest/Response` for filter tests.
- Integration tests (`*ITest.java`) use `@SpringBootTest` with Testcontainers or `@DataJpaTest`.
- Do NOT use `@MockBean` in integration tests where the real bean is available.
- JaCoCo merged report: unit (`jacoco.exec`) + IT (`jacoco-it.exec`) → `jacoco-merged.exec`. Current minimum: **70 %**.
- Excluded from coverage (infra wiring only, no business logic): `SecurityConfig`, `KeycloakConfig`, `KafkaConfig`, `WebSocketConfig`, `OpenApiConfig`, `ObservabilityConfig`, `QualityReportEndpoint`, `OtelLogbackInstaller`, `PyroscopeConfig`, `MiradorApplication`.

## Observability

- Distributed traces: OpenTelemetry → Tempo (LGTM container on port 3001).
- Metrics: Micrometer → Prometheus → Grafana.
- Logs: Logback → OTel log exporter → Loki. Trace ID is injected in every log line.
- Custom Micrometer meters registered in `CustomerController` constructor (pre-registered so Grafana shows data from first scrape, not first request).

## CVE status (last checked 2026-04-14)

| Dependency | Version | Remaining CVEs | Notes |
|---|---|---|---|
| Spring AI | 1.0.0-M6 | 5 (incl. 2 HIGH) | Cannot upgrade — artifact renamed in GA |
| protobuf-java | 4.34.1 | 1 (CVE-2026-0994) | NVD has no patched version yet |
| OTel SDK | latest BOM | several | Transitive, no override available |

Full details in `pom.xml` property comments and the cached report at `src/main/resources/META-INF/build-reports/dependency-check-report.json`.

## Docker / infrastructure

Two compose files — never merge them:
- `docker-compose.yml` — infra (PostgreSQL, Kafka, Redis, Ollama, Keycloak, admin tools)
- `deploy/compose/observability.yml` — observability (LGTM stack — Grafana, Prometheus, Tempo, Loki, Mimir + Pyroscope)

Loki, Tempo, Grafana are already inside the LGTM container — do NOT add them as separate services.

## Clean Code + Clean Architecture — binding constraints

Hard constraints, not aspirations — same 7 non-negotiables as
`~/.claude/CLAUDE.md` → "Clean Code + Clean Architecture":

1. Function size ≤ 20-30 LOC body, ≤ 5 params, complexity ≤ 10
   (ESLint + PMD enforce; Phase C flips to error).
2. Single Responsibility per class / service / endpoint.
3. Naming tells intent — rename the moment a mismatch is noticed
   (see 2026-04-22 `authenticateKeycloak` → `authenticateExternalJwt`).
4. Comments explain WHY, not WHAT (existing Comments rule).
5. Dependency rule — domain has zero framework imports.
   Feature-slicing (ADR-0008) + Hexagonal Lite (ADR-0044) are
   this repo's pattern. `port/` only when cross-feature coupling
   emerges.
6. Test-as-spec — coverage drop on a touched file = ship a test
   in the same commit.
7. No dead code — unused imports, silent catches, TODO > 30 d all
   count as warnings to clear before tagging a green checkpoint.

**Current-state baseline**:
[`docs/audit/clean-code-architecture-2026-04-22.md`](docs/audit/clean-code-architecture-2026-04-22.md)
(80 % Clean Code / 70 % Clean Arch). Every new commit must not
regress the ✅ / 🟢 items; open 🟡 items (QualityReportEndpoint
split, AuditEventPort extraction, ADR-0051) should chip away when
possible. Re-audit every 3-6 months.

## Code review checklist (run proactively after significant changes)

- [ ] Unused Java imports
- [ ] `@SuppressWarnings` — justified and minimal scope?
- [ ] New Flyway migration — unique version number?
- [ ] New timer/counter/gauge — pre-registered in constructor, not lazily?
- [ ] Exception handlers — nothing silently swallowed (empty `catch` blocks)?
- [ ] Javadoc on public methods with non-obvious parameters or return values
- [ ] **Clean Code 7 non-negotiables**: function size, SRP, naming,
      why-not-what comments, dependency rule, test-as-spec, no dead
      code. See the section above; the audit at
      `docs/audit/clean-code-architecture-*.md` is the current baseline.
- [ ] **Root hygiene**: no new file added to repo root that belongs under
      `config/`, `build/`, `deploy/compose/`, `docs/`, or `ci/`. See
      ~/.claude/CLAUDE.md → "Root file hygiene" for the authoritative list.
- [ ] **File length hygiene**: no hand-written file > 1 000 lines without
      a split plan (config/docs/auto-generated exempt). See
      ~/.claude/CLAUDE.md → "File length hygiene" + this repo's
      `File length hygiene` section above for current targets.
- [ ] **Pipelines green**: `glab ci list` on `main` shows `success`
      for the last run. Any failed job (even `allow_failure: true`)
      counts as a task. Warnings (bundle budget, deprecations,
      `allow_failure` shields) are fix-now unless carried by a dated
      follow-up. See ~/.claude/CLAUDE.md → "Pipelines stay green".

## Docker Cleanup — TIGHTENED CADENCE (2026-04-21)

Run the prune trio at **each** of these moments, not just "start of session":

1. **Session start** — baseline.
2. **After any CI pipeline failure carrying a runner-pressure signal**
   (connection refused to kind control-plane, OOMKilled, "resource quota
   evaluation timed out", vitest worker exit 137, Maven SIGKILL in ≤ 60 s).
   Rerun or repush WITHOUT cleanup → dies the same way.
3. **Every 30 min of active local CI work** — catches leaks silently.
4. **Before calling a session done** — clean slate for next session.

### Leak classes specific to this repo

- **Orphaned CI kind clusters** — `docker ps --format '{{.Names}}' |
  grep -E '^ci-k8s(-prom)?-'` must return NOTHING outside an in-progress
  job. Zombies outlive their CI job when jobs are cancelled mid-run —
  kill with:
  ```bash
  docker ps --format '{{.Names}}' | grep -E '^ci-k8s(-prom)?-' \
    | xargs -I {} kind delete cluster --name "$(echo {} | sed 's/-control-plane$//')"
  ```
  Session 2026-04-21 accumulated **4 stale clusters over 3 hours** before
  detection; each was ~700 MB RSS × 4 = blown past the Docker Desktop VM
  7.6 GB cap → cascading kind OOM on fresh pipelines.

- **Local long-running JVMs** — `ps auxm | head -20` spots
  `./mvnw spring-boot:run` or `java -jar target/*-SNAPSHOT.jar` left
  from earlier dev work. ~250-800 MB RSS each; kill if not actively
  needed.

- **Dev demo containers** — `postgres-demo`, `kafka-demo`, `redis-demo`,
  `ollama-demo` etc. started by `./run.sh all`. Stop if not needed:
  `docker stop postgres-demo kafka-demo redis-demo ollama-demo`.

### Prune trio + escalation

```bash
docker system df                                     # check first
docker container prune -f                            # stopped containers
docker builder prune -f                              # build cache
docker image prune -f                                # dangling images
# If > 80 GB total OR images > 100 count:
docker image prune -a -f                             # ALL unused images (~20-30 GB typical)
```

**Never** prune named volumes (`docker volume prune`) without user
confirmation — they hold postgres / sonar / flyway state.

See `~/.claude/CLAUDE.md` → "Clean Docker regularly — don't wait for
OOM" for the canonical rule.
