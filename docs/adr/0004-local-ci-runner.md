# ADR-0004: Local CI runner, no paid SaaS quota

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

GitLab.com SaaS runners cost money per CI minute. The default
`saas-linux-medium-amd64` shared runners burn through the free tier fast
on a Maven + Docker pipeline that runs on every push to `dev` and `main`.

We have an **Apple Silicon Mac mini (arm64)** on an always-on home
network that can run a GitLab runner 24/7 for the cost of ~5 W electricity.

## Decision

Every CI job tagged with `macbook-local` runs on the self-hosted
Apple Silicon runner. SaaS runners are **not** used.

Cross-architecture builds (local is arm64, GKE is amd64) use
**Docker buildx + QEMU** to emulate amd64 from the arm64 host:

```
docker buildx build --platform linux/amd64 -t ... .
```

We explicitly avoid Kaniko, because Kaniko does **not** cross-compile —
it runs on the host arch.

For jobs that absolutely need amd64 hardware (a tiny set: none today),
we would switch back to SaaS. So far we haven't found such a job.

## Consequences

### Positive

- Zero CI minute cost on GitLab.com plan.
- Faster feedback on Maven + test jobs (local disk cache, warm JVM,
  `~/.m2` persisted across runs).
- No paid quota alerts during a late-night debugging session.

### Negative

- Runner availability depends on the home network and the Mac mini being
  up. A power cut kills the pipeline.
- Runner security: it has a GitLab CI registration token. The host
  should not run anything else privileged. Runner is pinned to a
  dedicated user with no sudo.
- Apple Silicon ≠ GKE target arch → every `docker build` MUST pass
  `--platform linux/amd64` or the image will crash on deploy with
  `exec format error`. This is captured in global CLAUDE.md and
  verified by the `.gitlab-ci.yml` Docker buildx invocation.

### Neutral

- All CI secrets (GitLab PAT, Sonar token, Grafana OTLP auth) are masked
  and protected group variables — they don't live on the runner.

## Alternatives considered

### Alternative A — SaaS runners only

Too expensive once per-pipeline time exceeds ~10 minutes.

### Alternative B — Dedicated VPS (DigitalOcean, Hetzner)

Rejected: would cost $5-15/month, slower than the local Mac mini for
Maven builds (cache invalidation across runs), and adds another machine
to patch.

### Alternative C — GitHub Actions instead of GitLab CI

Rejected: we're on GitLab for repo hosting + MR workflow. Keeping CI
there avoids cross-SaaS webhook gymnastics.

## References

- Global `~/.claude/CLAUDE.md` — "Use local runners, never rely on
  GitLab SaaS quota" rule.
- `.gitlab-ci.yml` — every job carries `tags: [macbook-local]`.
