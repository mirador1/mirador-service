#!/usr/bin/env bash
# bin/run/gke-infra-setup.sh — implements `./run.sh gke-infra-setup`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    # One-time setup of GKE Autopilot cluster infrastructure prerequisites.
    # Installs ingress-nginx + cert-manager, then applies the GKE Autopilot
    # compatibility patches (leader-election namespace + RBAC for kube-system lock bypass).
    #
    # GKE Autopilot denies both cert-manager controller and cainjector from creating
    # leader-election Lease locks in kube-system ("managed namespace" policy).
    # Fix: redirect leader election to the cert-manager namespace itself.
    #
    # Run once after cluster creation (before first deploy:gke pipeline run).
    command -v kubectl >/dev/null 2>&1 || { echo "❌  kubectl not found — brew install kubectl"; exit 1; }
    echo "📦  Installing ingress-nginx..."
    kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
    echo "📦  Installing cert-manager..."
    kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
    echo "⏳  Waiting for cert-manager pods to be ready..."
    kubectl wait --for=condition=available deployment/cert-manager \
      deployment/cert-manager-webhook deployment/cert-manager-cainjector \
      -n cert-manager --timeout=120s
    echo "🔧  Applying GKE Autopilot RBAC patches (leader-election in cert-manager namespace)..."
    kubectl apply -f deploy/kubernetes/gke/cert-manager-gke-fix.yaml
    kubectl patch deployment cert-manager -n cert-manager --type='json' \
      -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--leader-election-namespace=cert-manager"}]'
    kubectl patch deployment cert-manager-cainjector -n cert-manager --type='json' \
      -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--leader-election-namespace=cert-manager"}]'
    kubectl rollout status deployment/cert-manager deployment/cert-manager-cainjector \
      -n cert-manager --timeout=60s
    echo "🔑  Creating letsencrypt-prod ClusterIssuer..."
    kubectl apply -f - <<'ISSUER'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: benoitbesson@gmail.com
    privateKeySecretRef:
      name: letsencrypt-prod-account-key
    solvers:
      - http01:
          ingress:
            ingressClassName: nginx
ISSUER
    echo "⏳  Waiting for ClusterIssuer to be ready..."
    for i in $(seq 1 12); do
      READY=$(kubectl get clusterissuer letsencrypt-prod -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)
      if [ "$READY" = "True" ]; then
        echo "✅  ClusterIssuer letsencrypt-prod is Ready"
        break
      fi
      echo "    still waiting... ($i/12)"
      sleep 5
    done
    echo "✅  GKE infra setup complete."
    echo "    Run 'deploy:gke' pipeline or push to main to deploy the application."
