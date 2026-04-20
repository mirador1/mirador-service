# `deploy/kubernetes/` — Kubernetes manifests (Kustomize layout)

This directory holds the Kubernetes manifests applied by the `deploy:*` jobs
in `.gitlab-ci.yml` and by `scripts/deploy-local.sh`. It follows the
**Kustomize base + overlays** pattern — the industry-standard way to keep
one source of truth while customising per target environment.

## Layout

```
deploy/kubernetes/
├── base/                          ← shared resources, no cluster-specifics
│   ├── kustomization.yaml           ← declares everything in the base
│   ├── namespace.yaml               ← app / infra / observability
│   ├── ingress.yaml                 ← HTTP-only (TLS added by overlays)
│   ├── backend/                     ← Spring Boot: Deployment, Service, HPA, ConfigMap
│   ├── frontend/                    ← Angular SPA: Deployment, Service
│   ├── stateful/                    ← in-cluster Kafka, Redis, Keycloak
│   └── postgres/                    ← OPTIONAL in-cluster Postgres (own mini-base)
│
├── overlays/
│   ├── local/                     ← kind cluster (deploy-local.sh)
│   │   ├── kustomization.yaml       includes ../../base + ../../base/postgres
│   │   └── images-pullpolicy-patch.yaml  (Never — kind loads images directly)
│   │
│   ├── gke/                       ← GKE Autopilot + Cloud SQL + Let's Encrypt
│   │   ├── kustomization.yaml
│   │   ├── cert-manager-gke-fix.yaml     (RBAC for Autopilot)
│   │   ├── cloud-sql-proxy.yaml          (ServiceAccount + DB_HOST override)
│   │   ├── ingress-tls-patch.yaml        (cert-manager + tls: block)
│   │   └── backend-cloudsql-sidecar-patch.yaml  (sidecar container)
│   │
│   ├── eks/                       ← AWS EKS + in-cluster Postgres (same shape as local)
│   │   └── kustomization.yaml
│   │
│   └── aks/                       ← Azure AKS + in-cluster Postgres (same shape as local)
│       └── kustomization.yaml
│
├── kind-config.yaml               ← kind cluster config for deploy-local.sh
└── README.md                      ← you are here
```

## Namespaces

Mirador follows the principle of least privilege: each pod lands in the
namespace whose PodSecurity profile and NetworkPolicy boundary fit its
trust level. The split is deliberate — don't collapse everything into
`default` "for simplicity". The security posture relies on the boundary.

| Namespace            | Role                                                               | Pods deployed                                                                                                  | Why this namespace?                                                                                                                                                                                                                       |
| -------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `app`                | Mirador backend runtime                                            | `mirador` (HPA, 1-5 replicas, default 2)                                                                       | Isolated from infra so PodSecurity `restricted:latest` enforces — the app is hardened (non-root UID 1000, read-only rootfs, drop-ALL caps, seccomp RuntimeDefault), unlike off-the-shelf infra charts.                                     |
| `infra`              | Stateful + auth + observability deps                               | `postgresql`, `kafka`, `redis`, `keycloak`, `unleash`, `unleash-db`, `unleash-proxy`, `lgtm`, `pyroscope`      | PodSecurity `baseline` — these charts don't fit `restricted` (root users, init-container capabilities, PVC fsGroup). Grouped so NetworkPolicy `allow-app-ingress-to-infra` can target a single namespace on Postgres/Redis/Kafka ports only. |
| `observability`      | Reserved for future split (LGTM moved out of `infra`)              | (empty today)                                                                                                  | Placeholder — when LGTM + Pyroscope are extracted into a dedicated obs stack, they land here. Labelled `baseline` in `namespace.yaml` so Grafana's root-user sidecars won't block on day one.                                              |
| `kube-system`        | K8s control plane (apiserver, scheduler, etcd, coredns, kube-proxy, kindnet) | standard                                                                                                       | K8s convention — never deploy app code here. On kind also hosts `kindnet` CNI.                                                                                                                                                               |
| `kube-public`        | Anonymous-readable cluster info                                    | (empty on kind)                                                                                                | K8s convention — only `cluster-info` ConfigMap by default (`kubectl get configmap -n kube-public`).                                                                                                                                         |
| `kube-node-lease`    | Node heartbeats                                                    | (none visible — pure internal Lease objects)                                                                   | K8s convention — performance optimisation for node health (Lease API instead of full Node status).                                                                                                                                          |
| `local-path-storage` | kind's default PVC provisioner (dev only)                          | `local-path-provisioner`                                                                                        | Ships with kind, binds `local-path` StorageClass to a hostPath directory. On GKE this namespace is absent — PVCs are satisfied by the `pd-balanced` CSI driver.                                                                              |
| `default`            | Unused here                                                        | (none)                                                                                                          | Everything is declared in an explicit namespace — don't leak workloads to `default`. If you see a pod there, it's a misconfigured manifest.                                                                                                   |

