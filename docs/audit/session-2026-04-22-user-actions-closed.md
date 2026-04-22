# Session 2026-04-22 — 3 user-actions closed + shield retirement

## Scope

Post-`stable-v1.0.10` session. The three user actions that Claude could
not execute directly (per its safety rules) got runbooks shipped the
night before (commits `040b34c` 3 runbooks + `bin/dev/setup-signed-commits.sh`).
This session walks the user through the three runbooks in sequence, then
retires the k8s-apply shields that depend on one of them.

## Timeline

| Time  | Event                                                                    |
|-------|--------------------------------------------------------------------------|
| 08:41 | Claude presents Action 1 (Docker VM) step-by-step                        |
| 09:00 | User: "voilà c'est fait"                                                 |
| 09:01 | Validation: `docker system info` → **15.6 GiB, 10 CPUs** (user set 16 GB)|
| 09:07 | User runs `bin/dev/setup-signed-commits.sh` → local sign ✅               |
| 09:07 | `gh ssh-key add` FAILS — missing `admin:ssh_signing_key` scope           |
| 09:10 | Claude commits `314012f` (retire shields) — first SIGNED commit ✅        |
| 09:14 | User runs `gh auth refresh` + `gh ssh-key add` → GitHub signing key added|
| 09:17 | Claude presents Action 3 `gh api required_signatures` commands           |
| 09:19 | User enables `required_signatures` on both repos                         |
| 09:20 | Validation: both repos return `"enabled": true`                          |

Total walkthrough: **~40 minutes** (incl. Claude's parallel shield commit + docs).

## Actions liquidated

### Action 1 — Docker VM ≥ 12 GB (for k8s shields retirement)

- **Before**: Docker Desktop default VM = 7.6 GB. Pipelines #610 + #612
  OOM'd on kind control-plane during chaos-mesh + kube-prom-stack CRD
  install.
- **After**: VM raised to **16 GB** (2 GB above minimum for headroom).
- **Consequence**: `test:k8s-apply` + `test:k8s-apply-prom` shields
  retired in the same session (commit `314012f`). Both jobs are now
  BLOCKING on MR + main again. First real exit-path demonstration of
  the ADR-0049 "dated exit ticket" pattern.

### Action 2 — S2 signed commits hardening

- **Before**: `required_signatures` was disabled on GitHub `main`
  during the earlier Auth0 work session to allow unsigned helper
  commits. Local signing was not configured.
- **After**:
  - `~/.gitconfig` global:
    - `gpg.format = ssh`
    - `user.signingkey = /Users/benoitbesson/.ssh/id_ed25519.pub`
    - `commit.gpgsign = true`
    - `gpg.ssh.allowedSignersFile = /Users/benoitbesson/.ssh/allowed_signers`
  - `~/.ssh/allowed_signers` created (for `git log --show-signature`).
  - GitHub signing-type SSH key registered:
    `signing key (MBP-de-Benoit)` ED25519.
- **Validated on**: commit `314012f` → `Good "git" signature for
  benoit.besson@gmail.com with ED25519 key SHA256:Phoko...`.
- **First follow-up snag**: `gh ssh-key add --type signing` hit 404 —
  `admin:ssh_signing_key` scope missing from `gh`. Fixed via
  `gh auth refresh -h github.com -s admin:ssh_signing_key`. Added to
  the runbook for future setups.

### Action 3 — Re-enable `required_signatures`

- `POST .../branches/main/protection/required_signatures` on both repos
  → both `"enabled": true` confirmed.
- Every future merge to main on either repo requires a signed commit.
  Unsigned pushes are rejected at the server side.

## Side-effect commits (this session)

| Commit   | Subject                                                           |
|----------|-------------------------------------------------------------------|
| [`314012f`](../../.git) | ci: retire k8s-apply[-prom] shields after Docker Desktop VM raise |

Plus doc updates in follow-up commits:

- TASKS.md — user-actions section rewritten (pending → ✅ DONE)
- ADR-0049 — "Shield-retirement log" section added with the
  2026-04-22 retirement precedent

## Learning captured

1. **`gh ssh-key add --type signing` needs the `admin:ssh_signing_key`
   scope** — regular `repo` + `user` scope are not enough. Added to the
   signed-commits runbook.
2. **Docker Desktop VM raise = 1-shot action**, users can set 16 GB
   directly (vs the 12 GB minimum suggestion) to buy headroom for
   future multi-compose days.
3. **First signed commit can be made BY Claude** if the user has just
   enabled `commit.gpgsign=true` globally — shell inheritance means
   Claude's git commits pick up the new config immediately. Commit
   `314012f` is a perfect test case.
4. **ADR-0049 exit pattern validated in production** — 18 hours from
   ticket (2026-04-21) to retirement (2026-04-22), 29 days inside the
   1-month revisit ceiling.

## Open follow-ups

- `TF_STATE_BUCKET` provisioning — deferred; creating a GCS bucket
  costs money (Google Cloud Storage ~€0.02/GB/month + egress fees),
  explicit user confirmation needed before provisioning.
- `.compat-job` + `semgrep` + `native-image-build` shields — retroactive
  tickets pending (manual-only intent; no urgency).

## References

- [ADR-0049](../adr/0049-ci-shields-with-dated-exit-tickets.md) — the
  pattern that made this flow smooth
- [docs/how-to/docker-desktop-vm-cap.md](../how-to/docker-desktop-vm-cap.md) — runbook used for Action 1
- [docs/how-to/required-signed-commits-github.md](../how-to/required-signed-commits-github.md) — runbook used for Action 3
- [bin/dev/setup-signed-commits.sh](../../bin/dev/setup-signed-commits.sh) — helper used for Action 2
