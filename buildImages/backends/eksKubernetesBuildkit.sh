#!/usr/bin/env bash

# Build backend for cloud Kubernetes contexts (EKS / GKE / AKS / migration-eks-).
# Uses docker buildx with the kubernetes driver to spin up amd64 + arm64 buildkit
# Pods directly on the cluster's build-nodepool. Local kind go through
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
    echo "Current context: '${context}'. Otherwise, use buildImages/backends/dockerHostedBuildkit.sh." >&2
    return 1
  fi
}

# A buildkit pod that never becomes Ready almost always means no node was provisioned for it, and
# the reason lives on the Karpenter NodeClaim rather than on the pod. On an EKS Auto Mode cluster the
# usual causes are a NodePool pointing at a NodeClass that does not exist, or the launch itself being
# rejected -- e.g. an SCP or IAM policy that requires tags on ec2:RunInstances. Both show up here as
# a NodeClaim stuck without a providerID and an AccessDenied in its events, so dump enough to name
# the failing action instead of leaving "0 replicas ready" as the only evidence.
dump_node_provisioning_state() {
  echo "=== node provisioning state (why no node?) ===" >&2
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get nodes -o wide >&2 || true
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get nodepools,nodeclasses -o wide >&2 || true
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get nodeclaims -o wide >&2 || true
  # The launch error text is in the NodeClaim's events/conditions.
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} describe nodeclaims >&2 || true
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get events -A \
    --sort-by=.lastTimestamp >&2 2>/dev/null | tail -60 >&2 || true
  echo "=== end node provisioning state ===" >&2
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

run_with_optional_timeout() {
  local timeout_value="$1"
  shift

  if command -v gtimeout >/dev/null 2>&1; then
    gtimeout "${timeout_value}" "$@"
  elif command -v timeout >/dev/null 2>&1; then
    timeout "${timeout_value}" "$@"
  else
    echo "No timeout command found; running without an external ${timeout_value} timeout." >&2
    "$@"
  fi
}

duration_to_seconds() {
  local value="$1"
  if [[ "${value}" =~ ^([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "${value}" =~ ^([0-9]+)([smh])$ ]]; then
    local amount="${BASH_REMATCH[1]}"
    local unit="${BASH_REMATCH[2]}"
    case "${unit}" in
      s) echo "${amount}" ;;
      m) echo $((amount * 60)) ;;
      h) echo $((amount * 3600)) ;;
    esac
    return 0
  fi
  echo "ERROR: Unsupported duration '${value}'. Use seconds, or an s/m/h suffix such as 900s, 15m, or 1h." >&2
  return 1
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

  # The kubernetes driver creates its builder Deployments as part of buildx
  # bootstrap. Give that bootstrap enough time for the build-nodepool cold-start
  # path; a kubectl pre-wait cannot help before buildx has created anything.
  local build_timeout="${BUILDKIT_BOOTSTRAP_TIMEOUT:-900s}"
  local timeout_seconds
  timeout_seconds="$(duration_to_seconds "${build_timeout}")"
  local deadline=$(( $(date +%s) + timeout_seconds ))
  local max_attempts="${BUILDKIT_BOOTSTRAP_ATTEMPTS:-0}"
  if [[ ! "${max_attempts}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: BUILDKIT_BOOTSTRAP_ATTEMPTS must be a non-negative integer." >&2
    return 1
  fi
  local attempt=1
  while true; do
    local now remaining attempt_label
    now="$(date +%s)"
    remaining=$((deadline - now))
    if [[ "${remaining}" -le 0 ]]; then
      echo "ERROR: buildx bootstrap did not complete within ${build_timeout}; dumping buildkit namespace state" >&2
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        get all -n "${namespace}" >&2 || true
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        describe pods -n "${namespace}" >&2 || true
      return 1
    fi

    if [[ "${max_attempts}" -gt 0 ]]; then
      attempt_label="attempt ${attempt}/${max_attempts}"
    else
      attempt_label="attempt ${attempt}"
    fi
    echo "Bootstrapping buildx builder (${attempt_label}, ${remaining}s remaining of ${build_timeout})..."
    if run_with_optional_timeout "${remaining}s" docker buildx inspect --bootstrap; then
      break
    fi
    echo "buildx bootstrap failed on ${attempt_label}; current buildkit namespace state:" >&2
    kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
      get deployments,replicasets,pods -n "${namespace}" -o wide >&2 || true

    if [[ "${max_attempts}" -gt 0 && "${attempt}" -ge "${max_attempts}" ]]; then
      echo "ERROR: buildx bootstrap exhausted ${max_attempts} attempt(s) before ${build_timeout}; dumping buildkit namespace state" >&2
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        get all -n "${namespace}" >&2 || true
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        describe pods -n "${namespace}" >&2 || true
      dump_node_provisioning_state
      return 1
    fi
    attempt=$((attempt + 1))
    now="$(date +%s)"
    remaining=$((deadline - now))
    if [[ "${remaining}" -le 0 ]]; then
      continue
    fi
    if [[ "${remaining}" -lt 10 ]]; then
      sleep "${remaining}"
    else
      sleep 10
    fi
  done
}

setup_build_backend() {
  export BUILDKIT_NAMESPACE="${BUILDKIT_NAMESPACE:-buildkit}"
  ensure_eks_buildkit_release
  ensure_eks_buildx_builder
}
