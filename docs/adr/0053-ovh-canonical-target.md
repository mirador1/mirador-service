# ADR-0053 — OVH Cloud as 2nd canonical Kubernetes target

- **Status**: Accepted
- **Date**: 2026-04-23
- **Amends**: [ADR-0036](0036-multi-cloud-terraform-posture.md)
  (which originally listed OVH under "not scaffolded")
- **Related**:
  [ADR-0030](0030-choose-gcp-as-the-kubernetes-target.md),
  [ADR-0007](0007-workload-identity-federation.md),
  [ADR-0022](0022-ephemeral-demo-cluster.md)

## Context

[ADR-0030](0030-choose-gcp-as-the-kubernetes-target.md) picked **GCP
(GKE Autopilot) as the canonical Kubernetes target** based on cost
(AWS EKS ruled out by the €72/month control-plane fee) and operational
simplicity (Autopilot — Google-managed node pool — kills the YAML
overhead of self-managed nodes).

[ADR-0036](0036-multi-cloud-terraform-posture.md) then established
the *reference modules* posture : ship Terraform code (the
infrastructure-as-code language Mirador uses) for AWS / Azure /
Scaleway as **read-only** examples that pass `terraform validate`
but never deploy. Crucially, **OVH was excluded from that list**, on
the grounds that "OVH ≈ Scaleway" — same EU-sovereign narrative
(both French-owned, both data centres in EU jurisdiction), so adding
both would dilute the signal-to-noise of the reference modules.

That exclusion was correct under the *reference* mental model. It is
incorrect under the *delivery* mental model that this ADR introduces.

### What changed

Two things, actually :

1. **Sovereignty pressure point.** The motivation to deploy on a
   French-jurisdiction sovereign cloud has gone from "narrative" to
   "we want it to actually work". A reference module is not enough —
   the project needs `terraform apply` capability targeting an
   EU-sovereign provider, with CI integration, observability overlay,
   cost tracking, and lifecycle scripts.

2. **HDS as a real differentiator.** OVH holds **HDS certification**
   (Hébergeur de Données de Santé — French regulatory certification
   required to host health-related personal data). Scaleway does NOT.
   For any future use case touching health data (a not-improbable
   evolution given Mirador's "observability for serious workloads"
   positioning), OVH would be the *only* viable French-jurisdiction
   option. ADR-0036's "OVH ≈ Scaleway" equivalence collapses on
   this single regulatory axis.

## Decision

