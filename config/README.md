# `config/` — Static analyzer and security-scanner configuration

Static analyzer rulesets and suppression files that Maven plugins read at
build/analysis time. They used to live at the project root — too much
clutter next to `pom.xml`, `Dockerfile`, and friends — and were moved here
to group "how the tool is configured" separately from "how the project is
built".

## Files

| File                       | Consumed by                                              | Purpose                                                                                                                                                 |
| -------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `owasp-suppressions.xml`   | `org.owasp:dependency-check-maven` (pom.xml → `<suppressionFile>`) | Project-specific CVE false-positive suppressions. Each `<suppress>` entry carries a `<notes>` comment explaining why the CVE doesn't apply or when the suppression should be revisited. |
| `pmd-ruleset.xml`          | `org.apache.maven.plugins:maven-pmd-plugin` (pom.xml → `<ruleset>`) | Selects which PMD rule categories to enforce and which rules to exclude. Currently: `bestpractices`, `design` (minus LawOfDemeter + LoosePackageCoupling), `errorprone`, `performance`. Excluded rules are ones that StackOverflow on Java 25 generic type hierarchies. |
| `spotbugs-exclude.xml`     | `com.github.spotbugs:spotbugs-maven-plugin` (pom.xml → `<excludeFilterFile>`) | Documents and suppresses SpotBugs false positives for Spring DI patterns (field injection, bean lifecycle, etc.) that otherwise drown the real signal. |
| `README.md`                | (humans)                                                 | This file.                                                                                                                                              |

## When to edit

- **A new CVE flagged in `owasp-dependency-check` is a false positive**
  (e.g. the vulnerable code path isn't reachable) → add a `<suppress>`
  entry to `owasp-suppressions.xml` with a dated note explaining the
  rationale. Re-evaluate at each dependency upgrade.
- **PMD flags a pattern we consider idiomatic** → either rewrite the code
  or exclude the rule in `pmd-ruleset.xml`.
- **SpotBugs flags a Spring annotation pattern** → add an exclusion with
  an inline comment to `spotbugs-exclude.xml`.

**Do not blanket-suppress** rules to silence noise. Every suppression
must carry a one-line rationale — `<notes>` for OWASP, XML comments for
SpotBugs, inline comments for PMD.

## Testing a change

```bash
# OWASP (slow — needs NVD cache)
mvn verify -Preport

# PMD
mvn -Preport-static pmd:pmd
cat target/pmd.xml   # check violations

# SpotBugs
mvn -Preport-static spotbugs:spotbugs
open target/spotbugs.html
```

## Related

- `pom.xml` — the plugin configurations that point to each file (search
  for `suppressionFile`, `ruleset`, `excludeFilterFile`).
- `.gitlab-ci.yml` — the `owasp-dependency-check`, `code-quality`, and
  related jobs that run these analyzers in CI.
- `.owasp-data/` — local NVD cache populated by the OWASP plugin (see
  `build/owasp-data-README.md`).
