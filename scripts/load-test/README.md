# `scripts/load-test/` — k6 smoke + load tests

[k6](https://k6.io) is a Go-based load-testing tool with JavaScript test
scripts. Used here for two complementary profiles:

| Script     | Purpose                                                              | Invoked by                  |
| ---------- | -------------------------------------------------------------------- | --------------------------- |
| `smoke.js` | 30 s, ~3 RPS, read-only. Pass/fail on p95 < 500 ms + error rate < 1%. | `.gitlab-ci.yml` `smoke-test` (post-deploy, manual on main) |
| `load.js`  | 4 m 30 s (1 m ramp + 3 m hold + 30 s cool-down) at 25 arrivals/s, read + write mix incl. `/customers/aggregate` virtual-threads demo. Pass/fail on p95 < 2 s, p99 < 5 s, error rate < 2 %. | `.gitlab-ci.yml` `load-test:nightly` (scheduled, $LOAD_TEST=true) |

The split follows standard perf-engineering practice: smoke is a
per-deploy quick-check; load is a nightly trend-watcher. Together
they answer "does it respond?" (smoke) and "does it hold up?" (load)
without either being too slow to block an MR or too superficial to
catch regressions.

## Run locally

```bash
# Smoke test against production
K8S_HOST=mirador1.duckdns.org k6 run scripts/load-test/smoke.js

# Against local (run the service first with ./run.sh all)
K8S_HOST=localhost:8080 K8S_SCHEME=http k6 run scripts/load-test/smoke.js

# Load test — against local (takes 4.5 min; against prod, see CI only)
K8S_HOST=localhost:8080 K8S_SCHEME=http k6 run scripts/load-test/load.js
```

Each run produces a text summary + a JSON artefact
(`smoke-summary.json` or `load-summary.json`) — machine-readable for
CI parsing or historical diff.

## Install k6

```bash
brew install k6                    # macOS
sudo apt install k6                # Debian/Ubuntu
```

Or pinned in CI via `mise` (see `.mise.toml`).

## Thresholds

### `smoke.js` — per-deploy fast-fail

| Threshold           | Value        | Why                                    |
| ------------------- | ------------ | -------------------------------------- |
| `http_req_duration` | `p(95)<500`  | Matches our smoke-SLO (p95 under 500 ms) |
| `http_req_failed`   | `rate<0.01`  | <1 % errors — any more = deploy broken |
| `checks`            | `rate>0.99`  | >99 % of assertions pass               |

### `load.js` — nightly trend-watcher

| Threshold                                  | Value          | Why                                               |
| ------------------------------------------ | -------------- | ------------------------------------------------- |
| `http_req_duration`                        | `p(95)<2000`   | Same band as `MiradorHighLatencyP95` alert        |
| `http_req_duration`                        | `p(99)<5000`   | Tail detection — JVM GC pauses surface here       |
| `http_req_failed`                          | `rate<0.02`    | Slightly above smoke: some 429 expected under load |
| `http_req_duration{endpoint:aggregate}`    | `p(95)<1500`   | Virtual-threads demo must stay healthy under load |
| `checks`                                   | `rate>0.97`    | Body-shape assertions (catches "200 OK empty body") |

If any threshold fails, k6 exits non-zero and the job goes red. Per
ADR-0048 there's no alerting pipeline — a failed load-test:nightly is
caught on next-morning review of `glab ci list`.

## Future scripts

- `spike.js` — sudden burst of traffic to probe rate-limit behaviour
  (429 handling, circuit-breaker fire+recover).
- `kafka-enrich.js` — drive the async request-reply pipeline end-to-end
  (produce `customer.request`, await `customer.reply`).
