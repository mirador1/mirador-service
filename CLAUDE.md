# Mirador Service — Claude Instructions

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
- **Regularly display the pending task list** — after completing a task, show what remains so the user can track progress without opening TASKS.md.
- **Act directly** — read only what is strictly necessary, then make the change. No long exploration before acting.
- **One commit per logical change** — do not batch unrelated fixes into one commit.
- **Run the build after every change** (`./mvnw verify -q`) and fix errors before committing.
- **Comments explain why**, not what. Write comments that a future Claude session with no conversation history can understand.
- After significant feature work, **do a code review pass**: unused imports, broad catches, missing Javadoc, test gaps.
- **Never modify files outside this project** unless explicitly asked.
- **Architectural decisions get an ADR.** Anything that locks in a pattern — new tool, replaced library, contract change — goes in `docs/adr/NNNN-*.md` using the Michael Nygard format. Code style / bug fixes / patch bumps do NOT get an ADR (see `docs/adr/README.md` for the criteria). This prevents the same decisions being relitigated in every new session.
- **Conventional Commits are mandatory.** Every commit message must start with one of `feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert`, optionally followed by a scope in parens, then `: subject` in lowercase. Enforced by `lefthook.yml` commit-msg hook + `commitlint.config.mjs`. Enables automatic CHANGELOG + semver bump on main via release-please.
- **Versions-freshness pass — weekly, or at the start of any session that touches dependencies.** Renovate (`renovate.json`) runs the automated weekly sweep and opens MRs. If you add a new dependency manually, check Maven Central / npm for the latest stable BEFORE pinning — don't paste an old version. For properties already in `pom.xml`, `npm outdated` / `mvn versions:display-property-updates` gives the current lag. Security-sensitive libs (`@auth0/*`, `sonar-scanner`, `findsecbugs-plugin`, `dependency-check-maven`, Spring AI) are always worth checking manually. Archived/deprecated packages must be replaced **the same session they're discovered**.
- **Budget watch — at session start, and after any `bin/demo-up.sh` / live cluster action.** Run `bin/budget.sh status` (current cap + thresholds) and `bin/gcp-cost-audit.sh` (structural idle-cost scan — orphaned PVCs, reserved IPs, NAT, LBs, snapshots). The ephemeral pattern (ADR-0022) targets ≤€2/month idle; drift is the single biggest project-health risk. `bin/gcp-cost-audit.sh --yes` is cron-safe for unattended monthly cleanup. Full doc: `docs/ops/cost-control.md`.

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
| OAuth2/OIDC | Auth0 | 7 500 MAU | Replaces Keycloak :9090; same Spring Security config, only issuer URI changes |
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
| 9090 | Keycloak | |

## Known gotchas

- **Port 8085 reserved for gcloud** — `gcloud auth login` uses `localhost:8085` as its default OAuth redirect. Assigning any service to this port will intercept the OAuth flow and block Google Cloud CLI authentication. Compodoc was moved to 8086 for this reason.
- **`/mvn/jvm.config`** — never add comments (`#`), they break Maven.
- **Flyway** — migration versions must be unique. Never add `V_N` if `V_N` already exists.
- **Spring AI** — version pinned at `1.0.0-M6`. DO NOT upgrade to `1.0.0` GA: the Ollama starter was renamed (`spring-ai-ollama-spring-boot-starter` → `spring-ai-starter-model-ollama`) and the pom would need manual migration. See the comment in `pom.xml`.
- **Page serialisation** — `Page<T>` is returned as-is (flat JSON: `totalElements`, `totalPages` at root). Do NOT add `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` — it changes the JSON shape to nested (`page.totalElements`) which breaks both integration tests and the Angular `Page<T>` interface in `api.service.ts`. The warning is suppressed via `logback-spring.xml`.
- **SB3 overlay files** — files under `src/main/java-sb3-compat/` and `src/test/java-sb3-compat/` are compiled only when `-Dsb3` is active. Do not cover them in tests for the default (SB4) profile.

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
- `docker-compose.observability.yml` — observability (Grafana, Prometheus, Tempo, Loki, Zipkin, Pyroscope)

Loki, Tempo, Grafana are already inside the LGTM container — do NOT add them as separate services.

## Code review checklist (run proactively after significant changes)

- [ ] Unused Java imports
- [ ] `@SuppressWarnings` — justified and minimal scope?
- [ ] New Flyway migration — unique version number?
- [ ] New timer/counter/gauge — pre-registered in constructor, not lazily?
- [ ] Exception handlers — nothing silently swallowed (empty `catch` blocks)?
- [ ] Javadoc on public methods with non-obvious parameters or return values

## Docker Cleanup

At the start of each session (or after heavy build/test work), run:
```
docker container prune -f
docker volume prune -f
docker builder prune -f
```
Check disk usage first with `docker system df`. Never prune running containers or named volumes without confirming with the user.
