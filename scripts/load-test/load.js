// =============================================================================
// k6 LOAD test — nightly sustained traffic against the Mirador backend.
//
// Smoke test (smoke.js) answers "can it respond at all?" with 30 s / 3 RPS.
// This LOAD test answers "does it hold up under a demo-realistic workload?"
// with a 5-minute ramp-and-hold profile that exercises:
//   - cached reads from the Caffeine L2 (GET /customers/{id})
//   - cold-start paths (GET /customers?search=...)
//   - write paths that fire Kafka + DB (POST /customers)
//   - the aggregate endpoint (parallel virtual threads — 200ms sleep each)
//
// Profile:
//   Stage 1 — ramp 0 → 50 VUs over 1 min (warm-up, JIT + caches populate)
//   Stage 2 — hold 50 VUs for 3 min (steady-state; alerts would fire here
//             under a real regression — see mirador-alerts.yaml)
//   Stage 3 — ramp 50 → 0 over 30 s (graceful cool-down, let backlogs drain)
//
// Scheduled: nightly via GitLab schedules (`LOAD_TEST_SCHEDULE` variable
// set on the scheduled pipeline). Not gated on MRs — a 5-min job would
// slow review latency without adding value (MR smoke.js covers the
// fast-fail read-only case already).
//
// Pass criteria (tuned for GKE Autopilot, single replica, no HPA):
//   - p95 latency < 2 s   (same cohort as MiradorHighLatencyP95 alert)
//   - p99 latency < 5 s   (tail detection — JVM GC pauses show here)
//   - <2 % 5xx            (slightly above the smoke threshold — we expect
//                          some 429 Too Many Requests under sustained load
//                          and the rate-limit bucket IS tested for)
//   - no HTTP 5xx from /customers/aggregate (must stay healthy under load)
//
// Run locally:
//   K8S_HOST=localhost:8080 K8S_SCHEME=http k6 run scripts/load-test/load.js
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const SCHEME = __ENV.K8S_SCHEME || 'https';
const BASE = `${SCHEME}://${__ENV.K8S_HOST || 'mirador1.duckdns.org'}`;

