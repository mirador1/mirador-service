# `./run.sh all` hangs or fails to bring up the compose stack

## Quick triage (30 seconds)

```bash
docker ps                                    # Docker Desktop up?
docker system df                             # running out of disk?
./run.sh status                              # what's UP vs DOWN?
```

If Docker Desktop is down → start it (most common cause by far).
If `docker system df` shows Reclaimable GB > 20 → run `docker container
prune -f && docker volume prune -f && docker builder prune -f`.

## Likely root causes (in order of frequency)

1. **Docker Desktop not running** — the socket at
   `~/.docker/run/docker.sock` doesn't exist. All docker commands fail.
2. **Port already in use** — typically :8080 (another Spring Boot
   instance running), :5432 (host Postgres), :9090 (Keycloak admin).
3. **Disk full on the Docker volume** — tmpfs runs out after heavy
   JaCoCo + Maven cache + LGTM image builds.
4. **Image pull timeout** — corporate VPN blocks Docker Hub / ghcr.io.
5. **docker-compose.yml or docker-compose.observability.yml has a
   syntax error** from a recent edit.

## Commands to run

```bash
# 1. Which services are running vs not
./run.sh status

# 2. Failed containers' last logs
docker ps -a --filter status=exited --format "table {{.Names}}\t{{.Status}}" \
  | head -10
docker logs <container-name> --tail 50

# 3. Port conflicts
lsof -iTCP:8080,5432,9090,3000,9080,4200 -sTCP:LISTEN

# 4. Disk pressure
docker system df                              # reclaimable space
df -h /                                       # host disk

# 5. docker compose syntax
docker compose -f docker-compose.yml config --quiet
docker compose -f docker-compose.observability.yml config --quiet
```

## Fix that worked last time

- **Docker down** — `open -a Docker`, wait 20s, re-run `./run.sh all`.
- **Port conflict on 8080** — another mirador instance was still alive:
  ```
  docker ps --filter publish=8080 --format "{{.Names}}" | xargs docker stop
  ```
- **Disk pressure** — the sequence in `CLAUDE.md` session prechecks:
  ```
  docker container prune -f
  docker volume prune -f   # WARNING: drops named volumes, loses Postgres data
  docker builder prune -f
  ```
- **VPN / image pull** — disconnect VPN, or pre-pull through a
  reachable mirror:
  ```
  docker pull ghcr.io/grafana/otel-lgtm:0.8.5
  docker pull postgres:17-alpine
  ```
- **Compose syntax** — `docker compose config` prints the resolved YAML
  with the error line number.

## When to escalate

`./run.sh nuke` wipes everything (containers + volumes + build
artefacts) so you can start from a guaranteed clean state. Use it
when you've tried individual fixes for >10 min without progress:

```bash
./run.sh nuke
./run.sh all
```

This loses the local Postgres data. For a portfolio demo that's
acceptable — `V*__seed.sql` migrations recreate the schema on boot.
