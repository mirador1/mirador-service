# `ovh-prom/` overlay — OVH Managed Kubernetes deployment

> **Per [ADR-0053](../../../docs/adr/0053-ovh-canonical-target.md)**, OVH joins GCP at the canonical-tier delivery target list. This overlay is the K8s side of that decision (the Terraform side is [`deploy/terraform/ovh/`](../../../deploy/terraform/ovh/)).

## What this deploys

Same stack as [`gke-prom/`](../gke-prom/) (the GCP equivalent) :

- Mirador app (Spring Boot + Postgres in-cluster)
- LGTM observability bundle (Grafana + Loki + Tempo + Mimir + OTel Collector)
- kube-prometheus-stack (Prometheus + node-exporter + kube-state-metrics)
- Mirador ServiceMonitor + alert rules

Most files are reused from `gke-prom/` via relative-path `resources:` entries — the chart itself is cloud-neutral, only storage + load-balancer binding differ.

## OVH-specific deltas vs `gke-prom/`

| Thing | GKE | OVH | Why |
|---|---|---|---|
| **Default StorageClass** | `standard-rwo` (Balanced PD) | `csi-cinder-high-speed` (Cinder) | Different CSI drivers. The chart uses `storageClassName: ""` (= cluster default) so no patch needed — OVH's Cinder is automatic. |
| **LoadBalancer cost** | ~€20/month per LB on GKE | **~€20/month (Classic) OR ~€100/month (Premium)** if no annotation | The `lgtm-loadbalancer-ovh-patch.yaml` pins `Classic` to dodge the silent €100/month default. |
| **Ingress controller** | GKE Autopilot pre-installs `gke-l7-default` | NOT pre-installed — bring your own | Demo prefers `kubectl port-forward`; production would `kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml`. |
| **Workload Identity** | GKE Workload Identity Federation | NOT supported on OVH (long-lived API tokens only) | Documented as a known gap in [ADR-0053](../../../docs/adr/0053-ovh-canonical-target.md#references). |

## Apply

```bash
# 1. Bring up the cluster (terraform — see ../../../bin/cluster/ovh/up.sh)
bin/cluster/ovh/up.sh

# 2. Deploy this overlay (server-side because chart CRDs > 262 KiB
#    annotation limit)
export KUBECONFIG=~/.kube/ovh-mirador.yaml
kubectl apply --server-side --force-conflicts \
  -k deploy/kubernetes/overlays/ovh-prom

# 3. Access Grafana via port-forward (no LB cost)
kubectl port-forward -n observability svc/lgtm 3000:3000
# Open http://localhost:3000 (default admin / admin)

# 4. Tear down when done (stops paying for nodes + storage)
bin/cluster/ovh/down.sh
```

## Cost (per ADR-0053)

| Resource | Monthly |
|---|---|
| 1× B2-7 node (always-on) | **€25.20** |
| 10 GiB persistent volume (Cinder) | **€0.40** |
| Public LoadBalancer (Classic, optional) | **+€20** if exposed |
| **Total at min, no LB** | **~€26/month** |
| **Total with LB** | **~€46/month** |

⚠️ Above the €10/month cap from [ADR-0022](../../../docs/adr/0022-ephemeral-demo-cluster.md) — accepted overhead per [ADR-0053](../../../docs/adr/0053-ovh-canonical-target.md#consequences). Apply only when actively in use ; monitor with `bin/budget/budget.sh ovh`.

## Maintenance — chart updates

The kube-prometheus-stack files (`kube-prom-stack-crds.yaml` + `kube-prom-stack-rendered.yaml`) live in `gke-prom/` to avoid duplication. Both overlays reference them via relative path. When updating the chart :

1. Re-render in `gke-prom/` (`helm template kube-prom-stack ...` — the comment in `gke-prom/values-kube-prom-stack.yaml` has the exact command).
2. Re-apply BOTH overlays to verify no regression.
3. The OVH overlay needs no extra attention as long as the chart stays cloud-neutral (which it does today).

## Related

- [ADR-0053](../../../docs/adr/0053-ovh-canonical-target.md) — OVH as canonical 2nd target
- [ADR-0036](../../../docs/adr/0036-multi-cloud-terraform-posture.md) — multi-cloud Terraform posture (amended for OVH)
- [`deploy/terraform/ovh/`](../../../deploy/terraform/ovh/) — Terraform module that provisions the cluster
- [`bin/cluster/ovh/up.sh`](../../../bin/cluster/ovh/up.sh) — wraps terraform apply + kubeconfig + node-Ready wait
- [`bin/budget/ovh-cost-audit.sh`](../../../bin/budget/ovh-cost-audit.sh) — monthly spend monitor
- [`gke-prom/`](../gke-prom/) — GCP sibling overlay (most files reused via relative path)