**Why the `app` / `infra` split matters concretely:**
- PodSecurity admission at namespace level catches a container trying to
  `runAsUser: 0` *before* it starts. If `mirador` ever regresses (e.g.
  a base-image upgrade flips the default user back to root), the
  `restricted:latest` label on `app` rejects the rollout — no
  surprise-root in prod.
- NetworkPolicy `allow-app-ingress-to-infra` (see
  `base/networkpolicies.yaml`) uses a `namespaceSelector` match on
  `kubernetes.io/metadata.name: app`. Without a dedicated `app`
  namespace we'd have to tag every pod individually.

### Per-pod reference

Each pod below describes: **what it does**, **image + port**, **key
probes**, **what happens if it goes DOWN**, and **where its config
lives**. Resource requests/limits follow
[ADR-0014](../../docs/adr/0014-single-replica-for-demo.md).

#### `mirador` (namespace `app`)

- **Role**: Spring Boot 4 backend. Serves `/api/*` (customer domain),
  `/actuator/*` (health, Prometheus, quality, maintenance), `/auth/*`
  (JWT issuance, Keycloak OIDC relay), and pushes OTLP telemetry to
  `lgtm.infra:4317`.
- **Image**: `registry.gitlab.com/mirador1/mirador-service/backend:main`
  (rolling tag, `imagePullPolicy: Always`; overridden to `Never` in the
  `local` overlay because kind side-loads images).
- **Port**: `8080/TCP` (http, behind Service `mirador`).
- **Probes**:
  - `startupProbe` `/actuator/health/liveness` — up to 5 min (60 × 5s)
    to absorb GKE Autopilot cold-start + JVM warm-up + Flyway migrations
  - `livenessProbe` `/actuator/health/liveness` — lightweight, no
    external deps (10s period, 3 failures = restart)
  - `readinessProbe` `/actuator/health/readiness` — checks DB, Kafka,
    Redis (5s period, 3 failures = pulled from Service endpoints)
- **If DOWN**: UI calls to `/api/*` return 502/connection-refused (no
  replicas behind Service). HPA still honors `minReplicas: 1`; if all
  pods are down and a new one can schedule, it recovers in ~90s. The
  ingress `/health` check fails and alerting fires (when wired).
- **Config**: `base/backend/` — `deployment.yaml`, `service.yaml`,
  `hpa.yaml`, `configmap.yaml`, `poddisruptionbudget.yaml`. Sensitive
  values come from Secret `mirador-secrets` (projected by ESO from GSM
  in `gke`, plaintext-applied in `local`).

#### `postgresql` (namespace `infra`)

- **Role**: Primary relational store for the customer domain (Flyway
  manages the schema). In-cluster StatefulSet used by `local`, `eks`,
  `aks` overlays; `gke` swaps it for Cloud SQL via a proxy sidecar
  ([ADR-0013](../../docs/adr/0013-in-cluster-postgres-on-gke-for-the-demo.md)
  — reversed from the earlier ADR-0003).
