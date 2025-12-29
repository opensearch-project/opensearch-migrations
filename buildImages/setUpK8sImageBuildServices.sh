#!/usr/bin/env bash

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)

echo "Configuring a buildkit service in minikube for buildImagesToRegistry to build with"
if ! helm list -n buildkit | grep -q buildkit; then
  helm install buildkit "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/charts/components/buildImages" \
    --create-namespace \
    -n buildkit \
    --set skipBuildJob=true \
    --set namespace=buildkit
else
  echo "buildkit helm release already exists, skipping install"
fi

if [ "${USE_LOCAL_REGISTRY}" = "true" ]; then
  echo "Setting up a local registry inside minikube"
  if ! kubectl get deployment docker-registry -n buildkit >/dev/null 2>&1; then
    kubectl apply -f "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/docker-registry.yaml" -n buildkit
  else
    echo "docker-registry already exists, skipping apply"
  fi
else
  echo "Not creating a docker registry.  Assuming that one is already running at port 5001."
fi

echo "Wait for buildkit and registry pods to be ready"
kubectl wait --for=condition=ready pod -l app=buildkitd -n buildkit --timeout=120s
kubectl wait --for=condition=ready pod -l app=docker-registry -n buildkit --timeout=60s

echo "Setting up port forwards"
if ! pgrep -f "kubectl port-forward.*buildkitd.*1234:1234" >/dev/null; then
  nohup kubectl port-forward -n buildkit svc/buildkitd 1234:1234 --address 0.0.0.0 > /tmp/buildkit-forward.log 2>&1 &
else
  echo "buildkit port-forward already running"
fi

if ! pgrep -f "kubectl port-forward.*docker-registry.*5001:5000" >/dev/null; then
  nohup kubectl port-forward -n buildkit svc/docker-registry 5001:5000 --address 0.0.0.0 > /tmp/registry-forward.log 2>&1 &
else
  echo "registry port-forward already running"
fi

echo "Remove any existing builder with this name"
docker buildx rm migrations-local-remote-builder 2>/dev/null || true

echo "Create the builder pointing to localhost:1234 (port-forwarded to the BuildKit service)"
docker buildx create --name migrations-local-remote-builder --driver remote tcp://localhost:1234

echo "Set it as the active builder"
docker buildx use migrations-local-remote-builder

echo "Bootstrap and verify connection"
docker buildx inspect --bootstrap

