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

  local -a reuse_values_args=()
  if helm ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} status buildkit -n buildkit >/dev/null 2>&1; then
    echo "Reconciling existing buildImages helm release while preserving its values..."
    reuse_values_args=(--reuse-values)
  else
    echo "Installing buildImages helm chart for nodepool..."
  fi

  # shellcheck disable=SC2086
  helm upgrade --install buildkit "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/charts/components/buildImages" \
    ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} \
    ${reuse_values_args[@]+"${reuse_values_args[@]}"} \
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

# Older buildx versions return success from inspect even when a node failed to
# bootstrap. Require both native builders to be running before starting a bake.
buildx_nodes_are_ready() {
  awk '
    /^Name:/ { name = $2 }
    /^Status:/ { status[name] = $2 }
    END {
      exit !(status["builder-amd64"] == "running" && status["builder-arm64"] == "running")
    }
  '
}

# The kubernetes driver has no context option and rejects endpoint arguments. It records $KUBECONFIG
# in each node's endpoint at `docker buildx create` time and later loads that file with no context
# override, so the builder follows the file's current-context. Each builder therefore gets its own
# single-context kubeconfig for KUBE_CONTEXT, which keeps its BuildKit pods on the requested cluster
# whatever the ambient current-context is now or later. The file can hold credentials, so it is 0600.
eks_buildx_kubeconfig_path() {
  local builder_name="$1"
  printf '%s\n' "${BUILDKIT_KUBECONFIG_DIR:-${HOME}/.kube/buildx}/${builder_name}.kubeconfig"
}

# Prints the minified kubeconfig for KUBE_CONTEXT, resolved from the caller's KUBECONFIG (which may
# list several files) or ~/.kube/config. --flatten inlines certificate files so the result stands alone.
render_eks_buildx_kubeconfig() {
  kubectl config view --minify --flatten --context="${KUBE_CONTEXT}"
}

write_eks_buildx_kubeconfig() {
  local builder_name="$1" kubeconfig_path tmp_path
  kubeconfig_path="$(eks_buildx_kubeconfig_path "${builder_name}")"
  (umask 077 && mkdir -p "$(dirname "${kubeconfig_path}")") || return 1
  tmp_path="$(umask 077 && mktemp "${kubeconfig_path}.XXXXXX")" || return 1
  if ! render_eks_buildx_kubeconfig > "${tmp_path}"; then
    rm -f "${tmp_path}"
    echo "ERROR: could not resolve kube context '${KUBE_CONTEXT}' from ${KUBECONFIG:-~/.kube/config}." >&2
    return 1
  fi
  mv -f "${tmp_path}" "${kubeconfig_path}"
  printf '%s\n' "${kubeconfig_path}"
}

# Encodes $1 the way Go's url.QueryEscape does, which is how buildx stores KUBECONFIG in an endpoint.
query_escape() {
  local LC_ALL=C
  local value="$1" encoded="" char code hex i
  for (( i = 0; i < ${#value}; i++ )); do
    char="${value:i:1}"
    case "${char}" in
      [a-zA-Z0-9._~-]) encoded+="${char}" ;;
      ' ') encoded+='+' ;;
      *)
        # Bytes >= 0x80 can come back sign-extended, so keep only the low byte.
        printf -v code '%d' "'${char}"
        printf -v hex '%%%02X' "$((code & 255))"
        encoded+="${hex}"
        ;;
    esac
  done
  printf '%s\n' "${encoded}"
}

