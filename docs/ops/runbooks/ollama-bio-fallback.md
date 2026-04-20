# Ollama `/customers/{id}/bio` returns "Bio temporarily unavailable."

## Quick triage (30 seconds)

```bash
# Is Ollama even up?
curl -sSf http://localhost:11434/api/tags | jq '.models[].name' 2>/dev/null
# OR
docker ps --filter name=ollama --format "{{.Names}}\t{{.Status}}"

# Check the circuit breaker state
curl -sS http://localhost:8080/actuator/circuitbreakers | jq .circuitBreakers.ollama.state
```

"Bio temporarily unavailable." is the **intended** fallback when Ollama
is slow, unreachable, or the bulkhead semaphore is exhausted. It's not
a bug — it's the graceful-degradation pattern in `BioService.java`.

The real question: is the fallback firing because Ollama is genuinely
down, or because the circuit breaker opened on recent failures?

## Likely root causes (in order of frequency)

1. **Ollama container stopped** — most common on compose when the
   machine restarted and `./run.sh all` wasn't re-run.
2. **Cold model load** — Ollama takes 30-60s to load `llama3.2` on
   first request after idle. The circuit breaker (10s timeout)
   trips on the first 3 requests, then opens for 60s.
3. **Resource starvation** — Ollama competes with the rest of compose
   for RAM. A 7B model needs 5 GB; a Mac with 16 GB total under the
   full compose stack is tight.
4. **Bulkhead saturation** — `max-concurrent-calls: 5` per
   application.yml; under load all 5 permits get taken and new
   requests fail-fast.
5. **Spring AI shim regression** — the SB3/SB4 compat shims are
   load-bearing (see README "Where AI was wrong"). A broken shim
   makes the ChatClient error on every call.

## Commands to run

```bash
# 1. Ollama process + model
docker logs customerservice-ollama --tail 30
curl -s http://localhost:11434/api/ps | jq .

# 2. Circuit breaker state (open/half-open/closed)
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state \
  | jq '.measurements[] | {value,tags}'

# 3. Bulkhead occupancy
curl -s http://localhost:8080/actuator/metrics/resilience4j.bulkhead.available.concurrent.calls \
  | jq .

# 4. Fallback log
docker logs customerservice-app --tail 100 | grep bio_fallback
# OR Grafana Loki: {app="mirador"} |~ "bio_fallback"

# 5. Direct Ollama call — bypasses circuit breaker
curl -s http://localhost:11434/api/generate \
  -d '{"model":"llama3.2","prompt":"Hi","stream":false}' | jq .response
```

## Fix that worked last time

- **Container stopped** — `docker compose --profile full up -d ollama`
  (ollama lives under the `full` profile since 2026-04-20) or
  `./run.sh all` to bring everything back.
- **Cold start** — warm up the model before demo:
  ```
  curl -s http://localhost:11434/api/generate -d '{"model":"llama3.2","prompt":"Hello","stream":false}' > /dev/null
  ```
  Then reset the circuit breaker by waiting 60s or hitting the
  actuator endpoint:
  ```
  curl -X POST http://localhost:8080/actuator/circuitbreakers/reset
  ```
- **OOM** — reduce the model size. `llama3.2:1b` (1.3 GB) works in
  constrained environments. Edit `docker-compose.yml` to set
  `OLLAMA_MODEL=llama3.2:1b`, pull the model, restart.
- **Bulkhead saturation** under load test — increase
  `resilience4j.bulkhead.instances.ollama.max-concurrent-calls` in
  `application.yml` to 10. Only useful if Ollama can actually handle
  the parallelism (check CPU + RAM headroom first).

## When to escalate

For a portfolio demo, Ollama down is **acceptable**. The
CircuitBreaker pattern is exactly what makes the API resilient to
a missing dependency — hitting the fallback is the intended
demonstration, not a failure to fix.

**Do NOT** attempt to restart Ollama mid-demo — the 30-60s cold-start
is more disruptive than the fallback. Explain the fallback out loud.

Only escalate (destroy + recreate) if:
- The main API is also degrading (readiness DOWN, see `backend-503.md`)
- Circuit breaker stays open for >5 min and doesn't half-open
- Bulkhead metric shows permanent saturation (all 5 slots always busy)

See [`resilience.md`](../../architecture/resilience.md) for the full
pattern stack (CB + bulkhead + retry + timeout).
