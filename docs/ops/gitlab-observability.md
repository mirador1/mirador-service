# GitLab Observability — operational guide

GitLab's managed OTLP ingest endpoint, beta as of 2026-04. Mirror sink
of the local LGTM stack — every trace / metric / log emitted by
`mirador-service` lands in **both** local Grafana AND GitLab's group
Observability tab, so reviewers don't need to clone + boot Docker
just to see telemetry.

For the architectural rationale (why dual-export vs replace, alternatives
considered), read [ADR-0054](../adr/0054-gitlab-observability-dual-export.md).
This doc covers operational : endpoints, who pushes, how to view, how
to verify, how to opt-out, how to extend.

---

## TL;DR

| Field | Value |
|---|---|
| Endpoint | `https://130289716.otel.gitlab-o11y.com:14318` (HTTPS, OTLP HTTP) |
| Endpoint (gRPC) | `https://130289716.otel.gitlab-o11y.com:14317` |
| Auth | None during beta (group-id in subdomain = identifier) |
| Group | [mirador1](https://gitlab.com/groups/mirador1) (group-id `130289716`) |
| Cost | Free during beta |
| Activated | 2026-04-23 |
| What pushes | `mirador-service` OTel Collector only (UI + Python NOT wired yet) |
| What ingests | Traces + Metrics + Logs (all 3 OTel signals) |
| Where to view | Group sidebar → Observability |

---

## Architecture (who pushes what where)

```
                                ┌── otlphttp/{traces,metrics,logs}        ──► LGTM local (Grafana :3000)
mirador-service Spring Boot ──► OTel Collector (port 4317/4318)
                                └── otlphttp/{traces,metrics,logs}-gitlab ──► https://130289716.otel.gitlab-o11y.com:14318
```

The OTel Collector (single binary inside the LGTM container's wider stack)
sits between Spring Boot and the backends. It receives OTLP from the app
on `:4317` (gRPC) / `:4318` (HTTP), applies any resource attribute /
sampling / batching transforms, then **fans out** to whatever exporters
are listed in `service.pipelines.<signal>.exporters`. Adding GitLab
Observability is one extra exporter block per signal.

### Currently emitting

- ✅ **`mirador-service`** (Java/Spring Boot) — wired via
  [`infra/observability/otelcol-override.yaml`](../../infra/observability/otelcol-override.yaml).
  All 3 signals (traces / metrics / logs) dual-exported.

### NOT emitting yet (future opt-in)

- ❌ **`mirador-ui`** (Angular) — has its own OTel Web SDK
  ([UI ADR-0009](file:///Users/benoitbesson/dev/js/mirador-ui/docs/adr/0009-browser-telemetry-via-otlp.md))
  but ships only to local Collector currently. To dual-export to
  GitLab : add `OTEL_EXPORTER_OTLP_ENDPOINT_GITLAB` env var + a 2nd
  exporter in the SDK init. (No ADR yet ; would mirror this doc.)
- ❌ **`mirador-service-python`** (Python/FastAPI) — uses
  `opentelemetry-exporter-otlp-proto-http` pointing at local
  `http://localhost:4318`. Same extension pattern as the UI : add a 2nd
  exporter pointing at the GitLab endpoint.

When extending, keep the local LGTM exporter as primary (lower latency,
offline-reviewable, richer Grafana UX) ; the GitLab sink is the
shared portfolio surface.

---

## Endpoint configuration

The endpoint is hardcoded as default in
[`infra/observability/otelcol-override.yaml`](../../infra/observability/otelcol-override.yaml) :

```yaml
exporters:
  otlphttp/traces-gitlab:
    endpoint: ${env:GITLAB_OBSERVABILITY_ENDPOINT:-https://130289716.otel.gitlab-o11y.com:14318}
    tls: { insecure: false }
```

The `${env:GITLAB_OBSERVABILITY_ENDPOINT:-…}` syntax means : use the env
var if set, fall back to the mirador1 default. Forks override via
`.env` :

```sh
# .env (gitignored, copy from .env.example)
GITLAB_OBSERVABILITY_ENDPOINT=https://<your-group-id>.otel.gitlab-o11y.com:14318
```

To find your own group-id : open https://gitlab.com/groups/<your-group>/-/observability/setup
and read it from the displayed endpoint URL (or query the group via
GitLab API : `GET /groups/<your-group-path>` returns `id`).

---

## How to view the data

Group sidebar (https://gitlab.com/groups/mirador1/-/observability) :

| Signal | URL | UI feature parity vs Grafana |
|---|---|---|
| Tracing | https://gitlab.com/groups/mirador1/-/observability/tracing | Span list + trace waterfall ; richer than basic Tempo, less than Grafana ExploreLogs2Metrics |
| Metrics | https://gitlab.com/groups/mirador1/-/observability/metrics | Time-series view ; PromQL-like query syntax |
| Logs | https://gitlab.com/groups/mirador1/-/observability/logs | Loki-style filtering + correlation to spans |

GitLab's Observability UI is **maturing fast** but still less polished
than Grafana for trace deep-dive. Use cases :

- **Reviewer / portfolio reader** : open the group's Observability tab,
  see live data without cloning anything. ← primary win.
- **Async post-mortem** : data persists after `demo-down` tears LGTM
  down. Look at "what happened during yesterday's 20-min demo".
- **Live debug during a demo** : prefer Grafana local (`:3000`) for
  query speed + richer trace search.

---

## How to verify it's working

### 1. Smoke test the endpoint (no app required)

```sh
curl -X POST https://130289716.otel.gitlab-o11y.com:14318/v1/traces \
  -H "Content-Type: application/json" \
  -d '{"resourceSpans":[]}'
# Expect : 200 OK (empty body acceptable in OTLP HTTP)
```

If you get 404 / 401 / 5xx :
- 404 : group-id wrong. Re-check the URL.
- 401 : beta has rolled out token requirement. Add
  `GITLAB_OBSERVABILITY_TOKEN` env var + uncomment the `headers` block
  in `otelcol-override.yaml`.
- 5xx : GitLab side issue. The local LGTM sink keeps working
  (independent pipeline), so the app doesn't degrade.

### 2. Generate traffic + observe ingestion

```sh
./run.sh obs                                    # bring up LGTM + Collector
./run.sh all                                    # bring up the rest of the stack
sleep 30                                        # let the app boot + emit
curl http://localhost:8080/customers            # generate a request
curl http://localhost:8080/customers/3/enrich   # generate a Kafka span
```

Open https://gitlab.com/groups/mirador1/-/observability/tracing — within
~30s a span named `GET /customers` should appear. If not, check
`docker logs <otel-collector-container>` for export errors.

### 3. Check Collector self-telemetry

The Collector emits its own metrics about exporter throughput :

```sh
curl http://localhost:8889/metrics | grep otelcol_exporter
```

Look for `otelcol_exporter_sent_spans_total{exporter="otlphttp/traces-gitlab"}`
— if it's incrementing, the export is working.

---

## How to opt-out (airgapped work, fork without GitLab access)

Edit `infra/observability/otelcol-override.yaml`, remove the
`otlphttp/*-gitlab` entries from each `service.pipelines.*.exporters`
list. Local LGTM sink keeps working unchanged. No app code changes.

Or set the endpoint to `https://example.invalid:14318` — the exporter
will fail every send + log a warning, but won't break the app
(BatchSpanProcessor drops on overflow ; spans aren't load-bearing).

---

## CI / pipeline observability (different feature, same toggle)

The same group-level Observability feature flag also enables **GitLab
CI pipeline telemetry** export. That's separate from this app
telemetry — pipelines emit their own OTLP spans (job duration,
runner queue time, etc.) which surface in the same Observability tab.

If you want to disable just the CI part (keep app telemetry on), use
the per-project setting under Settings → Monitor → Observability.

---

## Feature stability

| Aspect | Status (2026-04-25) |
|---|---|
| Free tier | Free during beta (UI banner) |
| API contract | Beta — may change before GA |
| Auth | None required (group-id is identifier) |
| Retention | Not publicly documented ; managed by GitLab |
| SLA | None (beta) |

When GA arrives, expect :
- Possible bearer-token auth (`Authorization: Bearer <token>`)
- Pricing tier (probably free on Premium/Ultimate, paid on free)
- Stable API guarantee
- Documented retention

The wiring shape (one extra exporter per pipeline) won't change — only
the auth + the cost per signal.

---

## See also

- [ADR-0054](../adr/0054-gitlab-observability-dual-export.md) — design rationale
- [ADR-0010](../adr/0010-otlp-push-to-collector.md) — why OTLP push (not pull)
- [ADR-0039](../adr/0039-two-observability-deployment-modes.md) — local vs
  prom overlays — dual-export applies to both
- [`infra/observability/otelcol-override.yaml`](../../infra/observability/otelcol-override.yaml) — the actual config
- [docs/architecture/observability.md](../architecture/observability.md) — overall observability story
- GitLab Observability docs : https://docs.gitlab.com/ee/operations/tracing.html
- mirador1 group setup page : https://gitlab.com/groups/mirador1/-/observability/setup
