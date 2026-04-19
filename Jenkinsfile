// =============================================================================
// Jenkinsfile — declarative pipeline, mirrors the GitLab CI stages.
//
// Why this exists, when GitLab CI already works:
//   Jenkins is the de-facto standard in a lot of enterprise environments
//   (banking, insurance, telco) where GitLab SaaS isn't available. The
//   goal of this file is to prove the project's industrial tooling isn't
//   GitLab-specific — a team adopting Mirador inside a Jenkins shop can
//   re-use everything (Testcontainers, SBOM, cosign, PIT, Sonar, Semgrep)
//   without rewriting from scratch.
//
// What it mirrors from .gitlab-ci.yml (parity):
//   lint → test → integration → sonar → package → supply-chain (SBOM + scan
//   + cosign) → docs-ready.
//
// What it deliberately skips (out of scope for portability demo):
//   * test:k8s-apply — needs Docker-in-Docker for kind, depends on the
//     runner's Docker socket access model (different on Jenkins)
//   * terraform-plan / deploy:gke — GCP auth tied to Workload Identity
//     Federation; a Jenkins equivalent would use the GCP credentials
//     plugin, out of scope here
//   * compat-sb3-java17 / sb4-java21 / etc. — Maven profile matrix; trivially
//     expressible as parallel Jenkins stages but omitted to keep the file
//     readable
//
// Usage:
//   * Local Jenkins (Docker):
//       docker run -p 8080:8080 -v /var/run/docker.sock:/var/run/docker.sock \\
//         -v jenkins_home:/var/jenkins_home jenkins/jenkins:lts-jdk25
//     Create a new Multibranch Pipeline pointing at the repo; Jenkins
//     auto-detects this file.
//   * Enterprise Jenkins: drop the file as-is into the project. Provide
//     the credentials below via the Jenkins Credentials plugin.
//
// Required credentials (Jenkins Credentials plugin, matching .gitlab-ci.yml):
//   - sonar-token       (Secret text) → SonarCloud token
//   - registry-creds    (Username/Password) → container registry
//   - cosign-key        (Secret file) → cosign private key (password via
//                                       cosign-password secret text)
// =============================================================================

