# ADR-0045 — Pitest mutation coverage stays in Maven Site, not SonarCloud

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Related: `docs/reference/quality-reports-map.md` (Maven Site vs SonarCloud comparison), ADR-0009 (quality pipeline)

## Context

Pitest (`pitest-maven-plugin`) produces **mutation coverage**, the
single most valuable complement to JaCoCo's line coverage: it
tells you whether your tests actually **assert** behaviour (vs
merely exercising lines with `assertNotNull` or no assertion at
all). An 80 % JaCoCo line coverage + 40 % Pitest mutation score
means "tests run the code but don't actually check much".

Pitest runs on demand via `mvn verify -Preport` and writes an
HTML report to `target/pit-reports/index.html`, which
`maven-site-plugin` then includes in the generated Maven Site
(served locally on `http://localhost:8083` under the `docs`
Compose profile).

The natural next step would be to show the mutation score next
to the line coverage on SonarCloud. That requires a Sonar
plugin that ingests Pitest output. The options on 2026-04-21:

- `org.codehaus.sonar-plugins:sonar-pitest-plugin` — last
  release **v0.5 in December 2014**. Abandoned 11 years. Doesn't
  work on SonarQube ≥ 7, certainly not SonarCloud 2026.
- `VinodAnandan/sonar-pitest` (community fork) — last push
  2020-05-16. Not maintained, not widely adopted.
- `Naveen-496/pitest-sonar` (personal fork, 0 stars, pushed
  2025-10-27). Zero ecosystem traction.
- SonarCloud native Pitest support — not on the roadmap as of
  2026-04-21 (no related feature request in the public roadmap).

## Decision

**Keep Pitest outputting HTML into Maven Site. Do NOT attempt
to ingest Pitest results into SonarCloud via any plugin or
custom bridge.**

Mutation coverage data lives in ONE place — Maven Site — and is
consulted by developers who want "are my tests asserting what
they should" data. The SonarCloud quality gate only considers
JaCoCo line/branch coverage (`sonar.coverage.jacoco.xmlReportPaths`).

## Alternatives considered

### A) Install `sonar-pitest-plugin@0.5`

**Rejected.** 11-year-old plugin, no SonarCloud support
(SonarCloud's plugin API has moved substantially since 2014),
no security patches. Installing would mean pinning an
abandoned JAR in the project's Sonar scanner configuration
with no upgrade path.

### B) Fork an unmaintained community plugin

**Rejected.** Would turn the project into an accidental
maintainer of a niche Sonar plugin. Out of scope for what is
a portfolio backend demo.

### C) Write a Pitest → SARIF converter ourselves

**Rejected for now.** Pitest output maps awkwardly to SARIF
(mutations are per-line-per-mutator, not per-issue). The
value added over reading the Pitest HTML directly is marginal
compared to the custom code to maintain. Reconsider if:
- A community SARIF converter emerges and gets traction.
- The team needs mutation coverage in SonarCloud PR
  decoration (which requires paid tier anyway).

### D) Drop Pitest entirely

**Rejected.** The mutation score IS valuable signal — a single
developer opening `target/pit-reports/index.html` after
`mvn verify -Preport` gets high-signal feedback on where
tests need strengthening. Keeping the report cost is a few
extra minutes on the `report` profile (off by default on
fast builds).

### E) Keep Pitest, in Maven Site only (accepted)

Pitest output is HTML in Maven Site. SonarCloud shows line
coverage only. Developers who care about mutation score open
`http://localhost:8083/pit-reports/` — documented in
`docs/reference/quality-reports-map.md`.

## Consequences

**Positive**:
- No abandoned-plugin maintenance burden.
- SonarCloud stays clean — only tools with current, actively
  maintained Sonar integration feed into the gate (JaCoCo for
  coverage; SARIF for Trivy/Spectral/ESLint).
- The `docs` Compose profile remains the single entry point
  for the full-rich HTML reports (Surefire, JaCoCo detail,
  Pitest, Javadoc).

**Negative**:
- Mutation coverage doesn't appear on SonarCloud PR decoration
  (and never would — we're on the free tier, no PR decoration
  at all per ADR-0041).
- Developers need to know about Maven Site to find the Pitest
  data. Partially mitigated by the
  [Quality reports map](../reference/quality-reports-map.md)
  table that lists every report and its home.

## Revisit criteria

- A maintained SonarCloud plugin for Pitest emerges with
  active releases within the last 12 months → evaluate
  wiring it via `sonar.externalIssuesReportPaths` or native
  plugin hook.
- Project moves to SonarCloud Developer Edition (paid) AND
  Developer Edition adds a native Pitest integration → drop
  the Maven-Site-only stance.
- A SARIF-based community converter appears and the SARIF
  output is actionable on SonarCloud's Issues tab.

## References

- `pom.xml` — `pitest-maven-plugin` configuration under the
  `report` profile.
- `docs/reference/quality-reports-map.md` — the canonical "where
  does each quality report live" table; this ADR is referenced
  from the "Why Pitest stays outside SonarCloud" subsection.
- TASKS.md — closed item: "Pitest mutation coverage to Sonar —
  DEFER: `sonar-pitest-plugin` abandoned 2017, no maintained
  wire-up".
- ADR-0009 — broader UI/svc quality pipeline.
