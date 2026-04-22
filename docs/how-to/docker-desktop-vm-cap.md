# Raise the Docker Desktop VM memory cap (macOS)

**Why this is needed**: Mirador's CI uses kind (Kubernetes-in-Docker) for
`test:k8s-apply` + `test:k8s-apply-prom`. Each kind cluster takes ~700 MB
RSS; running them serially with chaos-mesh + kube-prometheus-stack CRDs
loaded means peak ~2.5 GB. With Maven, integration tests, and the dev
docker-compose stack also sharing the Docker Desktop Linux VM, the
default 7.6 GB cap fills up and triggers cascading kind control-plane
OOMs (observed in pipelines #610, #612 — see `docs/audit/quality-thresholds-2026-04-21.md`).

Both jobs are currently shielded with `allow_failure: true` (commits
3dcb2d0, d54c5e9; see [ADR-0049](../adr/0049-ci-shields-with-dated-exit-tickets.md))
with **Revisit: 2026-05-21**. Raising the VM cap is the cleanest exit
path — alternative is moving the jobs to a SaaS amd64 runner (cost-bearing).

## Recommended approach — Docker Desktop UI (2 minutes)

Safest path. Same outcome as the JSON edit below but with input
validation by Docker Desktop itself.

1. Open **Docker Desktop** (menu bar Mac icon → "Open Docker Desktop").
2. Click the **gear icon** (top right) → **Settings**.
3. Left sidebar → **Resources** → **Advanced** (sub-tab).
4. Drag the **Memory** slider from 7.6 GB to **12 GB minimum** (16 GB
   recommended if you have ≥ 32 GB host RAM and frequently run multiple
   compose stacks in parallel).
5. Optional but recommended:
   - **CPU** : ≥ 4 cores (default 4 is OK)
   - **Swap** : ≥ 2 GB (default 1 GB occasionally tight when JVMs warm up)
   - **Disk image size** : ≥ 100 GB (Mirador's build cache + 156 images
     during a busy session can hit 80 GB)
6. Click **Apply & restart** (bottom right). Docker Desktop restarts in
   ~30 seconds; running containers are stopped + restarted in their
   previous state.
7. Verify with: `docker system info | grep -E "Memory|CPUs"`. Expect
   "Total Memory: ≥ 11.5 GiB".

After verification, retire the shields with:

```bash
cd /Users/benoitbesson/dev/workspace-modern/mirador-service
# Remove `allow_failure: true` + the 8-line ticket comment block from
# both `test:k8s-apply` and `test:k8s-apply-prom` jobs in .gitlab-ci.yml
$EDITOR .gitlab-ci.yml
git commit -m "ci: retire k8s-apply shields after Docker VM raise" .gitlab-ci.yml
```

## Power-user alternative — edit `settings-store.json` directly

Use this path only if you've done it before. Wrong key name silently
disables the change OR prevents Docker Desktop from starting.

1. **Quit Docker Desktop completely** (right-click menu bar icon → Quit).
   Editing while Docker is running gets overwritten on quit.
2. Find the settings file. Path varies by Docker Desktop version:
   ```bash
   # Docker Desktop 4.30+
   ls ~/Library/Group\ Containers/group.com.docker/settings-store.json
   # Older versions:
   ls ~/Library/Group\ Containers/group.com.docker/settings.json
   ```
3. Backup first:
   ```bash
   cp <path-found> <path-found>.bak
   ```
4. Edit the file. Look for the `MemoryMiB` key (note: capital M).
   Change `7680` → `12288` (or `16384` for 16 GB).
5. Validate the JSON is still parseable: `jq . <path-found> > /dev/null && echo OK`.
6. Restart Docker Desktop. Verify as in step 7 above.
7. If Docker Desktop won't start, restore the `.bak` and use the UI path.

## Disk pressure mitigation (orthogonal)

Even with VM cap raised, image bloat eats disk. From session 2026-04-21
the trio docker prune saved 18.7 GB. Per the new "Docker cleanup cadence"
rule (`~/.claude/CLAUDE.md`), run at session start + every 30 min of
active CI:

```bash
docker container prune -f
docker builder prune -f
docker image prune -f
# If > 80 GB total or > 100 images:
docker image prune -a -f
```

Never `docker volume prune` without confirmation — postgres, sonarqube,
flyway state lives there.

## Validation that the shield removal is justified

Before removing the `allow_failure: true` from `test:k8s-apply[-prom]`:

1. Run a fresh local kind cluster:
   ```bash
   kind create cluster --name vm-cap-test
   kubectl get nodes  # confirm Ready
   ```
2. Apply the chaos-mesh + kube-prom-stack CRDs locally:
   ```bash
   kubectl apply -k deploy/kubernetes/overlays/local-prom
   ```
3. Watch with `kubectl get pods -A -w` for 5 minutes. No `OOMKilled`,
   no `CrashLoopBackOff` on `local-path-provisioner` or any control-
   plane component.
4. Tear down: `kind delete cluster --name vm-cap-test`.

If steps 1-3 succeed, the shield is genuinely retired. Push the
`.gitlab-ci.yml` change, run a full pipeline, confirm green.

## Cross-references

- [ADR-0049](../adr/0049-ci-shields-with-dated-exit-tickets.md) — the
  shields pattern this how-to closes
- `~/.claude/CLAUDE.md` → "Clean Docker regularly — don't wait for OOM"
- `docs/audit/quality-thresholds-2026-04-21.md` — Phase A, names this
  exit path explicitly
