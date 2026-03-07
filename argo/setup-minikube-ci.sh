#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(git rev-parse --show-toplevel)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== P0: Setting up Argo CI/CD on Minikube ==="

# --- Step 1: Ensure minikube is running with adequate resources ---
MINIKUBE_CPU_COUNT="${MINIKUBE_CPU_COUNT:-8}"
MINIKUBE_MEMORY_SIZE="${MINIKUBE_MEMORY_SIZE:-18000}"

if ! minikube status --format='{{.Host}}' 2>/dev/null | grep -q Running; then
  echo "Starting minikube (${MINIKUBE_CPU_COUNT} CPUs, ${MINIKUBE_MEMORY_SIZE}MB RAM)..."
  minikube config set cpus "$MINIKUBE_CPU_COUNT"
  minikube config set memory "$MINIKUBE_MEMORY_SIZE"
  INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"
  minikube start \
    --extra-config=kubelet.authentication-token-webhook=true \
    --extra-config=kubelet.authorization-mode=Webhook \
    --extra-config=scheduler.bind-address=0.0.0.0 \
    --extra-config=controller-manager.bind-address=0.0.0.0 \
    --insecure-registry="${INSECURE_REGISTRY_CIDR}"
else
  echo "Minikube already running"
fi
kubectl config use-context minikube

# --- Step 2: Build images via existing BuildKit infrastructure ---
echo "Setting up BuildKit and building images..."
export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
"${REPO_ROOT}/buildImages/setUpK8sImageBuildServices.sh"

LOCAL_REGISTRY_PORT="${LOCAL_REGISTRY_PORT:-30500}"
MINIKUBE_IP="$(minikube ip)"
LOCAL_REGISTRY="${MINIKUBE_IP}:${LOCAL_REGISTRY_PORT}"
export LOCAL_REGISTRY

ARCH=$(uname -m)
case "$ARCH" in
  x86_64)  PLATFORM="amd64" ;;
  arm64|aarch64) PLATFORM="arm64" ;;
  *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac
"${REPO_ROOT}/gradlew" :buildImages:buildImagesToRegistry_${PLATFORM} -x test --info

# --- Step 3: Install test clusters + MA with Argo Workflows ---
echo "Installing test clusters + migration assistant with Argo..."
cd "${REPO_ROOT}/deployment/k8s/"
helm dependency update charts/aggregates/testClusters
helm dependency update charts/aggregates/migrationAssistantWithArgo

kubectl config set-context --current --namespace=ma

helm upgrade --install --create-namespace -n ma tc charts/aggregates/testClusters \
  --wait --timeout 10m \
  --set "source.image=${LOCAL_REGISTRY}/migrations/elasticsearch_searchguard"

helm upgrade --install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
  --wait --timeout 10m \
  -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml \
  --set "images.captureProxy.repository=${LOCAL_REGISTRY}/migrations/capture_proxy" \
  --set "images.captureProxy.tag=latest" \
  --set "images.captureProxy.pullPolicy=Always" \
  --set "images.installer.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
  --set "images.installer.tag=latest" \
  --set "images.installer.pullPolicy=Always" \
  --set "images.migrationConsole.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
  --set "images.migrationConsole.tag=latest" \
  --set "images.migrationConsole.pullPolicy=Always" \
  --set "images.trafficReplayer.repository=${LOCAL_REGISTRY}/migrations/traffic_replayer" \
  --set "images.trafficReplayer.tag=latest" \
  --set "images.trafficReplayer.pullPolicy=Always" \
  --set "images.reindexFromSnapshot.repository=${LOCAL_REGISTRY}/migrations/reindex_from_snapshot" \
  --set "images.reindexFromSnapshot.tag=latest" \
  --set "images.reindexFromSnapshot.pullPolicy=Always"

cd "${REPO_ROOT}"

# --- Step 4: Install Argo Events ---
echo "Installing Argo Events..."
kubectl create namespace argo-events 2>/dev/null || true
helm repo add argo https://argoproj.github.io/argo-helm 2>/dev/null || true
helm repo update argo

helm upgrade --install argo-events argo/argo-events \
  -n argo-events \
  --wait --timeout 5m \
  --set crds.install=true

# EventBus (NATS message backbone)
kubectl apply -n argo-events -f "${SCRIPT_DIR}/events/eventbus.yaml"

