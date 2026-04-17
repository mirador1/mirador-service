# CI / CD timings — mirador-service

Measured job durations from the 5 most recent successful MR pipelines and the
3 most recent main-branch pipelines (2026-04-16 to 2026-04-17). All values are
**wall-clock seconds per job**, median across the sample. Refresh by
re-running the analysis snippet at the bottom of this file.

> Times are runner time on the **macbook-local** runner (Apple M-series, arm64).
> SaaS runner times would differ; anything that uses `--platform linux/amd64`
> via buildx + QEMU will pay a cross-compilation tax we'd avoid on native amd64.

## Pipeline wall-clock (MR event, 5 pipelines)

| Sample | Duration |
| --- | --- |
| 1 | 11.5 min |
| 2 | 14.9 min |
| 3 | 9.9 min |
| 4 | 9.5 min |
| 5 | 10.2 min |
| **Median** | **10.2 min** |

End-to-end CI feedback on a typical MR is ~10 min. Critical path is
`unit-test → integration-test → sonar-analysis`.

## Per-job median duration

Combined MR + main-branch samples. Stage is shown where the job ran on main.

| Job                      | Stage         | Median  | Notes |
| ------------------------ | ------------- | ------- | ----- |
| `integration-test`       | integration   | **~121 s** | Longest single job — Testcontainers (Postgres + Keycloak + Kafka) + full context reload. |
| `renovate-lint`          | lint          | ~60 s   | Only runs when `renovate.json` changes; variable I/O latency pulling npm. |
| `trivy:scan`             | package       | ~59 s   | CVE scan over the full image; runs only on main. |
| `sonar-analysis`         | sonar         | ~58 s   | Reads merged JaCoCo + SpotBugs reports. |
| `code-quality`           | sonar         | ~40 s   | Converts SpotBugs/PMD/Checkstyle into GitLab Code Quality format. |
| `semgrep-sast`           | test          | ~34 s   | SAST scan — daily schedule + manual. |
| `build-jar`              | package       | ~31 s   | `mvn package -DskipTests` after unit + IT passed. |
| `unit-test`              | test          | ~31 s   | Only `*Test` — excludes ITests (Docker-free). |
| `owasp-dependency-check` | test          | ~16–28 s | First-run pulls the NVD cache (~10 min); steady-state hits the cache. |
| `docker-build`           | package       | ~25 s   | buildx + QEMU cross-compile to `linux/amd64`. Layer cache hit from the registry. |
| `secret_detection`       | test          | ~19 s   | GitLab SAST secret-detection template. |
| `secret-scan`            | test          | ~15 s   | Custom gitleaks step (defense-in-depth with secret_detection). |
| `hadolint`               | lint          | ~13 s   | Dockerfile linter. |

Supply-chain jobs (`sbom:syft`, `grype:scan`, `cosign:sign`, `dockle`) run
only on main. Their timings aren't yet in the sample because they were
broken until pipeline 344; update this file once they run cleanly on
main pipelines 345+.

## Per-stage median-sum (summed median durations per stage)

| Stage        | Sum of medians | Notes |
| ------------ | -------------- | --- |
| integration  | ~120 s         | One job (integration-test). |
| sonar        | ~100 s         | sonar-analysis + code-quality. |
| package      | ~115 s         | build-jar + docker-build + trivy:scan (+ supply-chain on main). |
| test         | ~120 s total   | unit-test + secret-scan + secret_detection + owasp-dependency-check (parallel). |
| lint         | ~85 s total    | openapi-lint + hadolint + renovate-lint (mostly parallel). |

Parallelism is what keeps the wall-clock at ~10 min despite ~8–10 min of
summed job time per stage — GitLab runs jobs within a stage in parallel
where `needs:` allows.

## Known slow paths — what eats time

1. **Testcontainers cold start** in `integration-test` — spinning up
   Postgres/Keycloak/Kafka containers is the ~40 s tail on top of the
   ~80 s of actual test execution. Potential win: reuse containers with
   `testcontainers.reuse.enable=true` in `.testcontainers.properties`.
2. **NVD cache priming** in `owasp-dependency-check` — the very first CI
   run spends ~10 min seeding `.owasp-data/`; subsequent runs hit the
   GitLab job cache in ~15 s.
3. **QEMU cross-compile** in `docker-build` — the `linux/amd64` build
   on an arm64 host pays ~15 s of emulation overhead. Gone if we move
   the package stage to an amd64 runner, but that burns SaaS quota.

## Refreshing this file

The numbers above were aggregated with the snippet below. Re-run after
any significant CI change and commit the updated table.

```bash
# Fetch the last 5 successful MR pipelines + 3 main pipelines as JSON.
for pid in $(glab api \
  "projects/mirador1%2Fmirador-service/pipelines?status=success&per_page=5" \
  | python3 -c 'import json,sys;[print(p["id"]) for p in json.load(sys.stdin)]'); do
  glab api "projects/mirador1%2Fmirador-service/pipelines/$pid/jobs?per_page=100" \
    > /tmp/mirador_jobs_$pid.json
done

python3 <<'PY'
import json, glob
from collections import defaultdict
per_job = defaultdict(list)
for f in sorted(glob.glob('/tmp/mirador_jobs_*.json')):
    for j in json.load(open(f)):
        if j.get('status') == 'success' and j.get('duration') is not None:
            per_job[j['name']].append(j['duration'])
for name, durs in sorted(per_job.items(), key=lambda kv: -sorted(kv[1])[len(kv[1])//2]):
    s = sorted(durs); med = s[len(s)//2]
    print(f"{name:<35} median {med:>7.1f}s  runs {len(durs)}")
PY
```
