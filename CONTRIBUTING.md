# Contributing to Mirador

First: thank you. Mirador is a portfolio demo project — contributions
improve the "real-world how-to-operate-this-thing" value for everyone
who reads it later.

## Where to contribute

**GitLab is the canonical source.** Contributions happen there.

- Service: [gitlab.com/mirador1/mirador-service](https://gitlab.com/mirador1/mirador-service)
- UI: [gitlab.com/mirador1/mirador-ui](https://gitlab.com/mirador1/mirador-ui)

The GitHub repos (`github.com/mirador1/mirador-*`) are read-only
mirrors. Issues and PRs opened there will not be reviewed — the
repo description points here. See
[`docs/ops/ci-philosophy.md`](docs/ops/ci-philosophy.md) for why.

## Types of contributions we welcome

| Type | How to start |
|---|---|
| **Bug report** | Open a [GitLab issue](https://gitlab.com/mirador1/mirador-service/-/issues/new) with the "bug" template. |
| **Security vulnerability** | **Do not open a public issue.** See [`SECURITY.md`](SECURITY.md). |
| **Documentation fix / clarification** | Open an MR directly. Docs-only MRs are merged fastest. |
| **New ADR / architectural suggestion** | Open an issue first to discuss the problem + alternatives before writing the ADR. |
| **New runbook** | Open an MR adding a file to [`docs/ops/runbooks/`](docs/ops/runbooks/) following the 5-heading template in the README there. |
| **New feature** | Open an issue + link an ADR draft. Features that don't advance the demo scenarios are usually declined (see [ADR-0021](docs/adr/0021-cost-deferred-industrial-patterns.md)). |
| **Dependency bump** | Renovate does this automatically. Manual bumps of critical libs (Spring Boot major, Java LTS) are accepted with a short ADR. |

## Development workflow

### 1. Set up

```bash
git clone https://gitlab.com/mirador1/mirador-service.git
cd mirador-service
bin/mirador-doctor   # one-command health check — must be all green
```

If any check fails, follow the advice in the output. `bin/mirador-
doctor --no-cost` skips the GCP checks if you're offline.

### 2. Branch + commit

Work on `dev`. The project uses
[trunk-based development](https://trunkbaseddevelopment.com):
single working branch, squash-merged to `main` via MR.

```bash
git checkout dev
# make your changes
git add ...
git commit -m "feat(scope): short subject under 72 chars"
```

**Conventional Commits are mandatory.** Enforced by
`lefthook` commit-msg hook + commitlint config. Accepted types:

```
feat | fix | docs | style | refactor | perf | test |
build | ci | chore | revert
```

Scope is optional but useful (`feat(auth): ...`, `fix(ci): ...`).

### 3. Push + open MR

```bash
bin/ship.sh "feat(scope): short subject"
```

This one command:
1. Pushes `dev` (force-with-lease — safe in solo-dev workflow)
2. Opens or updates the MR dev → main with squash-before-merge
3. Arms auto-merge when the pipeline succeeds

Or run each step manually with `git push` + `glab mr create` + the
auto-merge call — `ship.sh --dry-run` prints the exact commands.

### 4. Wait for CI

Green CI on the MR triggers the auto-merge. The pipeline stages
(see [`docs/ops/ci-variables.md`](docs/ops/ci-variables.md)):

```
lint → test → integration → k8s → package → compat →
native → sonar → reports → infra → deploy
```

Most are fast (unit + integration = 3 min). Docker image build,
kind-in-CI validation and mutation tests take longer. `allow_failure:
true` on optional stages means one red doesn't block merge.

### 5. Sync `dev` after merge

Handled automatically by `bin/ship.sh --wait`. Or manually:

```bash
git fetch --all
git reset --hard origin/main
git push origin dev --force-with-lease
```

After a squash-merge, `dev` has the pre-squash commits with content
already on `main`. Reset-to-main is the clean re-baseline.

## Code conventions

### Java / Spring Boot

- **Default stack**: SB4 + Java 25.
- **Compat matrix**: keep the other profiles (SB4+Java21, SB4+Java17,
  SB3+Java21, SB3+Java17) passing. If your change breaks one, note
  why in the commit message.
- **No `@Autowired` field injection**. Constructor injection only.
- **No deprecated Spring Boot flags or APIs.**
- **Flyway migration versions** are immutable — never modify a
  `V_N__*.sql` that's already in `main`. Bump the version.

### Angular (UI repo)

- **Angular 21 zoneless** — no `fakeAsync`, no `tick()`, no
  `detectChanges()` in tests.
- Use `@if`, `@for`, `@switch` — not `*ngIf`, `*ngFor`.
- Prefer `signal()` and `computed()` over `ngModel`.
- Charts in raw SVG (no charting library).

### Comments

Comments explain **why**, not what. Write comments that a future
maintainer with no conversation history can understand.

Good: `// Rate limit matches Cloudflare's DDoS threshold per IP.`
Bad: `// Set rate limit.`

### Tests

- Unit tests run on every MR.
- Integration tests (Testcontainers) run on every MR.
- E2E tests (Playwright, planned — ROADMAP Tier-1 #2) run in kind-in-CI.
- Mutation tests (PIT) run on main + nightly.
- Every new `@Service` or `@RestController` gets at least one unit
  test. Every new API endpoint gets at least one integration test.

### Documentation

- **Any new ADR-worthy decision gets an ADR**. See the criteria in
  [`docs/adr/README.md`](docs/adr/README.md). Code-style or
  bug-fix decisions do NOT get an ADR.
- **Any new `$VAR` in `.gitlab-ci.yml`** gets a row in
  [`docs/ops/ci-variables.md`](docs/ops/ci-variables.md) in the
  same commit.
- **Any new runbook** follows the 5-heading template.
- **READMEs in EN + FR** — a lefthook hook blocks a commit that
  touches one without the other. Skip with `LEFTHOOK=0 git commit`
  if the edit is intentionally one-sided.

## ADR format

See the existing ADRs — all follow Michael Nygard's format:

```markdown
# ADR-NNNN — Short verb-phrase title

- **Status**: Accepted / Superseded / Deprecated
- **Date**: YYYY-MM-DD
- **Related**: (optional) cross-refs

## Context
(What's the situation? What constraints are in play?)

## Decision
(What have we decided to do?)

## Consequences
### Positive / Negative / Neutral

## Alternatives considered
| Option | Why rejected |

## Revisit this when
(What would flip the decision?)
```

New ADRs get the next number (`0032`, `0033`, …). Numbering is
monotonic even when an ADR supersedes another.

## Merge policy

- **Squash-merge** into `main`. Keeps history linear, one MR = one
  commit on main.
- **`main` is protected** — no direct push.
- **Source branch preserved** (`--remove-source-branch=false`) — we
  keep `dev` alive always.
- **Auto-merge allowed** when MWPS is armed + CI green.

## Review

This is a solo-maintained project. Reviews happen asynchronously.
If your MR has been idle for a week without feedback, a polite ping
in a comment is welcome.

Heuristics the maintainer applies:

- Does the change advance a demo scenario, or solve a concrete
  problem?
- Is the ADR discipline preserved (new decisions have ADRs; non-
  decisions don't)?
- Does the CI stay green?
- Are docs updated?
- Does the commit message explain *why* in the body?

## What gets declined

- Changes that add dependencies without a clear scenario.
- Code-style churn for its own sake.
- Features that require a paid SaaS to demo (ADR-0022 budget).
- "Modernisations" of patterns that work — if a demo scenario
  doesn't break, the pattern stays.

## Acknowledgements

The project is built in close collaboration with Anthropic's
[Claude Opus 4.7](https://www.anthropic.com/claude) (1 M-token
context), driven from the
[Claude Code](https://docs.anthropic.com/claude/docs/claude-code) CLI
— see the "AI-assisted integration" section in the README for the
split of contributions. Contributors are welcome to use the same
tooling; the `Co-Authored-By:` trailer on each commit names the exact
model. Human review remains the authority on scope and arbitrage.

---

Thanks again for contributing. If you have any question the above
doesn't answer, open an issue with the label `question`.
