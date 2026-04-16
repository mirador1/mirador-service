# `infra/observability/` — Observability stack configs (LGTM + OTel)

This directory holds all the configuration files mounted into the local
observability containers: the **LGTM** all-in-one container (Grafana + Loki +
Tempo + Mimir), the OpenTelemetry Collector, Prometheus, and a CORS reverse
proxy for browser-to-Loki calls. Together they form the observability stack
started by `docker compose -f docker-compose.observability.yml up -d` (or
`./run.sh obs`).

## Why a dedicated compose file?

The observability stack is heavy (~2 GB RAM) and not always needed during
local development. Keeping it in a second compose file means:

- `docker compose up -d` brings up only the app stack (Postgres, Kafka, Redis,
  Keycloak, app)
- `./run.sh obs` adds the observability stack on top when you want to see
  traces/metrics/logs

The two files share the same Docker network so the app can emit telemetry
into the OTel collector as soon as it's running.

## Files and sub-directories

| Entry                           | Role                                                                                                                                                    | Mounted into                                                   |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| [`grafana/`](grafana/)          | Dashboards and provisioning (data sources) auto-imported by Grafana on startup — makes the UI useful out of the box with zero manual configuration.     | `/etc/grafana/provisioning/...` and `/var/lib/grafana/dashboards/` in the LGTM container |
| `otelcol-override.yaml`         | Override for the OpenTelemetry Collector config bundled with the LGTM container. Routes traces to Tempo, metrics to Mimir, logs to Loki. Removes Jaeger/Zipkin (deprecated — Tempo is the single tracing backend). | `/etc/otel-collector-config.yaml` in the LGTM container        |
| `prometheus.yml`                | Prometheus scrape config for the standalone Prometheus container. Scrapes the Spring Boot `/actuator/prometheus` endpoint every 15s.                    | `/etc/prometheus/prometheus.yml` in the Prometheus container   |
| `cors-proxy.conf`               | Nginx config for a CORS-enabled reverse proxy in front of Loki (port 3100) and the Docker API. The Angular UI at `localhost:4200` queries both from the browser; neither supports CORS natively. Zipkin has its own CORS knob (`ZIPKIN_HTTP_ALLOWED_ORIGINS`) so it bypasses this proxy. | `/etc/nginx/conf.d/default.conf` in the `cors-proxy` container |
| `README.md`                     | This file.                                                                                                                                              | (not mounted)                                                  |

## Ports (local dev)

| URL                           | Service                                                   |
| ----------------------------- | --------------------------------------------------------- |
| <http://localhost:3001>       | Grafana (LGTM) — default login `admin` / `admin`          |
| <http://localhost:9090>       | Prometheus standalone (scrape config here)                |
| <http://localhost:3100>       | Loki direct access (via CORS proxy at `localhost:3102`)   |
| <http://localhost:3102>       | Loki **via** CORS proxy (browser-safe)                    |
| <http://localhost:4317/4318>  | OTel collector gRPC/HTTP endpoints (app emits here)       |

## When to edit files in this directory

- **Adding a Grafana dashboard** → drop a `.json` into `grafana/dashboards-lgtm/`
  and reference it from `grafana/dashboard-*.yaml` (auto-loaded).
- **Scraping a new metrics source** → add a job to `prometheus.yml`.
- **New OTel processor/exporter** → update `otelcol-override.yaml`.
- **Front-end needs access to a new backend** → add a location block to
  `cors-proxy.conf`.

## Relationship to production

- In production (GKE), observability is handled by **Grafana Cloud** via
  direct OTLP push from the Spring Boot app. None of these files are
  deployed — only `OTEL_EXPORTER_OTLP_*` env vars are injected via a
  K8s Secret.
- The local LGTM stack is a **faithful-enough** replica that dashboards
  developed locally work when uploaded to Grafana Cloud with minor
  adjustments (data-source names mostly).