# --- Step 5: Install MinIO for artifact storage (minikube only) ---
echo "Installing MinIO for Argo artifact storage..."
kubectl create namespace minio 2>/dev/null || true
helm repo add minio https://charts.min.io/ 2>/dev/null || true
helm repo update minio

helm upgrade --install minio minio/minio \
  -n minio \
  --wait --timeout 5m \
  --set rootUser=minioadmin \
  --set rootPassword=minioadmin \
  --set mode=standalone \
  --set replicas=1 \
  --set persistence.size=5Gi \
  --set resources.requests.cpu=100m \
  --set resources.requests.memory=256Mi \
  --set buckets[0].name=argo-artifacts \
  --set buckets[0].policy=none

# Create artifact repository secret for Argo
kubectl -n ma create secret generic argo-artifact-repo-creds \
  --from-literal=accesskey=minioadmin \
  --from-literal=secretkey=minioadmin \
  --dry-run=client -o yaml | kubectl apply -f -

# Patch workflow-controller-configmap to use MinIO artifact repo
MINIO_SVC="minio.minio.svc.cluster.local:9000"
kubectl -n ma get configmap workflow-controller-configmap -o yaml 2>/dev/null || \
  kubectl -n ma create configmap workflow-controller-configmap
kubectl -n ma patch configmap workflow-controller-configmap --type merge -p "
data:
  artifactRepository: |
    archiveLogs: true
    s3:
      bucket: argo-artifacts
      endpoint: ${MINIO_SVC}
      insecure: true
      accessKeySecret:
        name: argo-artifact-repo-creds
        key: accesskey
      secretKeySecret:
        name: argo-artifact-repo-creds
        key: secretkey
      keyFormat: \"{{workflow.name}}/{{pod.name}}\"
"

# --- Step 6: CI-specific RBAC ---
echo "Applying CI RBAC..."
kubectl apply -n ma -f - <<'EOF'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: ci-workflow-role
  namespace: ma
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/log", "pods/exec", "services", "configmaps", "secrets", "persistentvolumeclaims"]
    verbs: ["*"]
  - apiGroups: ["apps"]
    resources: ["deployments", "statefulsets"]
    verbs: ["*"]
  - apiGroups: ["argoproj.io"]
    resources: ["workflows", "workflowtemplates", "workflowtaskresults"]
    verbs: ["*"]
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["*"]
  - apiGroups: ["helm.toolkit.fluxcd.io"]
    resources: ["helmreleases"]
    verbs: ["*"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: ci-workflow-rolebinding
  namespace: ma
subjects:
  - kind: ServiceAccount
    name: argo-workflow-executor
    namespace: ma
roleRef:
  kind: Role
  name: ci-workflow-role
  apiGroup: rbac.authorization.k8s.io
---
# Cross-namespace role for argo-events to submit workflows in ma namespace
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: argo-events-submit-workflows
  namespace: ma
subjects:
  - kind: ServiceAccount
    name: argo-events-sa
    namespace: argo-events
roleRef:
  kind: Role
  name: ci-workflow-role
  apiGroup: rbac.authorization.k8s.io
EOF

# --- Step 7: Apply CI workflow templates ---
echo "Applying CI workflow templates..."
# Only apply minikube-compatible templates (skip aws-integ/ which needs ci-runner image)
for f in "${SCRIPT_DIR}/workflows/"*.yaml; do
  echo "  Applying $(basename "$f")..."
  kubectl apply -n ma -f "$f"
done
echo "  (Skipping aws-integ/ templates — they require ci-runner image built for production EKS)"

# --- Step 8: Apply stage locks ConfigMap ---
echo "Applying stage locks..."
kubectl apply -n ma -f "${SCRIPT_DIR}/ci-stage-locks-configmap.yaml"

echo ""
echo "=== P0 Complete ==="
echo "Argo Workflows UI: kubectl -n ma port-forward svc/argo-workflows-server 2746:2746"
echo "Then open: https://localhost:2746"
echo ""
echo "MinIO Console: kubectl -n minio port-forward svc/minio-console 9001:9001"
echo "Then open: http://localhost:9001 (minioadmin/minioadmin)"
echo ""
echo "Registry IP: ${LOCAL_REGISTRY}"
