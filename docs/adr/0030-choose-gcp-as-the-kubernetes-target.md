# ADR-0030 — Choose GCP (GKE) as the Kubernetes target

- **Status**: Accepted
- **Date**: 2026-04-19
- **Related**: [ADR-0022](0022-ephemeral-demo-cluster.md),
  [ADR-0023](0023-stay-on-autopilot.md),
  [ADR-0016](0016-external-secrets-operator.md)

## Context

The project runs in-cluster on Kubernetes. The ADRs already in place
(0022 ephemeral cluster, 0023 Autopilot over Standard) take **GCP as a
given** without ever writing down why. This ADR closes that gap so
the decision is explicit and can be challenged with facts rather
than re-argued every six months.

The audience is a reviewer / future Claude session / adopting team
asking: "could this project run on AWS or Azure instead?" The answer
should be mechanical, not defensive.

## Decision

**Target: Google Kubernetes Engine (GKE) Autopilot in `europe-west1`**
for the portfolio-demo workload. The choice is **not** based on GCP
being universally better — it's based on a short list of measurable
constraints that narrowed the options to one.

## Factual constraints that led here

### 1. Budget €2/month (ADR-0022)

- **GKE**: control-plane fee is **€0** per cluster for the first
  zonal cluster per billing account, and free across all zonal
  clusters under Autopilot. Pod billing is the only line item.
- **AWS EKS**: control plane costs **$0.10/h = ~$72/month
  (~€66/month)** per cluster regardless of workload. That's already
  30× our budget before a single pod runs.
- **Azure AKS**: control plane is free (like GKE). The equivalent of
  Autopilot is **AKS Automatic** (GA 2024-06). Fits the model but is
  newer; we'd be early adopters of an evolving API.

**Winner on cost surface: GKE or AKS. EKS excluded mechanically.**

### 2. Autopilot-style per-pod billing

- **GKE Autopilot**: ~€0.02/pod-hour based on declared resource
  requests. 15 pods × 2h/demo = €0.60/demo.
- **AKS Automatic**: per-pod billing too (~$0.05/vCPU/h). Comparable,
  slightly more expensive at our size.
- **EKS Fargate**: per-pod, ~$0.04/vCPU/h + control plane. With EKS
  CP at $72/month the fixed cost alone kills it.

**Winner: GKE by ~20% at our volume, AKS close second.**

### 3. Workload Identity Federation (no long-lived keys in CI)

- **GCP WIF**: `gcloud iam workload-identity-pools` + OIDC federation
  with GitLab CI. Documented, widely used, no service-account keys
  on disk.
- **Azure WIF**: same pattern, works. Slightly less documentation,
  more clicks in the portal.
- **AWS IAM Roles for Service Accounts (IRSA)**: clean on EKS but
  mostly EKS-internal; federation with GitLab CI exists via OIDC
  but requires more wiring than GCP.

**Winner: GCP (convenience, documentation). AWS/Azure viable but
more setup.**

### 4. Managed services available in the same region

The project uses Cloud SQL for prod-like Postgres, Memorystore for
Redis, Managed Service for Kafka (all in europe-west1). Each
available but with different names/APIs per cloud. The choice
matters because:

- **Cross-cloud portability is not a goal** for this portfolio. The
  demo lives in one region on one cloud.
- **If we ever move**, each of those managed services has a
  drop-in equivalent in every major cloud.

So this criterion is **not a discriminator** — it just means "pick
one and stick with it".

### 5. Free credits at project start

New GCP accounts get **$300 in free credits, valid 90 days**. That
effectively makes the first 3 months of the portfolio free while
the cost model is being measured (ADR-0021 cost audit, ADR-0022
ephemeral pattern, ADR-0023 Autopilot).

AWS and Azure have comparable free-tier offers ($300 and ~$200
respectively). So this is **a tie-breaker, not a driver**.

### 6. Existing developer-machine setup

Pragmatic factor: `gcloud` SDK was already installed + authenticated
on the developer's Mac. Switching to `aws` or `az` would have cost
~30 min of CLI setup + region-picking + IAM policy drafting.

