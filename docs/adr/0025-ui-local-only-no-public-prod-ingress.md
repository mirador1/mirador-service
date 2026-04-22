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

### Port map — three environments, +10000 per env (decided 2026-04-18)

Convention: compose uses the upstream defaults, kind adds `+10000`, prod
adds `+20000`. Each environment has its own 5-digit decade, so the three
can coexist on the laptop simultaneously (useful for kind-vs-prod
comparisons).

| Service              | Compose  | Kind (+10000) | Prod (+20000) |
|----------------------|----------|---------------|---------------|
| Backend API          | `8080`   | `18080`       | `28080`       |
| Postgres             | `5432`   | `15432`       | `25432`       |
| Redis                | `6379`   | `16379`       | `26379`       |
| Kafka                | `9092`   | `19092`       | `29092`       |
| Grafana (LGTM)       | `3000`   | `13000`       | `23000`       |
| Tempo                | `3200`   | `13200`       | `23200`       |
| Loki                 | `3100`   | `13100`       | `23100`       |
| Mimir (Prom API)     | `9009`   | `19009`       | `29009`       |
| Pyroscope            | `4040`   | `14040`       | `24040`       |
| Keycloak             | `9090`   | `19090`       | `29090`       |
| Unleash              | –        | `14242`       | `24242`       |
| Unleash front-proxy  | –        | `14243`       | `24243`       |
| Argo CD UI           | –        | `18081`       | `28081`       |
| Chaos Mesh dashboard | –        | `12333`       | `22333`       |

Compose-local tooling (no offset — always the same port, regardless of
which tunnel is active):

| Service              | Port     | Target                                                  |
|----------------------|----------|---------------------------------------------------------|
| `pgweb-local`        | `8081`   | compose db:5432                                         |
| `pgweb-kind`         | `8082`   | host.docker.internal:15432 (profile `kind-tunnel`)      |
| `pgweb-prod`         | `8083`   | host.docker.internal:25432 (profile `prod-tunnel`)      |
| CloudBeaver          | `8978`   | compose db:5432 (user configures cluster conn as needed)|
| Kafka UI             | `9080`   | compose kafka                                           |
| RedisInsight         | `5540`   | compose redis                                           |

## Consequences

### Positive

- **No public attack surface on prod** beyond the GKE API server (which
  already requires `gcloud auth` + IAM). Grafana / Argo CD / Unleash /
  Chaos Mesh are invisible to the internet.
- **No TLS cert lifecycle to manage.** No Let's Encrypt rate limits,
  no "cert expired while the demo slept" incidents, no cert-manager
  upgrade pain. `bin/cluster/demo/up.sh` drops ~30 lines.
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
  links to the repo + instructions for cloning + `bin/cluster/port-forward/prod.sh`.
  Higher friction for demo viewing, much lower risk.
- **Port-forward fragility.** `kubectl port-forward` drops when the
  pod is killed or restarted (Chaos Mesh pod-kill experiments will
  regularly take down the backend tunnel). `bin/cluster/port-forward/prod.sh` includes
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
  `bin/cluster/demo/up.sh` drops cert-manager install + DuckDNS step +
  Argo CD Ingress apply.
- **Add**: `bin/cluster/port-forward/prod.sh`, `bin/cluster/port-forward/kind.sh`, `bin/cluster/port-forward/stop.sh`,
  `bin/cluster/port-forward/status.sh`. The port offsets (Kind +10000, Prod +20000) were
  finalised on 2026-04-18 — see the port map above.
- **Update**: `public/index.html` (landing points at clone + tunnel
  flow), `README.md` (swap public URLs for tunnel ports).

## UI-side follow-up (mirador-ui repo)

The EnvService has a dropdown in the topbar with three entries:

```
Environment: ( • Local ) ( Kind ) ( Prod tunnel )
```

- `Local`  → compose defaults (`localhost:8080` etc.)
- `Kind`   → kind cluster via bin/cluster/port-forward/kind.sh, ports `1xxxx`
- `Prod tunnel` → GKE cluster via bin/cluster/port-forward/prod.sh, ports `2xxxx`

Selection persists in `localStorage`. Signals downstream recompute.
Env-specific URLs (`unleashUrl`, `argocdUrl`, `chaosMeshUrl`, `pgwebUrl`)
are only defined on the environments where the tool runs — the UI gates
optional buttons on `@if (env.<tool>Url())`.

## Revisit this when

- A recruiter explicitly asks "do you have a URL?" often enough to
  outweigh the security concern. Re-introduce Cloudflare Tunnel for
  the UI only, behind a strict read-only auth gate.
- The project attracts more than one simultaneous dev. `kubectl
  port-forward` does not scale to shared prod access — move to a
  bastion or IAP at that point.
