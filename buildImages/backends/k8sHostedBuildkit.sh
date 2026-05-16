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

# Resolves the cluster's first node InternalIP. Used for NodePort access to
# both buildkitd (31234) and docker-registry (30500). Single source of truth
# so callers don't reinvent it.
get_node_internal_ip() {
  set_k8s_context_args
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get nodes \
    -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}'
}

# NodePort assignments for in-cluster build services. Keep in sync with
# buildkitd-pod.yaml (31234) and buildImages/docker-registry.yaml (30500).
readonly BUILDKIT_NODE_PORT=31234
readonly REGISTRY_NODE_PORT=30500
readonly REGISTRY_CLUSTER_DNS="docker-registry:5000"

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

  # Kill any leftover registry port-forwards from older script versions; we now
  # reach the registry via its NodePort instead, so the forward is a liability.
  pkill -f "kubectl port-forward.*docker-registry.*5001:5000" 2>/dev/null || true
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
    echo "Detected local K8s, using remote driver via NodePort"
    echo "Waiting for buildkitd pod..."
    kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} wait --for=condition=ready pod -l app=buildkitd -n "${namespace}" --timeout=120s

    local node_ip buildkit_endpoint
    node_ip="$(get_node_internal_ip)"
    buildkit_endpoint="${node_ip}:${BUILDKIT_NODE_PORT}"

    echo "Waiting for buildkit endpoint at ${buildkit_endpoint}..."
    for i in $(seq 1 60); do
      if (echo >/dev/tcp/"${node_ip}"/"${BUILDKIT_NODE_PORT}") 2>/dev/null; then
        echo "buildkit endpoint reachable at ${buildkit_endpoint}"
        break
      fi
      if [[ "${i}" -eq 60 ]]; then
        echo "ERROR: buildkit endpoint not reachable at ${buildkit_endpoint} after 60s" >&2
        kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get svc -n "${namespace}" >&2 || true
        kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get pods -n "${namespace}" >&2 || true
        exit 1
      fi
      sleep 1
    done

    docker buildx create \
      --name="${builder_name}" \
      --driver=remote \
      "tcp://${buildkit_endpoint}"
  fi

  docker buildx use "${builder_name}"
  echo "BUILDX_BUILDER=${builder_name}"
  echo "Bootstrapping builder..."

  # docker buildx inspect --bootstrap uses a hardcoded 110s timeout when it
  # waits for the builder Deployments to become Ready. On EKS, the very first
  # time the build-nodepool scales up, Karpenter cold-start routinely blows
  # past 110s (node provisioning + image pull + container start), so the
  # bootstrap fails with `expected 1 replicas to be ready, got 0` and the
  # whole pipeline dies before any test runs.
  #
  # Do a longer kubectl-level pre-wait on every buildkit Deployment in the
  # namespace first, then call buildx. The kubectl wait gives Karpenter the
  # time it realistically needs; once pods are Available, buildx's internal
  # 110s window is plenty. We still retry the bootstrap once in case a pod
  # flaps between kubectl-wait and buildx-inspect.
  local build_timeout="${BUILDKIT_BOOTSTRAP_TIMEOUT:-900s}"
  local attempts="${BUILDKIT_BOOTSTRAP_ATTEMPTS:-2}"
  local attempt=1
  while true; do
    echo "Pre-waiting up to ${build_timeout} for buildkit Deployments in '${namespace}' (attempt ${attempt}/${attempts})..."
    kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
      wait --for=condition=Available deployment --all -n "${namespace}" \
      --timeout="${build_timeout}" || {
        echo "Deployments not yet Available after kubectl wait; dumping state for diagnosis" >&2
        kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
          get deployments,replicasets,pods -n "${namespace}" -o wide >&2 || true
        kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
          describe pods -n "${namespace}" >&2 || true
      }
    if docker buildx inspect --bootstrap; then
      break
    fi
    echo "buildx bootstrap failed on attempt ${attempt}/${attempts}" >&2
    if [[ "${attempt}" -ge "${attempts}" ]]; then
      echo "ERROR: buildx bootstrap exhausted ${attempts} attempt(s); dumping buildkit namespace state" >&2
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        get all -n "${namespace}" >&2 || true
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        describe pods -n "${namespace}" >&2 || true
      return 1
    fi
    attempt=$((attempt + 1))
    sleep 10
  done
}

setup_build_backend() {
  export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-false}"
  export BUILDKIT_NAMESPACE="${BUILDKIT_NAMESPACE:-buildkit}"
  ensure_k8s_buildkit_release
  ensure_k8s_local_registry
  ensure_k8s_buildx_builder

  if [[ "${USE_LOCAL_REGISTRY}" != "true" ]]; then
    return 0
  fi

  # Publish the resolved registry endpoint so subsequent gradle invocations
  # (which run in a fresh shell under Jenkins) can pick it up. Writing both an
  # exported env var and a sourceable file works for either calling style.
  local node_ip registry_endpoint env_file
  node_ip="$(get_node_internal_ip)"
  registry_endpoint="${node_ip}:${REGISTRY_NODE_PORT}"
  env_file="${BUILD_BACKEND_ENV_FILE:-/tmp/k8s-build-backend.env}"

  export BUILD_REGISTRY_ENDPOINT="${registry_endpoint}"
  export BUILD_CONTAINER_REGISTRY_ENDPOINT="${REGISTRY_CLUSTER_DNS}"

  cat > "${env_file}" <<EOF
# Generated by buildImages/backends/k8sHostedBuildkit.sh — source to inherit
# the build endpoints resolved during setup.
export BUILD_REGISTRY_ENDPOINT="${BUILD_REGISTRY_ENDPOINT}"
export BUILD_CONTAINER_REGISTRY_ENDPOINT="${BUILD_CONTAINER_REGISTRY_ENDPOINT}"
EOF

  echo "Build registry endpoint: ${BUILD_REGISTRY_ENDPOINT}"
  echo "Build container registry endpoint (in-cluster): ${BUILD_CONTAINER_REGISTRY_ENDPOINT}"
  echo "Wrote build backend env to ${env_file}"
}
