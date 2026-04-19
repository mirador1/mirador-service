# Jenkins parity — why this project also ships a `Jenkinsfile`

## Why Jenkins matters

GitLab CI is the canonical CI for this project — everything in
[`.gitlab-ci.yml`](../../.gitlab-ci.yml) is live, tested, green. But a
lot of enterprise shops (banking, insurance, telco, public sector)
are locked into **Jenkins** for historical + regulatory reasons:

- Self-hosted on the private network, no SaaS dependency.
- On-premise audit trails the compliance team knows how to read.
- Plugin ecosystem that's been vetted for 15+ years.

A project that only works on GitLab is a project that can't be
adopted in those shops without a full CI rewrite. Shipping a
`Jenkinsfile` alongside `.gitlab-ci.yml` proves the **industrial
tooling** (Testcontainers, SBOM, cosign, Sonar, Semgrep, PIT) is
portable, not GitLab-specific.

This isn't about running both CIs at the same time. It's a parity
demonstrator: a team adopting Mirador inside a Jenkins shop can
cherry-pick this pipeline and have a working build on day one.

## What the `Jenkinsfile` covers

| Stage (GitLab) | Stage (Jenkins) | Parity |
|---|---|---|
| `lint:enforcer` | `Lint` | ✅ |
| `unit-test` | `Unit tests` | ✅ (JUnit + JaCoCo) |
| `integration-test` | `Integration tests` | ✅ (Testcontainers via docker-sock mount) |
| `sonar-analysis` | `SonarCloud` | ✅ (same token, `allow_failure: true` → `UNSTABLE`) |
| `build-jar` | `Package JAR` | ✅ (artifact archived) |
| `docker-build` + `sbom:syft` + `trivy:scan` + `cosign:sign` | `Build + supply chain` | ✅ (one consolidated stage) |
| `pit-mutation` | `PIT mutation tests` | ✅ (HTML report published) |
| `test:k8s-apply` (kind-in-CI) | — | **skipped** (docker-in-docker model differs) |
| `terraform-plan` / `deploy:gke` | — | **skipped** (WIF → needs GCP creds plugin) |
| `compat-sb3-java17`, etc. | — | **skipped** (matrix is trivial to add, omitted for readability) |

The **skipped** stages are skipped on purpose, not by accident:

- `test:k8s-apply` requires kind running sibling-socket to the runner,
  which works differently on Jenkins (a cloud agent vs. a static
  agent). Possible but adds ~80 lines that obscure the main story.
- Terraform + GKE deploy requires Google Cloud credentials plugin +
  Workload Identity Federation. Both exist on Jenkins; the integration
  is specific to each enterprise's security policy.
- Matrix compat builds (SB3/SB4, Java 17/21/25) are 5 parallel stages
  that double the file size without adding a new concept.

## What this demonstrates

1. **Tools are portable.** Every security + quality gate used on
   GitLab (Syft, Grype, cosign, Sonar, Semgrep, PIT, Testcontainers)
   runs unchanged under Jenkins.
2. **Parity doesn't mean duplication of intent.** Both files express
   "lint → test → scan → sign → publish"; they differ only in syntax
   and plugin names.
3. **Credentials abstraction is the same shape.** GitLab CI variables
   → Jenkins Credentials plugin. One-line mapping, same masking.
4. **The project doesn't lock the team into GitLab SaaS.** Adoption
   under a Jenkins-only org doesn't need architectural rework.

## Local test (before touching an enterprise Jenkins)

```bash
# Bring up a local Jenkins with Docker-in-Docker capabilities.
docker run -d --name mirador-jenkins-test \
  -p 8080:8080 -p 50000:50000 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v jenkins_home:/var/jenkins_home \
  jenkins/jenkins:lts-jdk25

# Initial admin password:
docker exec mirador-jenkins-test cat /var/jenkins_home/secrets/initialAdminPassword

# In the web UI (http://localhost:8080):
#   1. Install suggested plugins + "Docker Pipeline", "JaCoCo", "Pipeline: Stage View"
#   2. New Item → Multibranch Pipeline
#      → Git repo: https://github.com/mirador1/mirador-service
#      → Branch Sources: scan all branches
#   3. Credentials → System → Global credentials (unrestricted)
#      → Add "sonar-token" (Secret text), "cosign-password" (Secret text),
#        "cosign-key" (Secret file)
#   4. Scan Multibranch Pipeline — it finds the Jenkinsfile, runs.
```

## Differences to be aware of, compared to GitLab CI

| Concern | GitLab | Jenkins |
|---|---|---|
| Cancel-on-new-push | `interruptible: true` | `disableConcurrentBuilds()` + build discarder |
| Allow-failure | `allow_failure: true` | `catchError() { stage('X') {...} }` or `UNSTABLE` |
| Artifacts auto-expire | `expire_in: 1 week` | `logRotator(numToKeepStr: '10')` |
| Parallel stages | jobs in same stage run parallel by default | explicit `parallel {}` block |
| Env vars | `variables:` + file-level | `environment {}` + per-stage + withCredentials |

None of these is conceptually harder — they just have different
syntax. A team moving from GitLab to Jenkins rewrites the orchestration
file; the underlying tools (Maven, Docker, cosign) are untouched.

## When NOT to pick Jenkins

- **Public open-source project** — GitHub Actions + GitLab CI both
  have generous free tiers. Jenkins adds a self-hosting burden.
- **Small team, no compliance constraint** — GitLab SaaS is simpler
  end-to-end.
- **Greenfield startup** — GitLab CI + Renovate + release-please is
  faster to set up than any Jenkins + Jenkins-Library combo.

The `Jenkinsfile` exists for the 30% of enterprises where Jenkins is
the locked-in choice. If you're not in that 30%, stay on GitLab CI.

## What to add when adopting this in a real Jenkins shop

1. **Credentials plugin** — `sonar-token`, `registry-creds`,
   `cosign-key`, `cosign-password` as shown in the file header.
2. **Webhook from GitHub / GitLab to Jenkins** — so pushes trigger
   builds. Jenkins URL + shared secret, standard operation.
3. **Parallel matrix** for the SB3/SB4 × Java 17/21/25 compat grid
   if the team needs it. One Groovy `parallel {}` block.
4. **Slack / Mattermost notification** in `post { failure {} }`.
5. **Agent label** matching your build-agent pool (replace `agent any`).
6. **Terraform / GCP plugin** if you also want Jenkins to deploy.

None of these is in the current file on purpose — they are
environment-specific and belong to the adopting team, not the
demonstrator.
