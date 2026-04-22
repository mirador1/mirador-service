# Dev tooling — desktop apps, CLI, IDE plugins, environment hookup

This page lists the desktop / IDE tools the project expects a developer to
have locally, how to **install** them, how to **connect** them to each of
the three environments (compose, kind, GKE prod), and how to wire them to
**GitLab**. It intentionally excludes pure CLI (`kubectl`, `gh`, `docker`,
`mvn`, `npm`) — those are prerequisites, not tooling choices.

## TL;DR — install once

```bash
# Containers + Kubernetes
brew install --cask docker openlens
brew install k9s kubectx                      # optional TUI + context switcher

# IDE — pick one
brew install --cask visual-studio-code        # lightweight, best with extensions
brew install --cask intellij-idea             # payant Ultimate, best for Spring

# GitLab
brew install glab                             # CLI — drives MRs + pipelines
glab auth login                               # one-shot token setup
```

## Desktop apps

### OpenLens — Kubernetes IDE

- **Install**: `brew install --cask openlens` (fork gratuit du Lens
  officiel passé payant).
- **How it connects**: reads `~/.kube/config` on launch, lists every
  context, colour-codes status. No plugin needed.
- **Setup for this project**:
  - `kind-mirador-local` appears automatically after
    `kind create cluster --name mirador-local --config deploy/kubernetes/kind-config.yaml`.
  - `gke_…mirador-prod` appears automatically after `bin/cluster/connect-prod.sh`
    (or a manual `gcloud container clusters get-credentials`).
- **What it's good at**: pod logs / exec / events, CRD browsing (Argo
  Rollouts, Chaos Mesh, ExternalSecret show up as first-class objects),
  multi-cluster side-by-side.

### Docker Desktop — containers + local K8s

- **Install**: `brew install --cask docker`.
- **Licence**: free for ≤250 employees AND ≤$10M revenue. Commercial
  alternative: Rancher Desktop or OrbStack.
- **Settings worth tuning** (Preferences):
  - Resources → CPU **4+**, RAM **8 GB+** (the compose stack with Ollama
    needs it).
  - Kubernetes → **disabled** (we use kind, not Docker Desktop's K8s —
    saves ~1.5 GB RAM).

### Rancher Desktop (alternative to Docker Desktop)

- **Install**: `brew install --cask rancher`.
- **Why**: free for any size org, includes k3s if you want container
  runtime + Kubernetes in one app.
- **Trade-off**: slightly slower Docker CLI than Docker Desktop on M1/M2.

### K9s — terminal-first K8s

- **Install**: `brew install k9s`.
- **Usage**: `k9s` in any terminal — ultra-fast pod navigator, same
  kubeconfig as OpenLens.
- **When to reach for it**: live debugging during an incident (faster
  than clicking in OpenLens), pair with `kubectx` / `kubens` for context
  / namespace switching.

## IDEs

### VS Code — recommended lightweight IDE

Extensions to install (all free):

| Extension                           | Why                                                        |
|-------------------------------------|------------------------------------------------------------|
| `ms-kubernetes-tools.vscode-kubernetes-tools` | K8s cluster explorer, apply-from-editor, exec into pod.    |
| `ms-azuretools.vscode-docker`       | Docker images / containers / compose in sidebar.           |
| `gitlab.gitlab-workflow`            | MR review in-editor, CI pipeline status, clone helper.     |
| `vscjava.vscode-spring-boot-dashboard` | Run / stop / profile Spring Boot apps.                  |
| `redhat.vscode-yaml`                | K8s CRD schema validation (Argo, Chaos, ExternalSecret).  |
| `redhat.java` + `vmware.vscode-boot-dev-pack` | Language server + Spring tooling.              |

Quick setup:

```bash
code --install-extension ms-kubernetes-tools.vscode-kubernetes-tools
code --install-extension ms-azuretools.vscode-docker
code --install-extension gitlab.gitlab-workflow
```

### IntelliJ IDEA Ultimate — heavyweight IDE

Plugins (bundled in Ultimate — Community needs swaps):

| Plugin                  | Notes                                                              |
|-------------------------|---------------------------------------------------------------------|
| **Kubernetes**          | Same features as OpenLens but inside the IDE.                      |
| **Docker**              | Dockerfile syntax + compose run configurations.                    |
| **GitLab**              | Bundled since IntelliJ 2023.3 — handles MR, clone, pipelines.      |
| **Spring Boot**         | Ultimate only. Run configs + live beans view.                      |
| **Database Tools**      | Ultimate only. Points at localhost:5432 (compose), localhost:15432 (kind tunnel) or localhost:25432 (prod tunnel). |

Community-edition alternatives: disable bundled GitLab → use the `glab`
CLI from the integrated terminal; Kubernetes → use OpenLens in parallel.

## GitLab integration

The project lives at <https://gitlab.com/mirador1/mirador-service> +
<https://gitlab.com/mirador1/mirador-ui>. Two auth paths coexist.

