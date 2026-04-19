# ADR-0031 — Version adoption policy (patch / minor / major)

- **Status**: Accepted
- **Date**: 2026-04-19
- **Related**: [ADR-0021](0021-cost-deferred-industrial-patterns.md) (editorial rule),
  Renovate config (`renovate.json`), lefthook pre-commit

## Context

Dependencies update constantly. Two failure modes bookend the spectrum:

- **Stay on bleeding edge** — you hit bugs nobody else has yet, waste
  debugging time on regressions that will be fixed upstream in a week.
- **Stay far behind** — you accumulate known CVEs and technical debt;
  the big-bang upgrade becomes a project of its own.

Without a written policy, the default is drift: minor bumps get
deferred because "it might break something", patches pile up, and
the major upgrade hits when a breaking change is forced by a CVE.
That's the worst of both worlds.

This ADR codifies Mirador's adoption cadence per update class.

## Decision

Adopt a **graduated policy** based on the update class. Renovate is
already configured in a shape that mostly implements this; the ADR
makes the intent explicit and gives each rule a reason.

### Patch — security

**Adopt immediately.** Zero lag.

- CVE-tagged patches (OWASP Dep-Check, Grype, Trivy, Dependabot
  security alerts) → open MR automatically, auto-merge on CI green.
- Mirrored to the ROADMAP "safety net" policy: if a scanner flags
  and the fix is a patch bump, there's no scenario where lagging
  is safer than adopting.

### Patch — non-security

**Adopt within a week.** 0–7 days.

- Renovate schedule `schedule:weekends` groups these so MRs batch.
- Non-security patches are by definition low-risk — the point of
  a patch bump is preserving API compat while fixing a bug.
- Auto-merge if CI passes, human review unnecessary.

### Minor

**Adopt within 2–4 weeks.** Not immediately — let the early-adopter
cohort surface regressions.

- Renovate groups minors (see the 7 groupings: Spring Boot, Testcontainers,
  Argo, ESO, Chaos Mesh, Kyverno, cert-manager) so one ecosystem
  upgrades together.
- Human review required on the grouped MR — auto-merge disabled for
  minors unless `matchDepTypes: build/dev/plugin`.
- If the CVE scanner flags something in the current minor version,
  the rule escalates: treat the minor bump as a security patch and
  adopt immediately.

### Major

**Adopt within 1–3 months.** Deliberate, with ADR.

- Renovate labels majors `major-upgrade` + `manual-review` and does
  NOT auto-merge (see `renovate.json` rule for `java`).
- Every major bump triggers an ADR recording: what breaks, what
  tests prove the upgrade is safe, rollback plan.
- If the compat matrix (`compat-sb3-java17`, `compat-sb4-java21`,
  etc.) passes on the major, the upgrade confidence is high enough
  to proceed with auto-merge after human review.
- Exception: CVE disclosed on the current major forces the upgrade
  regardless of schedule. Document the rush in the ADR.

## Rationale — why not "stay N-1 months behind on everything"

The classic "be a few months behind to let others find bugs" heuristic
made sense in an era of 18-month release cycles. In 2024-2026:

- **Release cadences accelerated** (Spring Boot = 6-month minors,
  Java = 6-month cycle, npm ecosystem = weekly).
- **Upstream CI is better** — regressions are caught pre-release by
  the project's own test matrix, not by downstream users.
- **CVE disclosure windows shrank** — Log4Shell and Spring4Shell
  were exploited within hours of disclosure. Being "a few months
  behind" during that window = being exposed for months.
- **Security scanners attach to specific versions** — a permanent
  "we're at N-1" posture means permanent red dots on OWASP
  Dep-Check. The dashboard stops being actionable.

The honest conclusion: lag is beneficial for the **adoption risk**
dimension (early-adopter bugs) but harmful for the **security**
dimension. The graduated policy above separates those:

| Dimension | Patch | Minor | Major |
|---|---|---|---|
| Adoption risk (bugs) | trivial | moderate | high |
| Security upside | high | medium | low |
| Our lag | 0–7 d | 2–4 w | 1–3 mo |

### What this looks like in practice

Example sequence for a Spring Boot 4.1.6 → 4.1.7 patch:

1. Renovate opens MR with the bump on a weekend.
2. CI runs full test matrix + SBOM scan.
3. Auto-merge enabled (rule: `matchUpdateTypes: [patch, pin, digest]`).
4. CI green → merged within hours of Monday morning.

For a Spring Boot 4.1 → 4.2 minor:

1. Renovate opens a grouped MR ("Spring Boot") on Monday.
2. CI runs full test matrix + integration on the upgraded dependency.
3. Auto-merge NOT enabled — waits for human review.
4. Human checks the Spring Boot 4.2 migration notes, reviews the
   test diffs, approves or defers.
5. If approved, merge within 2–4 weeks of release.

For a Spring Boot 4 → 5 major:

1. Renovate opens MR with `major-upgrade` label.
2. A companion ADR is drafted: scope of changes, breaking-change
   handling, test coverage, rollback.
3. Compat matrix (`compat-sb4-java21`, `compat-sb5-java21`) runs in
   parallel to prove the upgrade path.
4. Adoption window: 1–3 months.

## Consequences

### Positive

- **Security posture stays current** — no "we're 6 months behind on
  Log4j" moments.
- **Human review cost bounded** — patch + minor bumps don't clog
  the review queue.
- **Major upgrades documented** — ADR discipline prevents
  "upgrade-and-pray" merges.
- **Renovate does the heavy lifting** — grouped MRs, automerge on
  low-risk, labels for triage.

### Negative

- **Minor bumps bite occasionally** — 2–4 weeks lag isn't long
  enough for every regression to surface. We accept this.
- **Major ADR adds friction** — deliberate. Majors should be
  friction-y; the cost of a wrong major upgrade is a rollback + a
  CI debugging session.

### Trade-off we accept

On balance, **being current is cheaper than being behind** for this
project's threat model (public portfolio, no regulated data). A
private-data or regulated environment might shift the window longer
on minors.

## Revisit this when

- The project acquires regulated data (GDPR sensitive, PCI scope)
  → minor lag might grow to 2–3 months with explicit change control.
- A major upgrade goes catastrophically wrong → postmortem revisits
  the major cadence, possibly tightens the ADR rule.
- Renovate's grouping logic gets significantly better (less manual
  babysitting) → the minor cadence may shrink to 1 week.
- The project moves to offline air-gap distribution → the security
  argument changes since CVEs can't be exploited remotely; the
  cadence may relax.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Stay current on everything (0 lag) | Early-adopter regression cost would show up in demos. Renovate's weekend grouping already gives 1–2 days lag effectively — enough to dodge the worst. |
| Always N-1 month behind | Permanent CVE backlog (Log4Shell class problem). Worse posture than current. |
| N-1 major only (pin majors forever) | Guarantees end-of-life exposure. Spring Boot 3 becomes EOL and CVE backlog grows unboundedly. |
| Adopt-when-blessed-by-upstream | No clear signal. Each project has its own "stable" tag meaning. Renovate's datasource metadata (pre-release flags, deprecation markers) is the closest machine-readable equivalent and is already used. |
