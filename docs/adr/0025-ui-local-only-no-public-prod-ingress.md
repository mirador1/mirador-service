# ADR-0025 — One UI, run locally; no public ingress in prod; port-forward tunnels for prod access

- **Status**: Accepted
- **Date**: 2026-04-18
- **Supersedes**: the public-ingress / TLS / DuckDNS parts of ADR-0022 and ADR-0023
- **Related**: ADR-0013 (in-cluster Postgres), ADR-0015 (GitOps / Argo CD), ADR-0024 (BFF pattern)

## Context

Up to this ADR, the GKE Autopilot demo cluster exposed several HTTPS
endpoints behind `mirador1.duckdns.org` and sub-hosts:

- `mirador1.duckdns.org` — Angular UI + backend API
- `grafana.mirador1.duckdns.org` — LGTM (Grafana)
- `argocd.mirador1.duckdns.org` — Argo CD UI

Each came with a Let's Encrypt certificate issued by cert-manager, an
Ingress in the `app` / `infra` / `argocd` namespaces, a DuckDNS entry
updated on every cluster spin-up, and a handful of RBAC patches for
GKE Autopilot.

That surface was useful when we thought "recruiter clicks a URL, sees
the live demo." In practice it has two costs that outweigh the benefit:

1. **Security** — any URL reachable from the public internet, even
   gated by JWT/OIDC, carries vulnerabilities we have neither the
   time nor the budget to continuously harden. Ops tooling (Grafana,
   Argo CD, Unleash, Chaos Mesh dashboard) ending up publicly
   reachable is a "why-is-this-not-behind-a-VPN" smell on a CV, not
   a feature.
2. **Operational weight** — cert-manager + DuckDNS updater + TLS
   patches + ClusterIssuer failure modes + "your cert expired while
   the cluster was down 7 days" etc. Roughly 25 % of the `bin/demo-*`
   code existed to manage this external surface. With the ephemeral
   cluster pattern (ADR-0022), most of that re-runs every spin-up.

The Angular UI is duplicated across two deploy targets (docker-compose
local + `customer-ui` Deployment in the cluster) with different
CORS / env-service config matrices. Keeping both in sync is pure
accidental complexity.

## Decision

**Three tightly-coupled choices:**

1. **The UI runs only on the developer's laptop.** No `customer-ui`
   Deployment or Service in Kubernetes, no frontend Ingress path.
   `ng serve` (dev) or `npm run build` + static server (prod-like
   local) are the only ways the UI is ever served.

2. **No public ingress on the GKE cluster.** The Ingress resources,
   cert-manager install, Let's Encrypt ClusterIssuer, DuckDNS update
   step, `lgtm-ingress.yaml`, `argocd/ingress.yaml`, and GKE-specific
   cert-manager RBAC fix are all removed. The cluster exposes nothing
   to the public internet by default.

3. **Prod access goes through `kubectl port-forward`.** A family of
   scripts under `bin/` (`pf-prod.sh`, `pf-stop.sh`, `pf-status.sh`)
   spin up one local tunnel per service on a predictable port so the
   same local UI can flip between "dev" (docker-compose) and
   "prod-tunnel" (GKE via port-forward) via an environment selector
   in the Angular topbar.

### Port map

Convention: dev/compose uses the upstream defaults, prod tunnels
prefix the same digit with `1` so both can coexist on the laptop
simultaneously.

| Service              | Dev (compose) | Prod (port-forward) |
|----------------------|---------------|---------------------|
| Backend API          | `8080`        | `18080`             |
| Postgres             | `5432`        | `15432`             |
| Redis                | `6379`        | `16379`             |
| Kafka                | `9092`        | `19092`             |
| Grafana (LGTM)       | `3000`        | `13000`             |
| Tempo                | `3200`        | `13200`             |
| Loki                 | `3100`        | `13100`             |
| Mimir (Prom API)     | `9009`        | `19009`             |
| Pyroscope            | `4040`        | `14040`             |
| Keycloak             | `9090`        | `19091`             |
| Unleash              | `4242`        | `14242`             |
| Argo CD UI           | –             | `18081`             |
| Chaos Mesh dashboard | –             | `12333`             |

## Consequences

### Positive

- **No public attack surface on prod** beyond the GKE API server (which
  already requires `gcloud auth` + IAM). Grafana / Argo CD / Unleash /
  Chaos Mesh are invisible to the internet.
