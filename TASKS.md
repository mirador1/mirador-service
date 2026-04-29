# TASKS — iris-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 🎯 e-commerce coverage — last item

JaCoCo bundle 94.97%, order/product packages 100%, Spring Boot IT
landed via stable-v1.2.16/17, smoke.hurl already covers the order/
product/lines flow (section 9bis), bin/dev/sections/code.sh already
loops over order + product slices. Remaining :

- ☐ PIT mutations score ≥ 75 % on `org.iris.{order,product}.*`
  (slow — `mvn test-compile org.pitest:pitest-maven:mutationCoverage`
  takes ~5-10 min on the e-commerce slice ; defer to a scheduled
  batch unless the time is budgeted)

## 🎨 SLO dashboard screenshots

5 panels to capture (SLO overview / breakdown by endpoint / latency
heatmap / Apdex / chaos demo) once cluster restart + Grafana up.
Procedure documented around 2026-04-28 08:55.
