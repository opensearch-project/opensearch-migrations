#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${MIGRATIONS_REPO_ROOT_DIR:-}" ]]; then
  MIGRATIONS_REPO_ROOT_DIR="$(git rev-parse --show-toplevel)"
fi

get_k8s_build_context() {
  if [[ -n "${KUBE_CONTEXT:-}" ]]; then
    echo "${KUBE_CONTEXT}"
  else
    kubectl config current-context 2>/dev/null
  fi
}

set_k8s_context_args() {
  CONTEXT_ARGS=()
  HELM_CONTEXT_ARGS=()
  if [[ -n "${KUBE_CONTEXT:-}" ]]; then
    CONTEXT_ARGS=("--context=${KUBE_CONTEXT}")
    HELM_CONTEXT_ARGS=("--kube-context=${KUBE_CONTEXT}")
  fi
}

ensure_k8s_buildkit_release() {
  local context
  set_k8s_context_args

  echo "Installing buildImages helm chart for nodepool..."
  if helm ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} list -n buildkit 2>/dev/null | grep -q buildkit; then
    echo "buildkit helm release already exists, skipping install"
    return 0
  fi

  context="$(get_k8s_build_context)"
  if [[ "${context}" =~ (eks:|gke_|aks-|migration-eks-) ]]; then
    AWS_EKS_ENABLED=true
    DEPLOY_BUILDKIT_PODS=false
  else
    AWS_EKS_ENABLED=false
    DEPLOY_BUILDKIT_PODS=true
  fi

  # shellcheck disable=SC2086
  helm install buildkit "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/charts/components/buildImages" \
    ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} \
    --create-namespace \
    -n buildkit \
    --set skipBuildJob=true \
    --set namespace=buildkit \
    --set awsEKSEnabled="${AWS_EKS_ENABLED}" \
    --set multiArchNative="${AWS_EKS_ENABLED}" \
    --set deployBuildkitPods="${DEPLOY_BUILDKIT_PODS}" \
    ${BUILDKIT_IMAGE:+--set buildkitImage="$BUILDKIT_IMAGE"} \
    ${BUILDKIT_HELM_ARGS:-}
}

ensure_k8s_local_registry() {
  set_k8s_context_args

  if [[ "${USE_LOCAL_REGISTRY:-false}" != "true" ]]; then
    echo "Not creating a docker registry. Assuming that one is already running."
    return 0
  fi

  echo "Setting up a local registry"
  if ! kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get deployment docker-registry -n buildkit >/dev/null 2>&1; then
    kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} apply -f "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/docker-registry.yaml" -n buildkit
  else
    echo "docker-registry already exists, skipping apply"
  fi

  echo "Waiting for docker-registry deployment to be available..."
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} rollout status deployment/docker-registry -n buildkit --timeout=120s

  if ! pgrep -f "kubectl port-forward.*docker-registry.*5001:5000" >/dev/null; then
    nohup kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} port-forward -n buildkit svc/docker-registry 5001:5000 > /tmp/registry-forward.log 2>&1 &
  else
    echo "registry port-forward already running"
  fi
}

ensure_k8s_buildx_builder() {
  local context builder_name namespace
  set_k8s_context_args

  context="$(get_k8s_build_context)"
  builder_name="builder-${context//[^a-zA-Z0-9_-]/-}"
  namespace="${BUILDKIT_NAMESPACE:-buildkit}"

  docker buildx rm "${builder_name}" 2>/dev/null || true

  echo "Creating buildx builder..."
  if [[ "${context}" =~ (eks:|gke_|aks-|migration-eks-) ]]; then
    echo "Detected cloud K8s, using kubernetes driver with native multi-arch builds"
    BUILDKIT_RESOURCE_OPTS=(
      --driver-opt="requests.cpu=${BUILDKIT_REQUESTS_CPU:-4}"
      --driver-opt="requests.memory=${BUILDKIT_REQUESTS_MEMORY:-8Gi}"
      --driver-opt="limits.cpu=${BUILDKIT_LIMITS_CPU:-8}"
      --driver-opt="limits.memory=${BUILDKIT_LIMITS_MEMORY:-16Gi}"
    )

    docker buildx create \
      --name="${builder_name}" \
      --driver=kubernetes \
      --platform=linux/amd64 \
      --node=builder-amd64 \
      --driver-opt="namespace=${namespace}" \
      --driver-opt="nodeselector=kubernetes.io/arch=amd64" \
      --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
      "${BUILDKIT_RESOURCE_OPTS[@]}" \
      ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}

    docker buildx create \
      --append \
      --name="${builder_name}" \
      --driver=kubernetes \
      --platform=linux/arm64 \
      --node=builder-arm64 \
      --driver-opt="namespace=${namespace}" \
      --driver-opt="nodeselector=kubernetes.io/arch=arm64" \
      --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
      "${BUILDKIT_RESOURCE_OPTS[@]}" \
      ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}
  else
    echo "Detected local K8s, using remote driver with port-forwards"
    echo "Waiting for buildkitd pod..."
    kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} wait --for=condition=ready pod -l app=buildkitd -n "${namespace}" --timeout=120s

    if ! pgrep -f "kubectl port-forward.*buildkitd.*1234:1234" >/dev/null; then
      echo "Starting buildkit port-forward..."
      nohup kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} port-forward -n "${namespace}" svc/buildkitd 1234:1234 > /tmp/buildkit-forward.log 2>&1 &
    else
      echo "buildkit port-forward already running"
    fi

    echo "Waiting for buildkit port-forward to be ready..."
    for i in $(seq 1 30); do
      if (echo >/dev/tcp/localhost/1234) 2>/dev/null; then
        echo "buildkit endpoint reachable"
        break
      fi
      if [[ "${i}" -eq 30 ]]; then
        echo "ERROR: buildkit endpoint not reachable at localhost:1234 after 30s" >&2
        cat /tmp/buildkit-forward.log 2>/dev/null || true
        exit 1
      fi
      sleep 1
    done

    docker buildx create \
      --name="${builder_name}" \
      --driver=remote \
      tcp://localhost:1234
  fi

  docker buildx use "${builder_name}"
  echo "BUILDX_BUILDER=${builder_name}"
  echo "Bootstrapping builder..."
  docker buildx inspect --bootstrap
}

setup_build_backend() {
  export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-false}"
  if [[ "${USE_LOCAL_REGISTRY}" == "true" ]]; then
    export BUILD_CONTAINER_REGISTRY_ENDPOINT="${BUILD_CONTAINER_REGISTRY_ENDPOINT:-docker-registry:5000}"
  fi
  export BUILDKIT_NAMESPACE="${BUILDKIT_NAMESPACE:-buildkit}"
  ensure_k8s_buildkit_release
  ensure_k8s_local_registry
  ensure_k8s_buildx_builder
}