- **No TLS cert lifecycle to manage.** No Let's Encrypt rate limits,
  no "cert expired while the demo slept" incidents, no cert-manager
  upgrade pain. `bin/demo-up.sh` drops ~30 lines.
- **No DuckDNS IP updates on every spin-up.** The ephemeral pattern
  now touches only GKE and GSM.
- **Single UI codebase.** No `customer-ui` image rebuild in CI, no
  CORS matrix between dev / prod, no env-specific builds. The UI
  repo's `EnvService` becomes an explicit "environment selector"
  component in the topbar.
- **Uniform dev workflow.** Run the UI the same way for both envs —
  only the env picker changes the backend URL and the handful of
  tunnel ports it hits.

### Negative

- **Recruiters can't click a URL.** The GitLab Pages static landing
  page (`public/index.html`) becomes the only public artefact. It
  links to the repo + instructions for cloning + `bin/pf-prod.sh`.
  Higher friction for demo viewing, much lower risk.
- **Port-forward fragility.** `kubectl port-forward` drops when the
  pod is killed or restarted (Chaos Mesh pod-kill experiments will
  regularly take down the backend tunnel). `bin/pf-prod.sh` includes
  an auto-restart inner loop to recover within ~2 s of pod churn.
- **13 processes in the background.** The `pf-prod.sh` script starts
  one `kubectl` subprocess per service. Memory is negligible (~5 MB
  each) but the process list is noisier. `pf-stop.sh` is a hard
  requirement for clean teardown.
- **Frontend image pipeline becomes vestigial.** The mirador-ui repo's
  `docker build + push to GitLab Registry` job stays for now (future
  option to re-enable an Ingress if one is ever needed) but is no
  longer deployed anywhere. Flagged as a cleanup follow-up.

## Alternatives considered

| Option                                                 | Why rejected                                                               |
|--------------------------------------------------------|----------------------------------------------------------------------------|
| Keep UI public, IAP-gate ops tooling                   | IAP requires a GCP org + load balancer and LBs on Autopilot are €20+/month — breaks ADR-0022 €2/month target. |
| Keep UI public, VPN for ops tooling                    | WireGuard + firewall rules + client onboarding for every recruiter is absurd. |
| Cloudflare Tunnel (cloudflared)                        | Free tier is fine, but every tool still gets a public hostname and Cloudflare becomes the new attack surface. Same smell, different vendor. |
| Keep everything public behind a single oauth2-proxy    | Still a public surface; auth bugs in oauth2-proxy have CVE history. No justification for the risk when port-forward is free and simpler. |

## Implementation notes

- **Delete**: `deploy/kubernetes/base/frontend/`, `base/ingress.yaml`,
  `overlays/gke/ingress-tls-patch.yaml`, `overlays/gke/lgtm-ingress.yaml`,
  `overlays/gke/cert-manager-gke-fix.yaml`, `deploy/argocd/ingress.yaml`.
- **Edit**: `overlays/gke/image-tags-patch.yaml` loses the customer-ui
  entry; `overlays/gke/kustomization.yaml` drops deleted files;
  `base/kustomization.yaml` drops frontend + ingress refs;
  `bin/demo-up.sh` drops cert-manager install + DuckDNS step +
  Argo CD Ingress apply.
- **Add**: `bin/pf-prod.sh`, `bin/pf-stop.sh`, `bin/pf-status.sh`.
- **Update**: `public/index.html` (landing points at clone + tunnel
  flow), `README.md` (swap public URLs for tunnel ports).

## UI-side follow-up (mirador-ui repo)

The EnvService needs a dropdown in the topbar with two entries:

```
Environment: ( • dev ) ( prod-tunnel )
```

Selecting `prod-tunnel` changes all service URLs to `localhost:1*`
per the port map. Selecting `dev` restores the compose defaults.
State persists in `localStorage`. Tracked separately in the UI
repo's `TASKS.md`.

## Revisit this when

- A recruiter explicitly asks "do you have a URL?" often enough to
  outweigh the security concern. Re-introduce Cloudflare Tunnel for
  the UI only, with a read-only `mirador.ui.ops-mode=false` Unleash
  flag hiding every ops panel.
- The project attracts more than one simultaneous dev. `kubectl
  port-forward` does not scale to shared prod access — move to a
  bastion or IAP at that point.