- **Image**: `postgres:17-alpine`.
- **Port**: `5432/TCP` (Service `postgresql`).
- **Probes**: `pg_isready -U demo -d customer-service` for both
  liveness and readiness.
- **If DOWN**: `mirador` `readinessProbe` fails (DB health indicator
  red) and its pods leave the Service; UI sees 503 on `/api/customers`.
  Keycloak uses its embedded H2 (not this DB) so auth still works.
  PVC `postgres-data` (10 Gi) persists on the local-path provisioner.
- **Config**: `base/postgres/postgres.yaml` (own mini-base — see
  "Why Postgres is in its own mini-base" below).

#### `kafka` (namespace `infra`)

- **Role**: Single-broker KRaft-mode Kafka ([ADR-0005](../../docs/adr/0005-in-cluster-kafka.md)).
  Powers the `customer-events` topic + the chaos/rate-limit
  demonstrations. No ZooKeeper (KRaft since 3.3).
- **Image**: `apache/kafka:3.8.0`.
- **Port**: `9092/TCP` client, `9093/TCP` controller (internal only).
- **Probes**: `tcpSocket:9092` for both (advertised listener
  `kafka.infra.svc.cluster.local:9092` makes `kafka-topics.sh` probes
  unreliable from inside the pod — TCP is the pragmatic choice).
- **If DOWN**: `/api/customers` POST/PUT succeeds (Kafka publish is
  async with a circuit breaker), but the event timeline stops updating
  and outbound integrations stall. `mirador` readiness flips only if
  the CB stays open past the threshold.
- **Config**: `base/stateful/kafka.yaml`.

#### `redis` (namespace `infra`)

- **Role**: In-memory cache for the JWT denylist
  ([ADR-0018](../../docs/adr/0018-jwt-strategy-hmac-refresh-rotation.md))
  and the `RecentCustomerBuffer` ring buffer (dashboard metric). LRU
  eviction, 128 MB cap.
- **Image**: `redis:7-alpine`.
- **Port**: `6379/TCP`.
- **Probes**: `redis-cli ping` (liveness only — readiness is implicit
  on a ClusterIP Service).
- **If DOWN**: JWT revocation checks fail-open (`mirador` logs a
  warning and trusts the signature alone) and the ring-buffer widget
  on the dashboard shows stale data. Not a hard app stop.
- **Config**: `base/stateful/redis.yaml`.

#### `keycloak` (namespace `infra`)

- **Role**: OAuth2 / OIDC provider. The UI login form POSTs to
  `/auth/login` on `mirador`, which relays token requests to Keycloak.
  Runs `start-dev` — non-TLS, TLS termination at ingress in prod.
- **Image**: `quay.io/keycloak/keycloak:26.2`.
- **Port**: `8080/TCP` (Service `keycloak:80 → 8080`).
- **Probes**: `GET /realms/master` for both liveness and readiness.
- **If DOWN**: the UI login loop retries with the message "auth
  service unreachable". Existing JWTs keep working until expiry — the
  app does local signature validation (HMAC, ADR-0018) so it doesn't
  need Keycloak for every request.
- **Config**: `base/stateful/keycloak.yaml`. Admin password from Secret
  `keycloak-secrets`.

#### `unleash` (namespace `infra`)

- **Role**: Feature-flag server. Flags today: `mirador.bio.enabled`
  (kill-switch for the Ollama-backed `/bio` endpoint). The browser
  never calls Unleash directly — see `unleash-proxy`.
- **Image**: `unleashorg/unleash-server:7.6.3`.
- **Port**: `4242/TCP` (Service `unleash`).
- **Probes**: `GET /health` for both liveness and readiness.
- **If DOWN**: flag evaluations in the proxy return the configured
  default (per flag, stored last by the proxy). UI kill-switches stay
  in their last known state; no hard failure.
- **Config**: `base/unleash/unleash.yaml`. Waits on `unleash-db` via
  initContainer.

#### `unleash-db` (namespace `infra`)