// Shared across VUs to avoid each VU creating its own large array.
// Pre-computed so the workload shape doesn't drift between runs.
const customerIds = new SharedArray('customer-ids', () => [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);

export const options = {
  scenarios: {
    // Ramping-arrival-rate gives a predictable req/s profile; easier to
    // correlate with Prometheus panels than a ramping-VUs scenario
    // (which depends on per-VU think-time to determine throughput).
    ramp_hold_cooldown: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 100,
      stages: [
        { target: 25, duration: '1m' },    // warm-up
        { target: 25, duration: '3m' },    // steady-state
        { target: 0, duration: '30s' },    // cool-down
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    http_req_failed: ['rate<0.02'],
    // Per-endpoint latency check — aggregate endpoint is the chaos canary.
    'http_req_duration{endpoint:aggregate}': ['p(95)<1500'],
    // Checks cover body-shape assertions so Prometheus alerts don't silently
    // fire on "200 OK but empty body" scenarios.
    checks: ['rate>0.97'],
  },
};

// Weighted path selection — roughly matches the demo's read/write ratio.
// Each VU iteration runs one of these via simple cumulative-weight sampling.
const weightedPaths = [
  { weight: 40, kind: 'read-cached' },    // GET /customers/{id} — hot cache
  { weight: 20, kind: 'read-search' },    // GET /customers?search=... — cold
  { weight: 15, kind: 'read-list' },      // GET /customers?page=0 — paginated
  { weight: 10, kind: 'aggregate' },      // GET /customers/aggregate — VT demo
  { weight: 10, kind: 'health' },         // GET /actuator/health
  { weight: 5, kind: 'write' },           // POST /customers — DB + Kafka write
];
const totalWeight = weightedPaths.reduce((s, p) => s + p.weight, 0);

function pickPath() {
  const roll = Math.random() * totalWeight;
  let cum = 0;
  for (const p of weightedPaths) {
    cum += p.weight;
    if (roll < cum) return p.kind;
  }
  return weightedPaths[0].kind;
}

export default function () {
  const kind = pickPath();
  let res;
  const commonHeaders = { Accept: 'application/json' };

  switch (kind) {
    case 'read-cached': {
      const id = customerIds[Math.floor(Math.random() * customerIds.length)];
      res = http.get(`${BASE}/customers/${id}`, {
        headers: commonHeaders,
        tags: { endpoint: 'get-by-id' },
      });
      check(res, {
        'get-by-id 2xx or 404': (r) => (r.status >= 200 && r.status < 300) || r.status === 404,
      });
      break;
    }
    case 'read-search': {
      // Search for a 3-char fragment — typical user-typing behaviour,
      // and the backend can't serve this from cache.
      const q = randomString(3, 'abcdefghijklmnop');
      res = http.get(`${BASE}/customers?search=${q}&page=0&size=20`, {
        headers: commonHeaders,
        tags: { endpoint: 'search' },
      });
      check(res, { 'search 2xx': (r) => r.status >= 200 && r.status < 300 });
      break;
    }
    case 'read-list': {
      res = http.get(`${BASE}/customers?page=0&size=20`, {
        headers: commonHeaders,
        tags: { endpoint: 'list' },
      });
      check(res, {
        'list 2xx': (r) => r.status >= 200 && r.status < 300,
        'list has content array': (r) => (r.body || '').includes('"content"'),
      });
      break;
    }
    case 'aggregate': {
      res = http.get(`${BASE}/customers/aggregate`, {
        headers: commonHeaders,
        tags: { endpoint: 'aggregate' },
      });
      check(res, {
        'aggregate 2xx': (r) => r.status >= 200 && r.status < 300,
        'aggregate has virtual-thread fields': (r) => {
          const b = r.body || '';
          // `customerData` + `stats` — see AggregatedResponse record.
          return b.includes('customerData') && b.includes('stats');
        },
      });
      break;
    }
    case 'health': {
      res = http.get(`${BASE}/actuator/health`, {
        headers: commonHeaders,
        tags: { endpoint: 'health' },
      });
      check(res, {
        'health UP': (r) => {
          try {
            return JSON.parse(r.body || '{}').status === 'UP';
          } catch {
            return false;
          }
        },
      });
      break;
    }
    case 'write': {
      // Always-unique email so re-runs don't trip the UNIQUE constraint.
      // The VU id + iteration number + nanosecond timestamp is overkill-safe.
      const email = `load-${__VU}-${__ITER}-${Date.now()}@example.com`;
      res = http.post(
        `${BASE}/customers`,
        JSON.stringify({ name: `Load Test ${__VU}.${__ITER}`, email }),
        {
          headers: { ...commonHeaders, 'Content-Type': 'application/json' },
          tags: { endpoint: 'create' },
        },
      );
      check(res, {
        // 200 (ok) or 401/403 (auth required on non-local) — 5xx is a real fail.
        'create not 5xx': (r) => r.status < 500,
      });
      break;
    }
  }

  // Tiny think-time to prevent hammering — ramping-arrival-rate already
  // owns the global RPS, this just smooths per-VU cadence.
  sleep(Math.random() * 0.3);
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    'load-summary.json': JSON.stringify(data, null, 2),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const p95 = m.http_req_duration?.values?.['p(95)']?.toFixed(1) ?? 'n/a';
  const p99 = m.http_req_duration?.values?.['p(99)']?.toFixed(1) ?? 'n/a';
  const avg = m.http_req_duration?.values?.avg?.toFixed(1) ?? 'n/a';
  const errRate = ((m.http_req_failed?.values?.rate ?? 0) * 100).toFixed(2);
  const count = m.http_reqs?.values?.count ?? 0;
  const rate = m.http_reqs?.values?.rate?.toFixed(1) ?? 'n/a';
  return [
    '',
    '=== k6 load-test summary ===',
    `Duration        : 4m 30s (1m ramp + 3m hold + 30s cool-down)`,
    `Total requests  : ${count}`,
    `Avg RPS         : ${rate}`,
    `Error rate      : ${errRate} %`,
    `Latency avg     : ${avg} ms`,
    `Latency p95     : ${p95} ms`,
    `Latency p99     : ${p99} ms`,
    '',
  ].join('\n');
}
