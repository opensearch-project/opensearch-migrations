#!/usr/bin/env bash

# Build backend for cloud Kubernetes contexts (EKS / GKE / AKS / migration-eks-).
# Uses docker buildx with the kubernetes driver to spin up amd64 + arm64 buildkit
# Pods directly on the cluster's build-nodepool. Local kind/minikube go through
# buildImages/backends/dockerHostedBuildkit.sh instead.

set -euo pipefail

if [[ -z "${MIGRATIONS_REPO_ROOT_DIR:-}" ]]; then
  MIGRATIONS_REPO_ROOT_DIR="$(git rev-parse --show-toplevel)"
fi

EKS_CONTEXT_PATTERN='(eks:|gke_|aks-|migration-eks-)'

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

require_eks_context() {
  local context
  context="$(get_k8s_build_context)"
  if [[ ! "${context}" =~ ${EKS_CONTEXT_PATTERN} ]]; then
    echo "ERROR: eksKubernetesBuildkit.sh requires a cloud K8s context matching ${EKS_CONTEXT_PATTERN}." >&2
    echo "Current context: '${context}'. Use buildImages/backends/dockerHostedBuildkit.sh for kind/minikube." >&2
    return 1
  fi
}

ensure_eks_buildkit_release() {
  set_k8s_context_args
  require_eks_context

  echo "Installing buildImages helm chart for nodepool..."
  if helm ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} list -n buildkit 2>/dev/null | grep -q buildkit; then
    echo "buildkit helm release already exists, skipping install"
    return 0
  fi

  # shellcheck disable=SC2086
  helm install buildkit "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/charts/components/buildImages" \
    ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} \
    --create-namespace \
    -n buildkit \
    --set skipBuildJob=true \
    --set namespace=buildkit \
    --set awsEKSEnabled=true \
    --set multiArchNative=true \
    --set deployBuildkitPods=false \
    ${BUILDKIT_IMAGE:+--set buildkitImage="$BUILDKIT_IMAGE"} \
    ${BUILDKIT_HELM_ARGS:-}
}

ensure_eks_buildx_builder() {
  local context builder_name namespace
  set_k8s_context_args
  require_eks_context

  context="$(get_k8s_build_context)"
  builder_name="builder-${context//[^a-zA-Z0-9_-]/-}"
  namespace="${BUILDKIT_NAMESPACE:-buildkit}"

  docker buildx rm "${builder_name}" 2>/dev/null || true

  echo "Creating buildx builder with the kubernetes driver for context ${context}"
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
  export BUILDKIT_NAMESPACE="${BUILDKIT_NAMESPACE:-buildkit}"
  ensure_eks_buildkit_release
  ensure_eks_buildx_builder
}