**Promote OVH from "not scaffolded" (ADR-0036's exclusion) to a
2nd canonical-tier delivery target alongside GCP.**

This means a *full* delivery module, not a reference module :

| Aspect | Reference (Scaleway / AWS / Azure) | Canonical (GCP, +OVH per this ADR) |
|---|---|---|
| Terraform module | exists, validates | exists, validates |
| `terraform apply` | never run | runs in CI (manual gate first) |
| K8s overlay | none | `deploy/kubernetes/overlays/<cloud>-prom/` |
| Cost tracking script | none | `bin/budget/<cloud>-cost-audit.sh` |
| Lifecycle scripts | none | `bin/cluster/<cloud>-{up,down}.sh` |
| Observability stack | bring-your-own | full LGTM (Loki/Grafana/Tempo/Mimir) |
| README depth | architectural notes | runbook-grade (apply / destroy / debug) |

GCP stays the **default** (the demo `bin/cluster/up.sh` keeps
defaulting to GCP). OVH is an **alternative** that's first-class —
the same `up.sh` learns a `--cloud=ovh` flag.

## Why this isn't a violation of ADR-0036's "signal-to-noise" argument

ADR-0036's exclusion of OVH was framed as : *"DigitalOcean ≈
Scaleway, OVH ≈ Scaleway, Oracle ≈ AWS — adding more reference
modules dilutes the signal without teaching anything new."*

That argument applies to **reference** modules — read-only examples
where the reader's value is "understand the pattern across clouds".
On that axis, yes, OVH and Scaleway look similar.

But this ADR moves OVH to the **canonical** tier, where the reader's
value is *"I want my Mirador deploy to land on this cloud"*. The
"new thing taught" is HDS-grade French sovereignty, which Scaleway
cannot teach.

The reference-module argument therefore stands for AWS / Azure /
Scaleway — those remain at the reference tier, no apply path. OVH is
the exception because of HDS.

## Tooling — Terraform by default, OpenTofu opt-in

**Decided 2026-04-23 alongside this ADR.** The OVH module (and every
other module under `deploy/terraform/`) MUST be dual-compatible with
both Terraform and OpenTofu. The default tool stays **Terraform 1.9.8**
(no migration friction for the existing GCP module) ; running with
**OpenTofu** is a one-flag opt-in (`TF_BIN=tofu`).

### Why dual-compat instead of picking one

- **Terraform-only** keeps Mirador on the BSL licence (Business
  Source License — open-source-ish but HashiCorp/IBM can revoke usage
  rights for competitive products), which weakens the souveraineté
  narrative the OVH addition explicitly chases.
- **OpenTofu-only** forces a tool migration on the existing GCP
  module (small but not free) and disrupts any reader's mental model
  ("why did you replace `terraform` with `tofu` in all the docs ?").
- **Dual-compat** keeps the default frictionless AND lets a sovereignty-
  conscious reviewer / contributor run with the MPL-2.0 (Mozilla Public
  License — fully open) tool by setting one env var.

### How dual-compat is enforced

1. **HCL stays vendor-neutral.** No `terraform { cloud {} }` block
   (Terraform-only). No `tofu` provider feature (OpenTofu-only).
   Same `terraform { required_providers {} }` block both tools accept.
2. **Scripts use `TF_BIN`.** `bin/cluster/<cloud>-up.sh` and the
   demo helpers read `TF_BIN="${TF_BIN:-terraform}"` — default
   terraform, override with `export TF_BIN=tofu`.
3. **`.mise.toml` installs both.** `terraform = "1.9.8"` (default
   on PATH) AND `opentofu = "1.8.4"` (installed, runnable as `tofu`).
   No conflict — they coexist.
4. **CI proves dual-compat.** `.gitlab-ci/terraform.yml` runs
   `terraform-plan-<cloud>` (default tool) AND a parallel
   `terraform-plan-<cloud>-tofu` job (same module, `TF_BIN=tofu`).
   If one passes and the other fails, we have a real divergence to
   investigate. If both pass, dual-compat is proven on every MR.
5. **Documentation says both.** `deploy/terraform/README.md` and
   each module's README include a "Running with OpenTofu" section :
   `export TF_BIN=tofu ; bin/cluster/ovh-up.sh --apply`.

The CI cost is minimal (~+1 min per MR for the OpenTofu plan) and
the value is high : we catch tool divergence the day it ships, not
the day a sovereignty reviewer tries `tofu apply` and discovers it
doesn't work.

## What gets built (delivery checklist)

Tracked in the [Phase OVH section of TASKS.md](../../TASKS.md). One
MR bundles the lot :

- ADR-0053 (this file) + ADR-0036 amendment footer
- `deploy/terraform/ovh/` — module canonical-grade (main.tf,
  network.tf, variables.tf, outputs.tf, README.md)
- `deploy/kubernetes/overlays/ovh-prom/` — mirror of `gke-prom/`,
  with OVH-specific patches (LoadBalancer annotations, default
  StorageClass, ingress controller class)
- `bin/budget/ovh-cost-audit.sh` — query OVH `/me/order` API for
  current month spend ; integrate into `bin/budget/budget.sh`
- `bin/cluster/ovh-up.sh` + `ovh-down.sh` — analogous to GCP
- `.gitlab-ci/terraform.yml` — `terraform-plan-ovh` (auto on MR
  touching `deploy/terraform/ovh/`) + `terraform-apply-ovh`
  (`when: manual`, gated on env vars `OVH_*`)
- `.env.example` — `OVH_APPLICATION_KEY` /
  `OVH_APPLICATION_SECRET` / `OVH_CONSUMER_KEY` /
  `OVH_PROJECT_ID` documented with the credential creation URL
  `https://eu.api.ovh.com/createToken/`
- `README.md` (top-level + `deploy/terraform/README.md`) — list OVH
  alongside GCP under "Multi-cloud delivery"

## Consequences

### Positive

- **Sovereignty story is end-to-end.** Not just "we have a Scaleway
  reference module that validates" — but "you can `bin/cluster/ovh-up.sh
  --apply` and your demo runs on a French-jurisdiction cluster in 15
  minutes". Demonstrable, not asserted.
- **HDS-readiness pre-positioned.** The module template already names
  the placement-group / region (e.g. SBG5 in Strasbourg) that's
  HDS-eligible. Adding HDS-attesting metadata is a follow-up MR, not
  a re-architecture.
- **CI catches OVH-side breakage.** Provider-major upgrades (ovhcloud
  TF provider v0 → v1) ship breaking changes ; without a CI plan,
  reference modules silently rot. This module gets the same
  `terraform validate` + `plan` rigor as GCP.

### Negative

- **+~€25/month.** OVH Managed K8s on the smallest node (B2-7) costs
  ~€25/month. Combined with GCP's €2-5 ephemeral cluster (per
  ADR-0022), the project cap of €10/month gets blown if both run at
  once. Mitigation : the `ovh-up.sh` script defaults to apply-on-demand
  (no scheduled apply), and CI's `terraform-apply-ovh` is `when:
  manual`. The cost-audit cron adds an alert at €5 spend so a
  forgotten cluster doesn't drift.
- **More CI surface.** The `.gitlab-ci/terraform.yml` grows from
  GCP-only to GCP+OVH plan/apply jobs. Mitigation : the OVH apply
  job stays manual until first-credentials wiring, so day-to-day MR
  pipelines don't change.
- **Skill divergence on the team.** Anyone touching the `ovh/`
  module needs a baseline understanding of OVH's Public Cloud
  console (different mental model from GCP's project / IAM). The
  README runbook is the mitigation : it walks through the first
  apply step-by-step.

### Neutral

- **No change to the default deploy.** `bin/cluster/up.sh` (called
  by the demo) keeps targeting GCP. OVH is opt-in via `--cloud=ovh`.

## Alternatives considered

### Alternative A — keep ADR-0036 as-is (OVH stays excluded)

**Pros** : zero new work, ADR-0036's signal-to-noise argument stays
clean.
**Cons** : the sovereignty motivation goes unanswered. Anyone
asking "can I run Mirador on a French sovereign cloud where I can
also host health data ?" gets "build it yourself".
**Why rejected** : the question is concrete enough (and the answer
takes ~10h to ship) that not having an answer is the more expensive
option.

### Alternative B — promote Scaleway to canonical instead of OVH

**Pros** : Scaleway is already scaffolded. The promotion would be
"ship the missing pieces" rather than "build the whole thing".
**Cons** : Scaleway lacks HDS. The whole point is having a
French-jurisdiction option that handles health-data scenarios. A
sovereignty story without HDS is incomplete.
**Why rejected** : the regulatory axis (HDS) is the actual
differentiator, and Scaleway doesn't satisfy it. Promoting Scaleway
would be a smaller diff but the wrong cloud.

### Alternative C — promote BOTH OVH and Scaleway to canonical

**Pros** : two French sovereign options, broader story.
**Cons** : the running cost doubles (≈€50/month combined when both
demo clusters run), the maintenance surface doubles, and Scaleway's
unique value (cost — it's the cheapest of the four reference
modules) is irrelevant for canonical-tier deploys.
**Why rejected** : OVH's HDS is the strict superset of Scaleway's
French-jurisdiction value for canonical purposes. Keep Scaleway at
the reference tier.

### Alternative D — full multi-cloud, all five at canonical (GCP +
AWS + Azure + Scaleway + OVH)

**Pros** : maximal portability story.
**Cons** : the project cap is €10/month. Five canonical clouds is a
500% cost blow-out for a portfolio demo. Also dilutes the *demo* :
"Mirador can run anywhere" is less compelling than "Mirador's
default is GCP, with a French-sovereign HDS-ready option".
**Why rejected** : disproportionate cost + diluted narrative.

## References

- [ADR-0030](0030-choose-gcp-as-the-kubernetes-target.md) — GCP as
  canonical (still holds, OVH joins it).
- [ADR-0036](0036-multi-cloud-terraform-posture.md) — original
  multi-cloud posture (now amended by this ADR for the OVH case).
- [ADR-0022](0022-ephemeral-demo-cluster.md) — €10/month project
  cap that bounds the "consequences" cost analysis.
- [ADR-0007](0007-workload-identity-federation.md) — auth pattern
  to mirror : OVH equivalent is OVH IAM tokens (no WIF — a known gap
  documented in the OVH module README).
- [Phase OVH in TASKS.md](../../TASKS.md) — delivery checklist.
- [https://www.ovhcloud.com/fr/enterprise/certification-conformity/hds/](https://www.ovhcloud.com/fr/enterprise/certification-conformity/hds/)
  — OVH HDS certification reference.