### `glab` CLI

```bash
brew install glab
glab auth login --hostname gitlab.com          # pick "Web" — browser opens, paste the URL back
glab auth status                               # confirms token works
```

Daily commands:

```bash
glab mr create --fill-commit-body              # MR from current branch, body pre-filled
glab mr merge 77 --auto-merge --squash=false --remove-source-branch=false
glab pipeline list --per-page 5
glab ci trace <job-id>                         # stream a job's logs
```

### IDE auth

- **VS Code**: GitLab Workflow extension reads a personal access token
  via `Cmd+Shift+P → "GitLab: Set GitLab Personal Access Token"`. Token
  scope: `api, read_repository, write_repository`.
- **IntelliJ**: `Settings → Version Control → GitLab → Add account` →
  same token. Enables "Create MR" from the Git menu.

### SSH vs HTTPS

Use SSH for pushes (no token expiration, works everywhere):

```bash
ssh-keygen -t ed25519 -C "benoit.besson@gmail.com"
cat ~/.ssh/id_ed25519.pub | pbcopy             # paste into GitLab → SSH Keys
git remote set-url origin git@gitlab.com:mirador1/mirador-service.git
```

## Environment connection matrix

Three environments, three port decades (compose = upstream, kind = +10000, prod = +20000).

| Environment    | Admin plane (kubeconfig)                        | App services (port-forward)     |
|----------------|-------------------------------------------------|----------------------------------|
| **compose**    | n/a                                             | `./run.sh all` — upstream ports  |
| **kind**       | `kubectl config use-context kind-mirador-local` | `bin/cluster/port-forward/kind.sh --daemon` — 1xxxx |
| **GKE prod**   | `bin/cluster/connect-prod.sh`                           | `bin/cluster/port-forward/prod.sh --daemon` — 2xxxx |

### Concretely

```bash
# Compose (dev)
./run.sh all                       # infra containers, ports 8080/3000/…

# kind (local K8s mirror)
kind create cluster --name mirador-local --config deploy/kubernetes/kind-config.yaml
kubectl apply -k deploy/kubernetes/overlays/local
bin/cluster/port-forward/kind.sh --daemon            # tunnels on 1xxxx (18080, 13000, 14242, …)
bin/cluster/pgweb/kind-up.sh               # optional: pgweb on 8082 for the UI's Database page

# GKE (ephemeral prod — ADR-0022)
bin/cluster/demo/up.sh                     # ~13 min
bin/cluster/connect-prod.sh                # gcloud credentials + open OpenLens
bin/cluster/port-forward/prod.sh --daemon            # tunnels on 2xxxx (28080, 23000, 24242, …)
bin/cluster/pgweb/prod-up.sh               # optional: pgweb on 8083
# …work…
bin/cluster/port-forward/stop.sh                     # stops both kind + prod tunnels
bin/cluster/demo/down.sh
```

## Deep-link URIs (opt-in, for the UI)

Most desktop tools expose a custom URI scheme that opens them focused on
a specific resource. Useful if the Angular UI wants "Open this file /
pod / image in …" buttons. Tracked as a UI-repo task.

| Tool             | URI template                                                | Example                                               |
|------------------|-------------------------------------------------------------|-------------------------------------------------------|
| **VS Code**      | `vscode://file/<absolute-path>:<line>`                      | `vscode://file//Users/you/dev/mirador-service/pom.xml:245` |
| **IntelliJ IDEA**| `idea://open?file=<abs-path>&line=<n>`                      | `idea://open?file=/Users/you/dev/mirador-service/pom.xml&line=245` |
| **OpenLens**     | `lens://app/catalog`                                        | opens OpenLens at the cluster catalog                 |
| **Docker Desktop** | `docker-desktop://dashboard/container/<id>`               | `docker-desktop://dashboard/container/mirador-db`     |
| **GitLab**       | https URL                                                   | `https://gitlab.com/mirador1/mirador-service/-/blob/main/pom.xml#L245` |

The Angular UI's "Architecture" + "Database" + "Quality" pages already
have placeholders for such links — wiring them up uses nothing beyond a
standard `<a href="vscode://…">` (the OS routes the scheme to the
registered app). Fails silently if the target app is not installed.

## Going further

- `bin/cluster/connect-prod.sh` — one command to refresh GKE kubeconfig + open
  OpenLens + reminder about `bin/cluster/port-forward/prod.sh`.
- `bin/cluster/port-forward/prod.sh`, `bin/cluster/port-forward/status.sh`, `bin/cluster/port-forward/stop.sh` — the whole
  tunnel lifecycle for app services (per ADR-0025).
- [ADR-0025](../adr/0025-ui-local-only-no-public-prod-ingress.md) —
  why prod has no public URL.
- [technologies.md](../reference/technologies.md) — every runtime / CI /
  quality tool used, with rationale.
