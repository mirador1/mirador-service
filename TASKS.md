# TASKS — pending work backlog

Source of truth across Claude sessions. Read this first. Update when
adding/starting/finishing a task. Delete when empty (per CLAUDE.md).

## ✅ Closed this session

### 2026-04-21 (current — checkpoint stable-v1.0.5)

- ServiceMonitor for Mirador in local-prom overlay (svc !108, 1b9acbd)
- gke-prom/ overlay: kube-prom-stack on GKE Autopilot (svc !109,
  dc79814 + b87800a + 8a5292f) — 7d retention, 10Gi PVC on standard-rwo,
  1.5Gi mem, 6 ServiceMonitors, ADR-0039 GKE section
- ADR-0037 Path B: `OpenApiCustomizer openApiSchemaSanitizer()` (svc
  !110, bf69492 + c50e12a + 95452fc) — strips MissingNode/NullNode
  defaults, drops empty-string defaults on non-string types, normalises
  parameter examples to schema type. 13 unit tests. Spectral errors
  24→0 on `oas3-valid-{schema,media}-example`. Both rules re-enabled
  in `.spectral.yaml`.
- bearerAuth `oas3-schema` "unevaluated properties: name" error fixed
  (root cause: `.name(securitySchemeName)` setter is only valid on
  `apiKey` schemes, not `http`). `openapi-lint` job
  `allow_failure: true` shield removed — pipeline now goes red on any
  Spectral error.

### 2026-04-20

- UI 15 feature smoke specs (UI !58, commit 088ec90 → squash on main)
- compat-* profiles surefire discovery (svc !98, commit 029dead — root
  cause: plugin-level compileSourceRoots leaked to testCompile)
- smoke-test CI infra (svc !98, commit ebbdb96 — swap k6 image for
  maven + docker CLI, spin compose + spring-boot:run, target
  app@localhost:8080)
- compose-profiles cleanup (svc, commit d80db1f — split full into
  full/admin/docs, tag observability stack, README+FR updated)
- Auth0 JWT validation ITest (svc, commit 60a5969 — 4 scenarios:
  happy path + expired + wrong issuer + wrong audience, uses
  in-process HttpServer + RSA keypair, no WireMock / Testcontainers)

## 🟡 Improvements

### SonarCloud Quality Gate — remaining drivers after 2026-04-20-2315 fix

The session 2026-04-20-2315 merged `fix(sonar): 4 BLOCKER
missing-assertion + 1 CRITICAL complexity` (svc MR !104, commit
16ca5d9 → squash on main) and the symmetric UI fix (UI !62, commit
9287def). That clears **all** open BLOCKER/CRITICAL issues on both
projects. Gate ERROR persists because of:

- **svc `new_security_rating = 3`** — 1 MAJOR `githubactions:S8234`
  on `.github/workflows/scorecard.yml:28` (`permissions: read-all`).
  Fix: narrow to the specific scopes OSSF scorecard needs
  (`contents: read`, `id-token: write`, probably `pull-requests: read`).
- **svc `new_coverage = 47.3%`** < 80% — new code added this period
  without matching tests. Hotspots: `CustomerController` (classify
  helper + enrich path), and the authz paths added in the Auth0 JWT
  validation ITest session.
- **svc `new_security_hotspots_reviewed = 0%`** — hotspots on new code
  need the Sonar "Review" click (mark as "safe" with justification).
- **UI `new_coverage = 0%`** + **UI `new_security_hotspots_reviewed = 0%`**
  — same shape.

Each is an independent task; keep one MR per driver.

### UI npm audit — 5 CVEs in @compodoc/compodoc (1 HIGH picomatch)

Dev-only dependency (Angular API doc generator, not shipped to browser).
Fix requires breaking upgrade to `@compodoc/compodoc@1.1.16`. Low
real-world impact — log for next session to decide whether to pin +
upgrade, or drop compodoc if it's no longer valuable. Check again when
next major compodoc release lands.

### Reduce `allow_failure: true` shields

