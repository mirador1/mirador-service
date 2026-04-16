# `scripts/load-test/` — k6 load + smoke tests

[k6](https://k6.io) is a Go-based load-testing tool with JavaScript test
scripts. Used here for two things:

| Script     | Purpose                                                              | Invoked by                  |
| ---------- | -------------------------------------------------------------------- | --------------------------- |
| `smoke.js` | 30 s, ~3 RPS, read-only. Pass/fail on p95 < 500 ms + error rate < 1%. | `.gitlab-ci.yml` `smoke-test` (post-deploy) |

## Run locally

```bash
# Smoke test against production
K8S_HOST=mirador1.duckdns.org k6 run scripts/load-test/smoke.js

# Against local (run the service first with ./run.sh all)
K8S_HOST=localhost:8080 k6 run scripts/load-test/smoke.js
```

Output includes a text summary + a `smoke-summary.json` artefact
(machine-readable — useful for parsing in CI).

## Install k6

```bash
brew install k6                    # macOS
sudo apt install k6                # Debian/Ubuntu
```

Or pinned in CI via `mise` (see `.mise.toml`).

## Thresholds

Configured inside `smoke.js` via k6's `options.thresholds` block:

| Threshold          | Value          | Why                                    |
| ------------------ | -------------- | -------------------------------------- |
| `http_req_duration` | `p(95)<500`   | Matches our SLO (p95 under 500 ms)    |
| `http_req_failed`   | `rate<0.01`   | <1 % errors — any more = deploy broken |
| `checks`            | `rate>0.99`   | >99 % of assertions pass               |

If any threshold fails, k6 exits non-zero and CI marks the job failed.
Follow-up plan: wire a `kubectl rollout undo` on smoke-test failure.

## Future scripts

- `load-stress.js` — sustained load (100+ RPS) to verify HPA scaling.
- `spike.js` — sudden burst of traffic to probe rate-limit behaviour.
- `kafka-enrich.js` — drive the async request-reply pipeline.
