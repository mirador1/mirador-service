# Mirador Service — Persistent Task Backlog

<!-- 
  This file is the source of truth for pending work across sessions.
  Claude must READ this file at the start of every session.
  Claude must UPDATE this file whenever a task is added, started, or completed.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
-->

## Pending

- [ ] Add `mvn site` step to GitLab CI pipeline — generate and publish the Maven site HTML
  as a browsable GitLab Pages or job artifact (user said: "fait la modif dans la CI")
  Command: `./mvnw site` (already works — generates Surefire, JaCoCo, SpotBugs, Javadoc)
  Note: PMD, Checkstyle and JXR omitted from site — they crash on Java 25 syntax;
  their results are still available at /actuator/quality

## Recently Completed

- [x] Maven site: <reporting> section added and working — generates Surefire, Failsafe,
      JaCoCo, SpotBugs, Javadoc reports; PMD/Checkstyle/JXR excluded (Java 25 incompatible)
- [x] TASKS.md + CLAUDE.md rule created in both repos for persistent task tracking
- [x] CVE upgrades: Tomcat 11.0.21, springdoc 3.0.3 / swagger-ui 5.32.2, protobuf 4.34.1
- [x] OWASP report embedded in JAR + `report` profile for on-demand full scan
- [x] Quality report tabbed UI (Overview / Tests / Static Analysis / Security / Mutation / Build)
- [x] Health aggregation fixed: Keycloak + Ollama → UNKNOWN when not running
- [x] IdempotencyFilter bug fix: now caches 2xx (not just 200), stores status+body pair
- [x] JwtTokenProvider: catch (JwtException | IllegalArgumentException e)
- [x] Zero `any` types across all Angular components
- [x] CustomerStatsSchedulerTest + QualityReportEndpoint constructor injection
- [x] TodoServiceTest (4 cases), BioServiceTest (3 cases), IdempotencyFilterTest 201-case
- [x] CLAUDE.md created in both repos with full workflow rules
- [x] Angular build: 0 NG8113 warnings, 0 errors