Inventory from stability-check: svc had 30, UI had 15. Session
2026-04-20 removed 4 stable shields and dated 1 flaky one:
- svc `owasp-dependency-check` → REMOVED (4x success, "initially"
  clause satisfied, genuine CVEs should now go red)
- svc `cosign:sign` → REMOVED (4x success, deploy-side `cosign:verify`
  already strict; signing failures are supply-chain breakage)
- svc `openapi-lint` → DATED, flip to false by 2026-05-20
- UI `bundle-size-check` → REMOVED (script never exits non-zero,
  shield was redundant)
- UI `typedoc` → REMOVED (`| tee` already masks typedoc's exit code,
  shield was redundant)

Remaining: svc ~28, UI ~13. Next sessions: continue 5/session.

#### Spectral warnings cleanup (was: openapi-lint shield flip — DONE 2026-04-21)

The `openapi-lint` job is now `allow_failure: false` (default) — any
`--fail-severity error` finding goes red. 6 warnings remain that don't
trip the gate but are real API-contract polishing:

- `operation-description` missing on `/customers/{id}.get`,
  `/scheduled/jobs.get`, `/customers/summary.get` — add
  `@Operation(description=...)` on the controllers.
- `operation-tag-defined` on `/scheduled/jobs.get.tags[0]` —
  either add the tag to `GroupedOpenApi.tags()` or drop the
  controller-level `@Tag`.
- 2× `no-script-tags-in-markdown` on `/demo/security/xss-*` —
  these are literal XSS demo endpoints; either `@Hidden` them in
  prod OpenAPI groups or escape the `<script>` in the Javadoc.

If we want to gate on warnings too: drop `--fail-severity error` for
`--fail-severity warn` on the spectral CLI invocation. Decide as a
separate session — for now warnings are visible in the report
artifact + Sonar issues UI.

### Follow-ups from ADR-0039 (kube-prometheus-stack overlay)

The `local-prom/` overlay shipped 2026-04-21. The `gke-prom/` overlay
shipped 2026-04-21 too (this MR). The Mirador ServiceMonitor shipped in
both. Remaining follow-ups:

- **Smoke-test `gke-prom/` on the ephemeral cluster** — kustomize+dry-run
  validation passes locally, but the storage class, PVC reclaim, and
  Autopilot privileged-DaemonSet behaviours haven't been exercised on
  a real GKE cluster yet. Schedule alongside the next
  `bin/cluster/demo-up.sh` cycle. Then verify:
  - PVC for `prometheus-prometheus-stack-kube-prom-prometheus-db-...-0`
    binds on `standard-rwo` and is mounted at `/prometheus`.
  - node-exporter pods schedule on every Autopilot worker node (PSS
    privileged label honored).
  - kube-prom Prometheus successfully scrapes Mirador's
    `/actuator/prometheus` cross-namespace.
  - Grafana datasource "Prometheus (kube-prom-stack)" returns data on
    a `up{job="kubelet"}` query.
  - After `demo-down.sh`, the orphaned PVC is detected and cleaned by
    `bin/budget/gcp-cost-audit.sh`.
- **`test:k8s-apply-prom` CI job** — copy of the existing `test:k8s-apply`
  but applies `local-prom/`. Adds ~3 min to the pipeline; only runs on
  `deploy/kubernetes/overlays/local-prom/**` changes (path filter).
  Catches Prometheus Operator manifest drift in CI before it hits a
  developer's machine. Also extend with a `gke-prom` matrix entry now
  that the overlay exists.
- **kubelet CA injection on GKE** — gke-prom currently keeps
  `insecureSkipVerify: true` on the 3 kubelet ServiceMonitor endpoints
  because GKE kubelet certs aren't signed by the SA-token-visible root.
  Fix path: mount the kubelet CA from a Secret + add `caFile:` to each
  endpoint via a JSON6902 patch on the rendered file. Documented in
  ADR-0039 "Future work" + the gke-prom values-kube-prom-stack.yaml
  comment block under `kubelet:`.

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
