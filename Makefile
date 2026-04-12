# =============================================================================
# Makefile — local CI equivalent
#
# Mirrors the exact Maven commands used in .gitlab-ci.yml and
# .github/workflows/ci.yml so you can validate a branch locally before
# pushing, without depending on remote CI runners or quota.
#
# Quick reference:
#   make check        unit tests only (fast, no Docker)
#   make verify       full pipeline: lint + unit + integration + coverage
#   make ci           alias for verify (main entry point)
#   make lint         Dockerfile linting with hadolint
#   make integration  IT + SpotBugs + JaCoCo (needs Docker for Testcontainers)
#   make package      build the fat JAR (tests already validated)
#   make docker       build the local JVM Docker image
#   make clean        wipe target/
#   make install-tools install hadolint + lefthook via Homebrew
#
# Prerequisites:
#   - Java 25 + Maven wrapper (./mvnw) — already in the repo
#   - Docker Desktop — for integration tests (Testcontainers) and docker target
#   - hadolint — for lint target (auto-fallback to Docker image if absent)
#   - lefthook — for git hooks (optional, install with: make install-tools)
# =============================================================================

# Maven wrapper with the same flags used in CI:
#   --batch-mode            suppress interactive prompts + coloured output
#   --errors                print full stack traces on errors
#   --no-transfer-progress  suppress download progress bars
MVNW  := ./mvnw
MAVEN := $(MVNW) --batch-mode --errors --no-transfer-progress

# Local Docker image tag (never pushed to a registry)
IMAGE := spring-api:local

# Detect whether hadolint is installed locally; fall back to Docker image
# so the lint target works even without a local hadolint binary.
HADOLINT := $(shell command -v hadolint 2>/dev/null)

.PHONY: help lint test check integration verify package docker clean ci install-tools

# ---------------------------------------------------------------------------
# Default target: print usage
# ---------------------------------------------------------------------------
help:
	@echo ""
	@echo "  make check         Unit tests only — fast, no Docker required"
	@echo "  make verify        Full pipeline: lint + unit + integration + coverage gate"
	@echo "  make ci            Alias for verify"
	@echo "  make lint          Dockerfile linting (hadolint)"
	@echo "  make test          Unit tests (Maven Surefire, *ITest excluded)"
	@echo "  make integration   IT + SpotBugs + JaCoCo (Maven Failsafe, needs Docker)"
	@echo "  make package       Build fat JAR — skips tests (run verify first)"
	@echo "  make docker        Build local JVM Docker image tagged '$(IMAGE)'"
	@echo "  make clean         Remove target/"
	@echo "  make install-tools Install hadolint + lefthook via Homebrew"
	@echo ""

# ---------------------------------------------------------------------------
# lint — Dockerfile static analysis
#
# hadolint checks for common Dockerfile mistakes:
#   DL3002 last USER should not be root
#   DL3007 do not use 'latest' tag
#   DL3008 pin apt package versions
#   SC2xxx shellcheck rules inside RUN instructions
#
# If hadolint is not installed locally, the Docker image is used as a fallback
# so this target works on any machine with Docker (no brew install required).
# ---------------------------------------------------------------------------
lint:
ifdef HADOLINT
	hadolint Dockerfile
else
	@echo "hadolint not found — running via Docker (install with: make install-tools)"
	docker run --rm -i hadolint/hadolint < Dockerfile
endif

# ---------------------------------------------------------------------------
# test — unit tests only (no Docker, fast feedback)
#
# Mirrors GitLab CI unit-test job: maven-surefire-plugin with *ITest excluded.
# JaCoCo prepare-agent instruments bytecode; jacoco.exec is written to target/.
# ---------------------------------------------------------------------------
test:
	$(MAVEN) test

# ---------------------------------------------------------------------------
# check — alias for test (short name for git hook / pre-push use)
# ---------------------------------------------------------------------------
check: test

# ---------------------------------------------------------------------------
# integration — integration tests + SpotBugs + JaCoCo coverage gate
#
# Mirrors GitLab CI integration-test job:
#   - maven-failsafe-plugin runs *ITest classes
#   - Testcontainers starts PostgreSQL 17 + Kafka + Redis containers via Docker
#   - SpotBugs:check runs bytecode analysis (fails on Medium+ findings)
#   - JaCoCo:merge combines unit exec + IT exec
#   - JaCoCo:check enforces 60% instruction coverage gate
#
# -Dsurefire.skip=true: unit tests already ran in the 'test' target.
# ---------------------------------------------------------------------------
integration:
	$(MAVEN) verify -Dsurefire.skip=true

# ---------------------------------------------------------------------------
# verify — full pipeline equivalent (lint + unit + integration)
#
# This is the main local CI target. Run this before opening an MR to get
# the same signal as the remote pipeline, without consuming CI quota.
# ---------------------------------------------------------------------------
verify: lint test integration

# ---------------------------------------------------------------------------
# ci — alias for verify (entry point name familiar to CI users)
# ---------------------------------------------------------------------------
ci: verify

# ---------------------------------------------------------------------------
# package — build the executable fat JAR
#
# Tests are skipped here — run verify first to ensure quality gates pass.
# The JAR is written to target/*.jar (Spring Boot layered format).
# ---------------------------------------------------------------------------
package:
	$(MAVEN) -DskipTests package

# ---------------------------------------------------------------------------
# docker — build the local JVM Docker image
#
# Multi-stage build (Dockerfile):
#   Stage 1: eclipse-temurin:25-jdk — mvnw package
#   Stage 2: eclipse-temurin:25-jdk — layer extraction
#   Stage 3: eclipse-temurin:25-jre — minimal runtime
# Image is tagged '$(IMAGE)' for local use only (not pushed to any registry).
# ---------------------------------------------------------------------------
docker:
	docker build -t $(IMAGE) .
	@echo ""
	@echo "Image built: $(IMAGE)"
	@echo "Run with: docker compose up -d"

# ---------------------------------------------------------------------------
# clean — remove all build artifacts
# ---------------------------------------------------------------------------
clean:
	$(MAVEN) clean

# ---------------------------------------------------------------------------
# install-tools — install local tooling via Homebrew
#
# hadolint: Dockerfile linter (makes the 'lint' target run natively, no Docker)
# lefthook: git hooks runner — activates pre-push hook that runs 'make check'
# After install: run 'lefthook install' to wire the hook into .git/hooks/
# ---------------------------------------------------------------------------
install-tools:
	brew install hadolint lefthook
	lefthook install
	@echo ""
	@echo "Tools installed. Git pre-push hook is now active."
	@echo "Every 'git push' will automatically run 'make check' (unit tests)."
