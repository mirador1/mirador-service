# TASKS — iris-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 📊 RPO measurement (paused)

RTO measured 2026-04-28 : **7 seconds** for postgres pod-kill on
GKE Autopilot ([shared/docs/runbooks/rto-rpo-measurement.md](https://gitlab.com/iris-7/iris-service-shared/-/blob/main/docs/runbooks/rto-rpo-measurement.md)).
Beats 30 s SLA target.

Remaining : **RPO measurement** with steady-state write traffic
during chaos window (k6 at 50 req/s POSTing /customers, count
post-recovery `SELECT id FROM customer WHERE id IN (...)` holes).
Cluster #2 was torn down to save costs — restart via
`bin/cluster/demo/up.sh` when ready, deploy iris-service-java alongside
postgres for write traffic.

## 🎯 e-commerce coverage — remaining items

JaCoCo bundle 93%+, order/product packages 100%, Spring Boot IT
landed via stable-v1.2.16/17 (OrderHttpITest + ProductHttpITest with
Testcontainers Postgres). Remaining :

- ☐ PIT mutations score ≥ 75 % on `org.iris.{order,product}.*`
- ☐ `bin/dev/api-smoke.sh` : POST /orders + 2 OrderLines + GET +
  DELETE + total recalculé (manual smoke covered by IT, but a
  scriptable ./run.sh entrypoint is convenient for cluster demos)
- ☐ `bin/dev/sections/code.sh` : include order + product packages
  in the stability-check section once it lands

## 🔒 SecurityConfig coverage (deferred — needs @SpringBootTest)

`org.iris.auth.SecurityConfig` sits at 27 % in unit-only JaCoCo
because the `securityFilterChain(HttpSecurity)` lambda DSL only
evaluates when Spring builds the filter chain. The MR pipeline's
IT data covers them in production CI but local UT does not.

To close locally without the full IT slowdown : `@SpringBootTest`
narrowed to `classes = SecurityConfig.class` with mocked
`JwtAuthenticationFilter` + `ApiKeyAuthenticationFilter`. Estimated
~5 min setup, ~3-5 s per test.

## 🎨 SLO dashboard screenshots

5 panels to capture (SLO overview / breakdown by endpoint / latency
heatmap / Apdex / chaos demo) once cluster restart + Grafana up.
Procedure documented around 2026-04-28 08:55.