- **Role**: Postgres backing the Unleash server **only**. Kept
  separate from the app Postgres so Unleash's Flyway migrations
  don't collide with ours — and so we can nuke-and-restore the Unleash
  schema without touching customer data.
- **Image**: `postgres:16.6-alpine`.
- **Port**: `5432/TCP` (Service `unleash-db`).
- **Probes**: `pg_isready -U unleash`.
- **If DOWN**: Unleash server crashes on boot or loses new flag
  writes; the proxy serves its in-memory cache until restart.
- **Config**: same file as `unleash` — `base/unleash/unleash.yaml`
  (StatefulSet `unleash-db`).

#### `unleash-proxy` (namespace `infra`)

- **Role**: Narrow read-only front-end proxy for Unleash
  ([ADR-0024](../../docs/adr/0024-bff-observability-proxy-and-unleash-without-sdk.md)
  + ADR-0026). The browser calls `/proxy?appName=mirador-ui` with a
  frontend token; the proxy relays to Unleash using a server-side
  admin token.
- **Image**: `unleashorg/unleash-proxy:1.4.17`.
- **Port**: `3000/TCP` (Service `unleash-proxy`).
- **Probes**: `GET /proxy/health`.
- **If DOWN**: the UI's feature-flag signals fall back to the SDK's
  bootstrap defaults. Visually some optional tabs may hide/show
  erratically on first load.
- **Config**: same file — `base/unleash/unleash.yaml`.

#### `lgtm` (namespace `infra`)

- **Role**: Grafana + Loki + Tempo + Mimir + OTel Collector in one pod
  ([ADR-0012](../../docs/adr/0012-stay-on-lgtm-with-bloom-filters.md),
  [ADR-0014](../../docs/adr/0014-single-replica-for-demo.md)). OTLP
  ingest on 4317/4318; `mirador` pushes here.
- **Image**: `grafana/otel-lgtm:0.25.0`.
- **Ports**: `3000` (Grafana), `4317/4318` (OTLP), `3100` (Loki),
  `3200` (Tempo), `9009` (Mimir) — all on Service `lgtm`.
- **Probes**: `GET /api/health` on 3000 for readiness (liveness
  implicit on the process).
- **If DOWN**: `mirador` OTLP export buffers briefly then drops
  (exporter retries with backoff). The dashboard iframe shows
  "unreachable". App functionality is unaffected — observability is
  one-way push.
- **Config**: `base/observability/lgtm.yaml` + `grafana-dashboards`
  ConfigMap for provisioned dashboards (home, golden-signals, canary).

#### `pyroscope` (namespace `infra`)

- **Role**: Continuous profiling server. The Java agent is embedded
  in the `mirador` image (`pyroscope-java.jar`) and pushes CPU +
  memory samples here. Grafana's Pyroscope datasource links through.
- **Image**: `grafana/pyroscope:1.14.0`.
- **Port**: `4040/TCP`.
- **Probes**: `GET /ready` for readiness.
- **If DOWN**: the agent's push fails silently (buffered, dropped on
  overflow). Flame graphs in Grafana show "no data". App unaffected.
- **Config**: `base/observability/pyroscope.yaml`.

#### `local-path-provisioner` (namespace `local-path-storage`)

- **Role**: kind's built-in dynamic PVC provisioner. Binds claims
  against the `standard` / `local-path` StorageClass to hostPath
  directories inside the kind node container.
- **Image**: shipped with kind — not managed by this repo.
- **If DOWN**: new PVCs stay `Pending`; existing PVs remain mounted.
  On GKE this pod is absent; `pd-balanced` CSI handles PVCs instead.
- **Config**: not ours — installed by `kind create cluster`.

## How to apply

Everything goes through `kubectl apply -k` (Kustomize is bundled in `kubectl`
≥ 1.14 — no separate install needed):

