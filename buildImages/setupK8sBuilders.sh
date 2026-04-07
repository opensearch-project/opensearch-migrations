#!/usr/bin/env bash
set -euo pipefail

# Use KUBE_CONTEXT env var if set, for explicit context targeting
CONTEXT_ARGS=()
if [[ -n "${KUBE_CONTEXT:-}" ]]; then
  CONTEXT="${KUBE_CONTEXT}"
  CONTEXT_ARGS=("--context=${KUBE_CONTEXT}")
else
  CONTEXT=$(kubectl config current-context 2>/dev/null)
fi

# Derive builder name from context so each cluster gets its own builder
# NOTE: This naming convention must match RegistryImageBuildUtils.groovy's builder name derivation
BUILDER_NAME="builder-${CONTEXT//[^a-zA-Z0-9_-]/-}"

# Always remove stale builder — the helm chart is reinstalled before this script,
# so any pre-existing builder config points at a dead endpoint.
docker buildx rm "$BUILDER_NAME" 2>/dev/null || true

echo "Creating buildx builder..."
NAMESPACE="${BUILDKIT_NAMESPACE:-buildkit}"

# Detect cloud vs local K8s
if [[ "$CONTEXT" =~ (eks:|gke_|aks-|migration-eks-) ]]; then
  echo "Detected cloud K8s, using kubernetes driver with native multi-arch builds"
  
  # Resource requests/limits for buildkit pods — ensures pods get scheduled on
  # appropriately sized nodes and aren't evicted during large builds.
  # Note: Karpenter disruption protection is handled by the NodePool's WhenEmpty
  # consolidation policy — the kubernetes driver doesn't support pod annotations.
  BUILDKIT_RESOURCE_OPTS=(
    --driver-opt="requests.cpu=${BUILDKIT_REQUESTS_CPU:-4}"
    --driver-opt="requests.memory=${BUILDKIT_REQUESTS_MEMORY:-8Gi}"
    --driver-opt="limits.cpu=${BUILDKIT_LIMITS_CPU:-8}"
    --driver-opt="limits.memory=${BUILDKIT_LIMITS_MEMORY:-16Gi}"
  )

  docker buildx create \
    --name="$BUILDER_NAME" \
    --driver=kubernetes \
    --platform=linux/amd64 \
    --node=builder-amd64 \
    --driver-opt="namespace=${NAMESPACE}" \
    --driver-opt="nodeselector=kubernetes.io/arch=amd64" \
    --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
    "${BUILDKIT_RESOURCE_OPTS[@]}" \
    ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}

  docker buildx create \
    --append \
    --name="$BUILDER_NAME" \
    --driver=kubernetes \
    --platform=linux/arm64 \
    --node=builder-arm64 \
    --driver-opt="namespace=${NAMESPACE}" \
    --driver-opt="nodeselector=kubernetes.io/arch=arm64" \
    --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
    "${BUILDKIT_RESOURCE_OPTS[@]}" \
    ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}
else
  echo "Detected local K8s, using remote driver with port-forwards"
  
  # Wait for buildkitd pod
  echo "Waiting for buildkitd pod..."
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} wait --for=condition=ready pod -l app=buildkitd -n "$NAMESPACE" --timeout=120s
  
  # Set up port-forward if not running
  if ! pgrep -f "kubectl port-forward.*buildkitd.*1234:1234" >/dev/null; then
    echo "Starting buildkit port-forward..."
    nohup kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} port-forward -n "$NAMESPACE" svc/buildkitd 1234:1234 > /tmp/buildkit-forward.log 2>&1 &
  else
    echo "buildkit port-forward already running"
  fi
  
  # Wait for port-forward to be ready by probing the TCP port
  echo "Waiting for buildkit port-forward to be ready..."
  for i in $(seq 1 30); do
    if (echo >/dev/tcp/localhost/1234) 2>/dev/null; then
      echo "buildkit endpoint reachable"
      break
    fi
    if [ "$i" -eq 30 ]; then
      echo "ERROR: buildkit endpoint not reachable at localhost:1234 after 30s" >&2
      cat /tmp/buildkit-forward.log 2>/dev/null || true
      exit 1
    fi
    sleep 1
  done
  
  docker buildx create \
    --name="$BUILDER_NAME" \
    --driver=remote \
    tcp://localhost:1234
fi

docker buildx use "$BUILDER_NAME"
echo "BUILDX_BUILDER=${BUILDER_NAME}"
echo "Bootstrapping builder..."
docker buildx inspect --bootstrap