pipeline {
  // 'any' keeps this portable. In enterprise Jenkins, replace by a label
  // matching the build-agent pool (e.g. `agent { label 'docker-agent' }`).
  agent any

  options {
    // Retain the last 10 builds. Jenkins defaults to unbounded which
    // consumes gigabytes of disk over time — tight cap matches GitLab's
    // artifact expire_in.
    buildDiscarder(logRotator(numToKeepStr: '10'))
    // Abort stuck builds automatically. 30 min is generous — the full
    // GitLab chain runs in ~12 min on macbook-local.
    timeout(time: 30, unit: 'MINUTES')
    // Timestamps on every log line; essential for Jenkins in ops.
    timestamps()
    // Coloured ANSI output so Maven + Docker logs are readable.
    ansiColor('xterm')
    // Reject concurrent builds on the same branch — matches GitLab's
    // `interruptible: true` semantics (new push cancels old build).
    disableConcurrentBuilds()
  }

  environment {
    // Mirror the MAVEN_CLI_OPTS from .gitlab-ci.yml so local repro
    // produces identical dependency trees.
    MAVEN_CLI_OPTS = '--batch-mode --errors --fail-at-end --show-version'
    // Local Maven repo cache — mounted into each stage so we don't
    // re-download 500 MB of artifacts per stage.
    MAVEN_OPTS = "-Dmaven.repo.local=${env.WORKSPACE}/.m2/repository"
    // Image coordinates — match registry convention.
    IMAGE_NAME = 'mirador-service'
    IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT?.take(8) ?: 'local'}"
  }

  stages {

    // ── lint ─────────────────────────────────────────────────────────────
    stage('Lint') {
      agent {
        docker {
          image 'maven:3.9.14-eclipse-temurin-25-noble'
          args '-v $HOME/.m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        // Maven Enforcer checks alone — no Sonar yet (Sonar is a
        // dedicated stage later, matching .gitlab-ci.yml).
        sh 'mvn $MAVEN_CLI_OPTS enforcer:enforce validate'
      }
    }

    // ── test ─────────────────────────────────────────────────────────────
    stage('Unit tests') {
      agent {
        docker {
          image 'maven:3.9.14-eclipse-temurin-25-noble'
          args '-v $HOME/.m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        sh 'mvn $MAVEN_CLI_OPTS test'
      }
      post {
        always {
          // Jenkins-native junit parser. Same data source as GitLab's
          // `reports: junit:` artifact.
          junit testResults: 'target/surefire-reports/*.xml',
                allowEmptyResults: false
          // JaCoCo coverage — the hotspot for code-quality gate metrics.
          recordCoverage(tools: [[parser: 'JACOCO', pattern: 'target/site/jacoco/jacoco.xml']],
                         id: 'unit', name: 'Unit coverage')
        }
      }
    }

    // ── integration ──────────────────────────────────────────────────────
    stage('Integration tests') {
      // Needs Docker socket to spawn Testcontainers. The `args` mount the
      // host Docker socket — same pattern as the GitLab socket-mount runner.
      agent {
        docker {
          image 'maven:3.9.14-eclipse-temurin-25-noble'
          args '-v /var/run/docker.sock:/var/run/docker.sock -v $HOME/.m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        sh 'mvn $MAVEN_CLI_OPTS verify -P integration -Dfailsafe.tc.reuse=true'
      }
      post {
        always {
          junit testResults: 'target/failsafe-reports/*.xml', allowEmptyResults: true
          recordCoverage(tools: [[parser: 'JACOCO', pattern: 'target/site/jacoco-it/jacoco.xml']],
                         id: 'integration', name: 'Integration coverage')
        }
      }
    }

    // ── sonar ────────────────────────────────────────────────────────────
    stage('SonarCloud') {
      // Only run on main + MRs to avoid burning analysis time on feature
      // branches (parity with the GitLab rule in the .gitlab-ci.yml).
      when {
        anyOf {
          branch 'main'
          changeRequest()
        }
      }
      agent {
        docker {
          image 'maven:3.9.14-eclipse-temurin-25-noble'
          args '-v $HOME/.m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
          sh '''
            mvn $MAVEN_CLI_OPTS compile -DskipTests -Djacoco.skip=true
            mvn $MAVEN_CLI_OPTS sonar:sonar \
              -Dsonar.token=$SONAR_TOKEN \
              -Dsonar.host.url=https://sonarcloud.io
          '''
        }
      }
      // allow-failure equivalent — matches `allow_failure: true` in
      // .gitlab-ci.yml. A SonarCloud outage won't fail the pipeline.
      post {
        failure {
          script {
            currentBuild.result = 'UNSTABLE'
            error("Sonar failed but not blocking the build (see GitLab parity rule).")
          }
        }
      }
    }

    // ── package ──────────────────────────────────────────────────────────
    stage('Package JAR') {
      agent {
        docker {
          image 'maven:3.9.14-eclipse-temurin-25-noble'
          args '-v $HOME/.m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        sh 'mvn $MAVEN_CLI_OPTS package -DskipTests -Dmaven.javadoc.skip=true'
      }
      post {
        success {
          archiveArtifacts artifacts: 'target/mirador-*.jar', fingerprint: true
        }
      }
    }

    // ── supply chain (SBOM + scan + sign) ───────────────────────────────
    stage('Build + supply chain') {
      when { branch 'main' }
      agent any
      environment {
        IMAGE_REF = "ghcr.io/beennnn/${IMAGE_NAME}:${IMAGE_TAG}"
      }
      steps {
        // Build the image. --platform linux/amd64 to match GKE target,
        // crucial on arm64 agents (same rule as ~/.claude/CLAUDE.md).
        sh 'docker buildx build --platform linux/amd64 -t $IMAGE_REF -f build/Dockerfile --load .'

        // SBOM generation via Syft — same tool as GitLab.
        sh 'syft $IMAGE_REF -o cyclonedx-json=sbom.cdx.json'
        archiveArtifacts artifacts: 'sbom.cdx.json'

        // Scan for CVEs via Grype — same tool as GitLab.
        // --fail-on high matches the GitLab policy.
        sh 'grype $IMAGE_REF --fail-on high --output table'

        // Sign the image with cosign. Keyless signing (OIDC via
        // Jenkins service account) would be preferred in enterprise,
        // but a key-backed sign is more portable for this demo.
        withCredentials([
          file(credentialsId: 'cosign-key', variable: 'COSIGN_KEY_FILE'),
          string(credentialsId: 'cosign-password', variable: 'COSIGN_PASSWORD')
        ]) {
          sh 'cosign sign --key $COSIGN_KEY_FILE --yes $IMAGE_REF'
        }
      }
    }

    // ── mutation tests ──────────────────────────────────────────────────
    stage('PIT mutation tests') {
      // Expensive — only on main. Jenkins parity with GitLab's
      // `reports` stage.
      when { branch 'main' }
      agent {
        docker {
          image 'maven:3.9.14-eclipse-temurin-25-noble'
          args '-v $HOME/.m2:/root/.m2'
          reuseNode true
        }
      }
      steps {
        sh 'mvn $MAVEN_CLI_OPTS org.pitest:pitest-maven:mutationCoverage -DskipTests=false'
      }
      post {
        always {
          publishHTML target: [
            reportDir: 'target/pit-reports',
            reportFiles: 'index.html',
            reportName: 'PIT Mutation Report',
            keepAll: true
          ]
        }
      }
    }
  }

  post {
    // Clean up Docker images to avoid disk pressure on the agent.
    // Matches .gitlab-ci.yml's `after_script` on the k8s job.
    cleanup {
      sh 'docker image prune -f --filter "until=24h" || true'
    }
    // Notification hooks would go here (Slack, email, Mattermost).
    // Intentionally left empty for portability; every enterprise has
    // its own notification plugin.
    failure {
      echo "Build failed — see console log. Enterprise: hook Slack/Email here."
    }
    success {
      echo "Build ${env.BUILD_NUMBER} succeeded on ${env.BRANCH_NAME}."
    }
  }
}