# Reads `docker buildx inspect` output on stdin. Succeeds only when every node is a kubernetes-driver
# node created with KUBECONFIG=$1 and $1 exists, i.e. when the builder can only reach that file's
# context. A node created without it (e.g. by an older version of this script), or whose file is
# gone, falls back to the ambient current-context.
buildx_nodes_use_kubeconfig() {
  local expected_kubeconfig="$1" expected_query line endpoint query found=false
  local endpoint_re='^Endpoint:[[:space:]]+(.*)$'
  local kubeconfig_re='(^|&)kubeconfig=([^&]*)'
  [[ -f "${expected_kubeconfig}" ]] || return 1
  expected_query="$(query_escape "${expected_kubeconfig}")"
  while IFS= read -r line; do
    [[ "${line}" =~ ${endpoint_re} ]] || continue
    endpoint="${BASH_REMATCH[1]}"
    [[ "${endpoint}" == kubernetes://*\?* ]] || return 1
    query="${endpoint#*\?}"
    [[ "${query}" =~ ${kubeconfig_re} && "${BASH_REMATCH[2]}" == "${expected_query}" ]] || return 1
    found=true
  done
  [[ "${found}" == true ]]
}

# Succeeds when builder $1 can be reused for KUBE_CONTEXT: its nodes use this builder's kubeconfig,
# that file still matches what KUBE_CONTEXT resolves to, and both native nodes are running.
eks_buildx_builder_is_ready() {
  local builder_name="$1" builder_kubeconfig builder_status
  if [[ -n "${KUBE_CONTEXT:-}" ]]; then
    builder_kubeconfig="$(eks_buildx_kubeconfig_path "${builder_name}")"
    [[ -f "${builder_kubeconfig}" ]] || return 1
    # Compare the files so that a context re-pointed at another cluster gets a new builder.
    render_eks_buildx_kubeconfig 2>/dev/null | cmp -s - "${builder_kubeconfig}" || return 1
  fi
  builder_status="$(docker buildx inspect "${builder_name}" 2>/dev/null)" || return 1
  if [[ -n "${KUBE_CONTEXT:-}" ]] &&
      ! buildx_nodes_use_kubeconfig "${builder_kubeconfig}" <<< "${builder_status}"; then
    echo "Existing builder ${builder_name} is not pinned to kube context ${KUBE_CONTEXT}; it will be recreated." >&2
    return 1
  fi
  buildx_nodes_are_ready <<< "${builder_status}"
}

# Removes builder $1 and its BuildKit Deployments without acting on any cluster other than KUBE_CONTEXT.
# `docker buildx rm` deletes the Deployments through the builder's recorded kubeconfig, which is only
# safe when that is this builder's pinned file. Otherwise the builder would resolve the ambient
# current-context, possibly another deployment's cluster, so only its local entry is removed and its
# Deployments are deleted by explicit context instead.
remove_eks_buildx_builder() {
  local builder_name="$1" builder_kubeconfig builder_status
  if [[ "${BUILDX_BUILDER:-}" == "${builder_name}" ]]; then
    unset BUILDX_BUILDER
  fi
  if [[ -z "${KUBE_CONTEXT:-}" ]]; then
    docker buildx rm "${builder_name}" 2>/dev/null || true
    return 0
  fi

  builder_kubeconfig="$(eks_buildx_kubeconfig_path "${builder_name}")"
  builder_status="$(docker buildx inspect "${builder_name}" 2>/dev/null)" || builder_status=""
  if [[ -n "${builder_status}" ]] &&
      buildx_nodes_use_kubeconfig "${builder_kubeconfig}" <<< "${builder_status}"; then
    docker buildx rm "${builder_name}" 2>/dev/null || true
  else
    # --keep-daemon only drops the local entry; it is a no-op when the builder does not exist.
    docker buildx rm --keep-daemon "${builder_name}" 2>/dev/null || true
    if [[ -n "${builder_status}" ]]; then
      echo "Builder ${builder_name} was not pinned to kube context ${KUBE_CONTEXT}; removed it without using its recorded kubeconfig." >&2
      kubectl --context="${KUBE_CONTEXT}" -n "${BUILDKIT_NAMESPACE:-buildkit}" \
        delete deployment builder-amd64 builder-arm64 --ignore-not-found >&2 || true
    fi
  fi
  rm -f "${builder_kubeconfig}"
}

ensure_eks_buildx_builder() {
  local context builder_name namespace builder_kubeconfig
  set_k8s_context_args
  require_eks_context

  context="$(get_k8s_build_context)"
  builder_name="builder-${context//[^a-zA-Z0-9_-]/-}"
  namespace="${BUILDKIT_NAMESPACE:-buildkit}"
  local build_timeout="${BUILDKIT_BOOTSTRAP_TIMEOUT:-900s}"
  local timeout_seconds
  timeout_seconds="$(duration_to_seconds "${build_timeout}")"
  local max_attempts="${BUILDKIT_BOOTSTRAP_ATTEMPTS:-0}"
  if [[ ! "${max_attempts}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: BUILDKIT_BOOTSTRAP_ATTEMPTS must be a non-negative integer." >&2
    return 1
  fi

  remove_eks_buildx_builder "${builder_name}"

  # Without an explicit KUBE_CONTEXT the builder keeps following the caller's KUBECONFIG, as before.
  builder_kubeconfig="${KUBECONFIG:-}"
  if [[ -n "${KUBE_CONTEXT:-}" ]]; then
    builder_kubeconfig="$(write_eks_buildx_kubeconfig "${builder_name}")" || return 1
  fi

  echo "Creating buildx builder with the kubernetes driver for context ${context}"
  BUILDKIT_RESOURCE_OPTS=(
    --driver-opt="requests.cpu=${BUILDKIT_REQUESTS_CPU:-4}"
    --driver-opt="requests.memory=${BUILDKIT_REQUESTS_MEMORY:-8Gi}"
    --driver-opt="limits.cpu=${BUILDKIT_LIMITS_CPU:-8}"
    --driver-opt="limits.memory=${BUILDKIT_LIMITS_MEMORY:-16Gi}"
  )

  KUBECONFIG="${builder_kubeconfig}" docker buildx create \
    --name="${builder_name}" \
    --driver=kubernetes \
    --platform=linux/amd64 \
    --node=builder-amd64 \
    --driver-opt="namespace=${namespace}" \
    --driver-opt="nodeselector=kubernetes.io/arch=amd64" \
    --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
    "${BUILDKIT_RESOURCE_OPTS[@]}" \
    ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}

  KUBECONFIG="${builder_kubeconfig}" docker buildx create \
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

  # Select the builder for this shell and its children only. `docker buildx use` would change the
  # current builder for every other build on this machine. Callers can also pass --builder explicitly.
  export BUILDX_BUILDER="${builder_name}"
  echo "BUILDX_BUILDER=${builder_name}"
  echo "Bootstrapping builder..."

  # The kubernetes driver creates its builder Deployments as part of buildx
  # bootstrap. Retry within the overall deadline to allow for nodepool cold
  # starts; older buildx versions reject the Kubernetes driver's timeout option.
  # A kubectl pre-wait cannot help before buildx has created anything.
  local deadline=$(( $(date +%s) + timeout_seconds ))
  local attempt=1
  while true; do
    local now remaining attempt_label builder_status
    now="$(date +%s)"
    remaining=$((deadline - now))
    if [[ "${remaining}" -le 0 ]]; then
      echo "ERROR: buildx bootstrap did not complete within ${build_timeout}; dumping buildkit namespace state" >&2
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        get all -n "${namespace}" >&2 || true
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        describe pods -n "${namespace}" >&2 || true
      dump_node_provisioning_state
      return 1
    fi

    if [[ "${max_attempts}" -gt 0 ]]; then
      attempt_label="attempt ${attempt}/${max_attempts}"
    else
      attempt_label="attempt ${attempt}"
    fi
    echo "Bootstrapping buildx builder (${attempt_label}, ${remaining}s remaining of ${build_timeout})..."
    if builder_status="$(run_with_optional_timeout "${remaining}s" docker buildx inspect "${builder_name}" --bootstrap 2>&1)"; then
      printf '%s\n' "${builder_status}"
      if buildx_nodes_are_ready <<< "${builder_status}"; then
        break
      fi
      echo "buildx inspect returned success but both native builders are not running." >&2
    else
      printf '%s\n' "${builder_status}" >&2
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