```bash
# Local dev (kind)
./scripts/deploy-local.sh                               # wraps the command below
kubectl apply -k deploy/kubernetes/overlays/local

# Production GKE
kubectl apply -k deploy/kubernetes/overlays/gke

# AWS EKS / Azure AKS (manual triggers in CI)
kubectl apply -k deploy/kubernetes/overlays/eks
kubectl apply -k deploy/kubernetes/overlays/aks
```

CI pipelines (`.gitlab-ci.yml` → `.kubectl-apply` template) export
`K8S_OVERLAY=<name>` then run:

```bash
kubectl kustomize "deploy/kubernetes/overlays/${K8S_OVERLAY}" \
  | envsubst \
  | kubectl apply -f -
```

`envsubst` replaces `${K8S_HOST}`, `${IMAGE_REGISTRY}`, `${IMAGE_TAG}`,
`${UI_IMAGE_TAG}`, `${CLOUD_SQL_INSTANCE}`, `${GCP_PROJECT}` and
`${CORS_ALLOWED_ORIGINS}` in the rendered YAML stream. Kustomize itself
does **not** do variable substitution.

## How each overlay differs from the base

| Overlay | Adds                                                                 | Removes                   | Patches                                                                 |
| ------- | -------------------------------------------------------------------- | ------------------------- | ----------------------------------------------------------------------- |
| `local` | `base/postgres` (in-cluster StatefulSet)                              | none                      | `imagePullPolicy: Never` on mirador + customer-ui (kind loads directly) |
| `gke`   | `cert-manager-gke-fix.yaml`, `cloud-sql-proxy.yaml`                   | `base/postgres` (skipped) | Ingress: cert-manager + TLS · Deployment: Cloud SQL proxy sidecar       |
| `eks`   | `base/postgres`                                                       | none                      | none (cluster-specific IAM via Terraform)                               |
| `aks`   | `base/postgres`                                                       | none                      | none (cluster-specific identities via Terraform)                        |

## Why Postgres is in its own mini-base

GKE uses Cloud SQL, not an in-cluster DB. Keeping `postgres/` out of the main
base means `overlays/gke` doesn't have to explicitly remove it — it simply
doesn't reference it. The other overlays opt in by listing
`../../base/postgres` in their `resources:` block.

Kustomize requires every resource to live inside a kustomization tree, so
the mini-base has its own `kustomization.yaml` (just `resources: [postgres.yaml]`).

## Adding a new overlay

1. `mkdir deploy/kubernetes/overlays/<target>`
2. Create `kustomization.yaml` with `resources: [../../base]` plus anything
   target-specific.
3. If the target needs in-cluster Postgres, add `../../base/postgres` to
   `resources`. If it uses managed PostgreSQL, skip it and add an override
   ConfigMap that sets `DB_HOST` to the managed endpoint (see
   `overlays/gke/cloud-sql-proxy.yaml` for a reference).
4. Verify the build:
   ```bash
   kubectl kustomize deploy/kubernetes/overlays/<target> | kubectl apply --dry-run=client -f -
   ```
5. Wire a new `deploy:<target>` job in `.gitlab-ci.yml` that exports
   `K8S_OVERLAY=<target>` and calls `!reference [.kubectl-apply, script]`.

## What NOT to put here

- **Cloud infrastructure** (VPC, GKE/EKS/AKS cluster, Cloud SQL instance,
  IAM bindings) → `deploy/terraform/`
- **Local Docker Compose configs** (dev-only services) → `infra/`
- **Application source code** → `src/`

## Related docs

- `deploy/terraform/gcp/README.md` — how the GKE cluster and Cloud SQL
  instance are provisioned.
- `.gitlab-ci.yml` — the `.kubectl-apply` template and each `deploy:*` job.
- `scripts/deploy-local.sh` — wraps kind + Kustomize for one-command local
  deployment.
- [`docs/architecture/environments-and-flows.md`](../../docs/architecture/environments-and-flows.md)
  — cross-environment diagrams (compose vs kind vs GKE tunnel) and the
  namespace boundaries as seen from the UI call flows.
