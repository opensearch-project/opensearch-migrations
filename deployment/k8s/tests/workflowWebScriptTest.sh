#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="${REPO_ROOT}/deployment/k8s/workflowWeb.sh"
WORK_DIR="$(mktemp -d)"
CALLS="${WORK_DIR}/kubectl.calls"

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

cat >"${WORK_DIR}/kubectl" <<'FAKE_KUBECTL'
#!/usr/bin/env bash
set -euo pipefail
printf '%q ' "$@" >>"${FAKE_KUBECTL_CALLS}"
printf '\n' >>"${FAKE_KUBECTL_CALLS}"
if [[ " $* " == *" exec "* ]]; then
  cat >/dev/null
fi
FAKE_KUBECTL
chmod +x "${WORK_DIR}/kubectl"

export FAKE_KUBECTL_CALLS="${CALLS}"
export KUBECTL="${WORK_DIR}/kubectl"
export KUBE_CONTEXT="kind-developer-test"
export WORKFLOW_NAMESPACE="migration-test"
export WORKFLOW_NAME="migration-workflow"
export WORKFLOW_MANAGE_LOCAL_PORT="8181"

"${SCRIPT}" start
grep -F -- "--context kind-developer-test -n migration-test rollout status statefulset/migration-console --timeout=10m" "${CALLS}"
grep -F -- "--context kind-developer-test -n migration-test exec -i migration-console-0 -- env" "${CALLS}"
grep -F -- "MANAGE_WEB_ARGO_SERVER=https://argo-server:2746" "${CALLS}"
grep -F -- "MANAGE_WEB_WORKFLOW=migration-workflow" "${CALLS}"
grep -F -- "--context kind-developer-test -n migration-test port-forward statefulset/migration-console 8181:8000 --address 127.0.0.1" "${CALLS}"

: >"${CALLS}"
"${SCRIPT}" status
grep -F -- "MANAGE_WEB_ACTION=status" "${CALLS}"

: >"${CALLS}"
"${SCRIPT}" logs
grep -F -- "MANAGE_WEB_ACTION=logs" "${CALLS}"
grep -F -- "MANAGE_WEB_FOLLOW_LOGS=false" "${CALLS}"

: >"${CALLS}"
"${SCRIPT}" logs --follow
grep -F -- "MANAGE_WEB_FOLLOW_LOGS=true" "${CALLS}"

: >"${CALLS}"
"${SCRIPT}" stop
grep -F -- "MANAGE_WEB_ACTION=stop" "${CALLS}"

: >"${CALLS}"
if WORKFLOW_MANAGE_LOCAL_PORT=70000 "${SCRIPT}" start >"${WORK_DIR}/invalid.out" 2>&1; then
  echo "Expected an invalid local port to fail." >&2
  exit 1
fi
grep -F -- "port must be between 1 and 65535" "${WORK_DIR}/invalid.out"
[[ ! -s "${CALLS}" ]] || {
  echo "kubectl was called before the invalid port was rejected." >&2
  exit 1
}

echo "workflowWeb.sh contract tests passed."
