#!/usr/bin/env bash
#
# Dump the state of a Kubernetes cluster to debug a failed, timed-out or cancelled CI job:
# resources, events, describe output, Argo workflows, Migration Assistant resources, and every
# container's logs (including the previous run of restarted containers). With a kind cluster
# name, also exports node-level logs (kubelet, containerd). Best effort: a failing command
# doesn't stop the rest.
#
# Usage: dump-k8s-diagnostics.sh <kube-context> <output-dir> [kind-cluster-name]

set -uo pipefail

readonly KUBE_CONTEXT="${1:?kube context required}"
readonly OUTPUT_DIR="${2:?output directory required}"
readonly KIND_CLUSTER="${3:-}"

k() { kubectl --context "${KUBE_CONTEXT}" "$@"; }

if ! k cluster-info >/dev/null 2>&1; then
  echo "Kubernetes context ${KUBE_CONTEXT} isn't reachable; nothing to dump"
  exit 0
fi

mkdir -p "${OUTPUT_DIR}/pods"
echo "Dumping cluster diagnostics to ${OUTPUT_DIR}"

k get nodes -o wide > "${OUTPUT_DIR}/nodes.txt" 2>&1
k describe nodes > "${OUTPUT_DIR}/describe-nodes.txt" 2>&1
k get all,pvc,configmaps,jobs,cronjobs -A -o wide > "${OUTPUT_DIR}/resources.txt" 2>&1
k get events -A --sort-by=.lastTimestamp > "${OUTPUT_DIR}/events.txt" 2>&1
k describe pods -A > "${OUTPUT_DIR}/describe-pods.txt" 2>&1

for group in argoproj.io migrations.opensearch.org; do
  resources="$(k api-resources --api-group="${group}" -o name 2>/dev/null | paste -sd, -)"
  if [[ -n "${resources}" ]]; then
    k get "${resources}" -A -o yaml > "${OUTPUT_DIR}/${group}.yaml" 2>&1
  fi
done

pod_count=0
while read -r namespace pod; do
  [[ -n "${pod}" ]] || continue
  log_file="${OUTPUT_DIR}/pods/${namespace}_${pod}"
  k logs -n "${namespace}" "${pod}" --all-containers --prefix --timestamps > "${log_file}.log" 2>&1
  if ! k logs -n "${namespace}" "${pod}" --all-containers --prefix --timestamps --previous \
      > "${log_file}.previous.log" 2>/dev/null; then
    rm -f "${log_file}.previous.log"
  fi
  pod_count=$((pod_count + 1))
done < <(k get pods -A --no-headers -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name 2>/dev/null)

if [[ -n "${KIND_CLUSTER}" ]]; then
  kind export logs "${OUTPUT_DIR}/kind" --name "${KIND_CLUSTER}" >/dev/null 2>&1 ||
    echo "kind export logs failed for ${KIND_CLUSTER}"
fi

# A short summary for the job log; the full dump is in the artifact.
echo "Pods (${pod_count} logged):"
k get pods -A -o wide 2>&1
echo "Warning events:"
k get events -A --field-selector type=Warning --sort-by=.lastTimestamp 2>&1 | tail -n 50
echo "Diagnostics size: $(du -sh "${OUTPUT_DIR}" | cut -f1)"