**This is a pragmatic tie-breaker, not a technical argument** — it
should NOT be the deciding factor in a real team. It happened to be
the deciding factor here because the other criteria already pointed
at GCP.

## The honest summary

| Criterion | GCP | AWS | Azure |
|---|---|---|---|
| Control-plane cost at our budget | ✅ free | ❌ €66/month | ✅ free |
| Autopilot-style per-pod billing | ✅ mature | ⚠️ Fargate | ✅ Automatic (newer) |
| WIF → CI auth without keys | ✅ documented | ✅ via IRSA | ✅ similar |
| Region available for managed services | ✅ europe-west1 | ✅ eu-west-3 | ✅ West Europe |
| Free credits at start | ✅ $300 / 90 days | ✅ $300 | ✅ ~$200 |
| Dev machine already set up | ✅ gcloud | — | — |

**AWS EKS is eliminated by the €2/month budget constraint alone** —
the control-plane fee exceeds the entire monthly budget 30×. That's
not a stylistic preference; it's arithmetic.

**AKS is a viable alternative**. We didn't pick it because AKS
Automatic is newer (more risk of API change during the portfolio's
lifetime) and the existing `gcloud`+DuckDNS tooling was already set
up. A fresh start today might reasonably pick AKS.

## Consequences

### Positive

- **Budget is reachable.** €2/month idle + €0.60 per demo is
  demonstrable on GCP, impossible on EKS.
- **ADR-0022 ephemeral pattern works natively.** GKE Autopilot boots
  in ~5 min, tears down cleanly via `terraform destroy`.
- **Budget alerts + Cloud Function budget-kill** (see
  [docs/ops/cost-control.md](../ops/cost-control.md)) rely on GCP
  Billing APIs. These APIs exist on AWS and Azure too but we've
  wired specifically to GCP's.
- **Workload Identity Federation** closes the "no long-lived keys"
  loop that ADR-0016 started.

### Negative

- **Vendor lock-in, specifically.** We depend on:
  - GCP Billing + Pub/Sub + Cloud Functions for the budget-kill.
  - WIF for CI auth.
  - GKE Autopilot's PodSecurity defaults.
  - Artifact Registry for image hosting (moderate lock, images are
    portable).
- **DuckDNS integration** is provider-agnostic but the A-record
  points at a GCP Cloud Load Balancer IP. Portable with an LB
  re-creation on another cloud.
- **A team already on AWS or Azure** has to either maintain a GCP
  account alongside or adapt. ADR-0029 (Jenkinsfile parity) shows
  the tooling is portable; cluster provisioning is not.

### Neutral

- **Cross-cloud migration** is out of scope. If that becomes a
  goal, Terraform (already used) + EKS/AKS modules would be the
  starting point. Budget assumptions in ADR-0022 would need
  rewriting.

## Revisit this when

- **AWS EKS drops its control-plane fee** (not likely given their
  pricing model).
- **Azure AKS Automatic stabilises and GCP Autopilot pricing
  doubles.** The €0.02/pod-hour rate is the load-bearing number.
- **A real team with a non-GCP mandate adopts the project.** At
  that point the ephemeral pattern + budget-kill need rewriting
  per that cloud's native equivalents.
- **The project gets real traffic and moves to always-on.** The
  budget math flips: EKS Standard is competitive once you're
  paying for nodes anyway; Autopilot's premium stops paying for
  itself.

## Alternatives considered

| Alternative | Why rejected (factually) |
|---|---|
| **AWS EKS** | $72/month CP fee blows the €2/month budget 30×. |
| **AKS Automatic** | Viable; rejected on newer-API risk + existing `gcloud` tooling. Pragmatic tie-break, not technical. |
| **Self-hosted k3s on a VPS** (€5/month) | Cheaper baseline but no WIF, no managed Postgres/Redis/Kafka in the same region, no Billing API — budget-kill pattern doesn't exist. Rebuilds a managed platform by hand. |
| **Bare Docker Compose in prod** (no k8s) | Loses the K8s story entirely — that's half the portfolio point (ADRs 0013/0014/0015/0022/0023/0028). |
| **Multi-cloud** | Out of scope for a portfolio demo. See ADR-0021 (cost-deferred industrial patterns) for the general "not now" principle. |
