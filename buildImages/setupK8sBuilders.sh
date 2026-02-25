#!/usr/bin/env bash
set -euo pipefail

BUILDER_NAME="local-remote-builder"

# Check if builder already exists and is healthy
if docker buildx inspect "$BUILDER_NAME" --bootstrap &>/dev/null; then
  echo "Builder '$BUILDER_NAME' already configured and healthy"
  docker buildx use "$BUILDER_NAME"
  exit 0
fi

echo "Creating buildx builder..."
docker buildx rm "$BUILDER_NAME" 2>/dev/null || true

NAMESPACE="${BUILDKIT_NAMESPACE:-buildkit}"

# Detect cloud vs local K8s
CONTEXT=$(kubectl config current-context 2>/dev/null)
if [[ "$CONTEXT" =~ (eks:|gke_|aks-) ]]; then
  echo "Detected cloud K8s, using kubernetes driver with native multi-arch builds"
  
  docker buildx create \
    --name="$BUILDER_NAME" \
    --driver=kubernetes \
    --platform=linux/amd64 \
    --node=builder-amd64 \
    --driver-opt="namespace=${NAMESPACE}" \
    --driver-opt="nodeselector=kubernetes.io/arch=amd64" \
    --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
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
    ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}
else
  echo "Detected local K8s, using remote driver with port-forwards"
  
  # Wait for buildkitd pod
  echo "Waiting for buildkitd pod..."
  kubectl wait --for=condition=ready pod -l app=buildkitd -n "$NAMESPACE" --timeout=120s
  
  # Set up port-forward if not running
  if ! pgrep -f "kubectl port-forward.*buildkitd.*1234:1234" >/dev/null; then
    echo "Starting buildkit port-forward..."
    nohup kubectl port-forward -n "$NAMESPACE" svc/buildkitd 1234:1234 --address 0.0.0.0 > /tmp/buildkit-forward.log 2>&1 &
    sleep 2
  else
    echo "buildkit port-forward already running"
  fi
  
  docker buildx create \
    --name="$BUILDER_NAME" \
    --driver=remote \
    tcp://localhost:1234
fi

docker buildx use "$BUILDER_NAME"
echo "Bootstrapping builder..."
docker buildx inspect --bootstrap
