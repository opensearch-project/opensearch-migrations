#!/usr/bin/env bash
# =============================================================================
# 02-setup-k8s-secrets.sh
#
# Creates Kubernetes secrets for source ES and target OpenSearch credentials
# in the 'ma' namespace, with the labels required by the migration console
# workflow CLI to recognize them.
#
# Safe to re-run — uses --dry-run=client | kubectl apply for idempotency.
# =============================================================================
set -euo pipefail

NAMESPACE="ma"

# ── Source Elasticsearch credentials ─────────────────────────────────────────
echo "Creating source-es-creds secret..."
kubectl create secret generic source-es-creds \
  --namespace "$NAMESPACE" \
  --from-literal=username=elastic \
  --from-literal=password=ElasticRocks \
  --dry-run=client -o yaml | kubectl apply -f -

# ── Target OpenSearch credentials ─────────────────────────────────────────────
echo "Creating target-os-creds secret..."
kubectl create secret generic target-os-creds \
  --namespace "$NAMESPACE" \
  --from-literal=username=admin \
  --from-literal=password='MyStr0ng!P@ssw0rd2024' \
  --dry-run=client -o yaml | kubectl apply -f -

# ── Apply required labels ─────────────────────────────────────────────────────
# The workflow CLI's SecretStore looks for secrets with use-case=http-basic-credentials.
# Without this label, `workflow configure edit` reports the secrets as "missing"
# and exits with code 1, blocking the configureAndSubmitWorkflow step.
echo "Labeling secrets for workflow CLI discovery..."
kubectl label secret source-es-creds -n "$NAMESPACE" \
  use-case=http-basic-credentials --overwrite
kubectl label secret target-os-creds -n "$NAMESPACE" \
  use-case=http-basic-credentials --overwrite

echo ""
echo "Verifying secrets..."
kubectl get secret source-es-creds target-os-creds -n "$NAMESPACE" --show-labels
echo ""
echo "Verifying workflow CLI sees no missing credentials..."
kubectl exec -n "$NAMESPACE" migration-console-0 -- \
  /bin/bash -lc 'workflow configure credentials create --show-missing' 2>&1 || true
echo ""
echo "Done. Secrets are ready."
