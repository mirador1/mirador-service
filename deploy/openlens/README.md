# OpenLens preferences — golden reference

OpenLens stores per-cluster preferences in a host-local file:

```
~/Library/Application Support/OpenLens/lens-cluster-store.json
```

That file is gitignored (host-specific IDs, paths, lastSeen timestamps).
This directory keeps the **golden reference** of what the
`kind-mirador-local` cluster's `preferences` block MUST contain so the
Metrics tab works.

## Files

- [`lens-cluster-store-snippet.json`](lens-cluster-store-snippet.json) —
  the two valid `preferences` blocks (`_OptionA_lgtmOnly` and
  `_OptionB_kubePromStack`) plus a comment block explaining the
  provider-name gotchas.

## Apply

```bash
bin/cluster/openlens/prometheus-config.sh
```

The script:
1. Detects which observability overlay is currently applied
   (`local/` → lgtm-only, `local-prom/` → kube-prom-stack).
2. Writes the matching block from the snippet into the live OpenLens
   store.
3. Prints the restart command:
   `osascript -e 'quit app "OpenLens"' && sleep 2 && open -a OpenLens`

## Why this exists

OpenLens's "Metrics" tab fails silently with
`Metrics are not available due to missing or invalid Prometheus configuration`
when the `prometheusProvider.type` doesn't match the actual stack.
Without a documented config, debugging takes ~1h of cycling through
provider options. This snippet collapses that to seconds.

## Provider name gotchas

The OpenLens UI dropdown labels are user-friendly but the JSON values
differ:

| UI label | JSON `type` value | When to use |
|---|---|---|
| Auto | `auto` | Try first if you don't know |
| Lens | `lens` | Generic Prometheus URL — works with any `/api/v1/query` endpoint (including our lgtm Mimir) |
| Helm | `helm` | Lens's own helm chart (not us) |
| Prometheus Operator | `operator` | kube-prometheus-stack (our `local-prom/` overlay) |
| Stacklight | `stacklight` | Mirantis stacklight (not us) |

Empirical verification (2026-04-21):
- `provider=prometheus` (not in the list above — entered manually): returned `metadata.prometheus.success: false`.
- `provider=operator`: ✓ Metrics tab populates immediately for kube-prom-stack.
- `provider=lens` + lgtm Mimir at `:9009`: ✓ basic graphs work but limited metric coverage (OTel naming).

## See also

- ADR-0038 — kubeletstats receivers in lgtm (lgtm-only stack).
- ADR-0039 — two observability deployment modes (lgtm vs kube-prom-stack).
- `bin/cluster/openlens/prometheus-config.sh` — the apply script.
