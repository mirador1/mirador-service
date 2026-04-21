# Alert: `MiradorHeapHigh`

Fired when `mirador:jvm_heap:used_ratio > 0.85` for ≥ 10 min — JVM heap is
above 85 % of max. Warning-tier: the G1 collector handles this gracefully
up to ~92 %, but sustained pressure predicts a GC-induced slowdown.

## Quick triage (60 seconds)

```bash
# 1. Confirm the alert from Prometheus
curl -s 'http://localhost:9090/api/v1/query?query=mirador:jvm_heap:used_ratio' \
  | jq '.data.result[0].value[1]'

# 2. What's the heap profile right now? Pyroscope has the answer:
#    Pyroscope UI → profile type "alloc_space" on application "mirador"
#    Top 5 allocating stack frames = prime leak suspects.
```

## Likely root causes

1. **Caffeine cache unbounded** — a new cache with no `maximumSize`
   was added recently. Check `/actuator/caches` for an oversized
   `estimatedSize`. Usual fix: add `.maximumSize(10_000)` to the
   Caffeine builder.
2. **Observation span accumulation** — Micrometer's Observation
   context leaks when an async task doesn't close its span. Fixed
   by `takeUntilDestroyed` in the UI; on the backend, look for a
   `CompletableFuture` chain missing a final `.whenComplete()`.
3. **Spring AI conversation history** — `ChatClient` retains
   conversation by default. Stateless calls need
   `.advisors(a -> a.param("chat_memory_conversation_id", null))`.
4. **Streaming endpoint backpressure** — the `/customers/stream` SSE
   buffers events if clients disconnect without closing. Check
   active connection count via `/actuator/metrics/sse.emitters.active`.

## Commands to run

```bash
# Heap dump via actuator (requires management.endpoint.heapdump.enabled=true)
curl -o heap.hprof http://localhost:8080/actuator/heapdump

# Use Eclipse MAT or visualvm to analyse heap.hprof.
# Retained-size > 50 MB on a single class = leak candidate.

# Cache audit
curl -s http://localhost:8080/actuator/caches | jq .

# Current heap usage by pool
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | jq .
```

## Fix that worked last time

- Caffeine `recentCustomers` unbounded — added `.maximumSize(1000)`
  in `CustomerService.java`, heap dropped from 85 % → 62 %.
- OTel span leak on Kafka consumer — `span.end()` was missed on
  consumer poll failure; added a try/finally.

## When to escalate

- Heap > 92 % for 10 min + G1 spending > 30 % on GC (check
  `jvm_gc_pause_seconds_sum / scrape_duration_seconds`) → imminent
  OOMKill. Restart the pod and raise `-Xmx` in the Deployment.
- Heap pressure persists after restart → real leak. Take a heap
  dump at T+5 min and T+15 min; diff the retained size to find
  what's still growing.
