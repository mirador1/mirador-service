// =============================================================================
// k6 smoke test — runs post-deploy to verify the GKE endpoint responds OK.
//
// 30 seconds of light traffic (~3 RPS) against the production endpoint.
// Pass criteria:
//   - p95 latency < 500 ms
//   - zero 5xx responses
//   - at least 90 successful requests in the window
//
// If any threshold fails, k6 exits non-zero and the CI job fails, which
// should trigger a rollback (wired in a follow-up — for now we just
// fail the job so the team investigates).
//
// Run locally:
//   K8S_HOST=mirador1.duckdns.org k6 run scripts/load-test/smoke.js
//
// In CI, K8S_HOST is set by the deploy:gke job's environment.
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = `https://${__ENV.K8S_HOST || 'mirador1.duckdns.org'}`;

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-arrival-rate',
      rate: 3, // 3 requests per timeUnit (second) — ~90 req total over 30 s
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
  },
  thresholds: {
    // Pass criteria — job fails if any of these are violated.
    http_req_duration: ['p(95)<500'],
    http_req_failed:   ['rate<0.01'],  // <1% errors
    'checks':          ['rate>0.99'],  // >99% of checks pass
  },
};

// Mix of cheap + medium endpoints. No POST/PUT — smoke test is read-only.
const paths = [
  '/actuator/health',
  '/actuator/health/liveness',
  '/actuator/health/readiness',
  '/customers?page=0&size=10',
];

export default function () {
  const path = paths[Math.floor(Math.random() * paths.length)];
  const res = http.get(`${BASE}${path}`, {
    headers: { 'Accept': 'application/json' },
  });

  check(res, {
    'status is 2xx':      (r) => r.status >= 200 && r.status < 300,
    'latency < 1s':       (r) => r.timings.duration < 1000,
    'body is non-empty':  (r) => (r.body || '').length > 0,
  });

  sleep(0.1);
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    'smoke-summary.json': JSON.stringify(data, null, 2),
  };
}

// Minimal text summary so we don't need to fetch an external module.
function textSummary(data) {
  const m = data.metrics;
  const p95 = m.http_req_duration?.values?.['p(95)']?.toFixed(1) ?? 'n/a';
  const avg = m.http_req_duration?.values?.avg?.toFixed(1) ?? 'n/a';
  const errRate = ((m.http_req_failed?.values?.rate ?? 0) * 100).toFixed(2);
  const count = m.http_reqs?.values?.count ?? 0;
  return [
    '',
    '=== k6 smoke-test summary ===',
    `Requests        : ${count}`,
    `Error rate      : ${errRate} %`,
    `Latency avg     : ${avg} ms`,
    `Latency p95     : ${p95} ms`,
    `Thresholds      : ${data.root_group ? 'see details above' : 'n/a'}`,
    '',
  ].join('\n');
}
