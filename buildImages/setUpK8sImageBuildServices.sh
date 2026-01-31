#!/usr/bin/env bash

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)

echo "Installing buildImages helm chart for nodepool..."
if ! helm list -n buildkit | grep -q buildkit; then
  # Detect if we're on EKS (cloud) vs local K8s
  CONTEXT=$(kubectl config current-context 2>/dev/null)
  if [[ "$CONTEXT" =~ (eks:|gke_|aks-) ]]; then
    AWS_EKS_ENABLED=true
    DEPLOY_BUILDKIT_PODS=false  # kubernetes driver creates its own pods
  else
    AWS_EKS_ENABLED=false
    DEPLOY_BUILDKIT_PODS=true   # need static buildkitd pod for remote driver
  fi

  helm install buildkit "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/charts/components/buildImages" \
    --create-namespace \
    -n buildkit \
    --set skipBuildJob=true \
    --set namespace=buildkit \
    --set awsEKSEnabled="${AWS_EKS_ENABLED}" \
    --set multiArchNative="${AWS_EKS_ENABLED}" \
    --set deployBuildkitPods="${DEPLOY_BUILDKIT_PODS}"
else
  echo "buildkit helm release already exists, skipping install"
fi

if [ "${USE_LOCAL_REGISTRY}" = "true" ]; then
  echo "Setting up a local registry"
  if ! kubectl get deployment docker-registry -n buildkit >/dev/null 2>&1; then
    kubectl apply -f "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/docker-registry.yaml" -n buildkit
  else
    echo "docker-registry already exists, skipping apply"
  fi

  echo "Waiting for docker-registry deployment to be available..."
  kubectl rollout status deployment/docker-registry -n buildkit --timeout=120s

  if ! pgrep -f "kubectl port-forward.*docker-registry.*5001:5000" >/dev/null; then
    nohup kubectl port-forward -n buildkit svc/docker-registry 5001:5000 --address 0.0.0.0 > /tmp/registry-forward.log 2>&1 &
    sleep 2
  else
    echo "registry port-forward already running"
  fi
else
  echo "Not creating a docker registry. Assuming that one is already running at port 5001."
fi

# Set up the builders
export BUILDKIT_NAMESPACE=buildkit
"${MIGRATIONS_REPO_ROOT_DIR}/buildImages/setupK8sBuilders.sh"
