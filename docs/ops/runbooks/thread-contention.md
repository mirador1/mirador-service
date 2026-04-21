# Alert: `MiradorThreadContention`

Fired when > 30 % of JVM threads are in BLOCKED state for ≥ 5 min. Classic
"something is locked" signal. Warning-tier — high BLOCKED rates don't
immediately fail the app but predict a latency cliff.

## Quick triage (60 seconds)

```bash
# 1. Current thread-state breakdown:
curl -s 'http://localhost:9090/api/v1/query' --data-urlencode 'query=
  sum by (state) (jvm_threads_states_threads{job=~"mirador.*"})
' | jq '.data.result[] | {state: .metric.state, count: .value[1]}'

# 2. Thread dump via actuator:
curl -s http://localhost:8080/actuator/threaddump > threaddump.json
jq '.threads[] | select(.threadState == "BLOCKED") | {name, lockOwnerName, lockName}' \
   threaddump.json | head -40
```

The `lockName` tells you WHAT they're waiting on — usually a
`ReentrantLock` field path or a `synchronized` monitor address.

## Likely root causes (virtual threads + platform threads)

1. **Synchronized block on a hot path** — virtual threads **pin** to
   their carrier when entering `synchronized`. A few virtual threads
   pinned on the same monitor drain the carrier pool fast. Pyroscope's
   `lock` profile highlights the `synchronized` by code frame.
2. **`ReentrantLock` contention** — less pinning risk than `synchronized`
   for virtual threads, but still queues virtual threads if held long.
   Check `LockSupport.park` stacks in the thread dump.
3. **Connection pool starvation** — HikariCP blocks callers waiting for
   a connection. Shows up as BLOCKED threads with `lockName`
   referencing `com.zaxxer.hikari`.
4. **Caffeine cache loader contention** — `Caffeine.loadAll()` serialises
   per-key; 100 threads requesting the same key all BLOCKED on the
   single loader task. Solution: `.refreshAfterWrite` + `AsyncLoadingCache`.

## Commands to run

```bash
# Pyroscope — narrow to the lock profile type for the last 15 min.
# URL: <grafana>/explore then select Pyroscope → profile_type="mutex"
#      → application="mirador"

# JFR (Java Flight Recorder) for a 60-s snapshot via actuator:
# Only works if management.endpoint.jfr.enabled=true AND -XX:StartFlightRecording
curl -X POST http://localhost:8080/actuator/jfr/start?duration=60s
# …wait 60 s…
curl -o contention.jfr http://localhost:8080/actuator/jfr/dump

# Open contention.jfr in JMC → Lock Instances tab.
```

## Fix that worked last time

- Replaced a `synchronized` block in `AggregationService.loadStats()`
  with a `ConcurrentHashMap` on a read-heavy map — virtual-thread
  pinning dropped from 8 % to 0.1 %.
- Raised HikariCP `maximumPoolSize` 10 → 20 under chaos-test load;
  BLOCKED ratio dropped below threshold.

## When to escalate

- BLOCKED > 60 % → the service is functionally down even if `/actuator/health`
  still reports UP. Take a thread dump + Pyroscope snapshot for post-mortem,
  then restart the pod to recover.
- Contention reproduces after restart → code regression. `git log -p` the
  last 10 commits touching `synchronized` / `Lock` / `HikariCP` config.
