#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="${REPO_ROOT}/deployment/k8s/workflowWeb.sh"
WORK_DIR="$(mktemp -d)"
CALLS="${WORK_DIR}/kubectl.calls"
EXEC_STDIN="${WORK_DIR}/kubectl-exec.stdin"
PORT_FORWARD_COUNT="${WORK_DIR}/port-forward.count"
start_pid=""

cleanup() {
  if [[ -n "${start_pid}" ]] && kill -0 "${start_pid}" 2>/dev/null; then
    kill -TERM "${start_pid}" 2>/dev/null || true
    wait "${start_pid}" 2>/dev/null || true
  fi
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

cat >"${WORK_DIR}/kubectl" <<'FAKE_KUBECTL'
#!/usr/bin/env bash
set -euo pipefail
printf '%q ' "$@" >>"${FAKE_KUBECTL_CALLS}"
printf '\n' >>"${FAKE_KUBECTL_CALLS}"
if [[ " $* " == *" exec "* ]]; then
  cat >>"${FAKE_KUBECTL_EXEC_STDIN}"
fi
if [[ " $* " == *" port-forward "* ]]; then
  count=0
  if [[ -f "${FAKE_PORT_FORWARD_COUNT}" ]]; then
    count="$(cat "${FAKE_PORT_FORWARD_COUNT}")"
  fi
  count=$((count + 1))
  printf '%s\n' "${count}" >"${FAKE_PORT_FORWARD_COUNT}"
  if [[ "${count}" -eq 1 ]]; then
    exit 23
  fi
  while true; do
    sleep 1
  done
fi
FAKE_KUBECTL
chmod +x "${WORK_DIR}/kubectl"

export FAKE_KUBECTL_CALLS="${CALLS}"
export FAKE_KUBECTL_EXEC_STDIN="${EXEC_STDIN}"
export FAKE_PORT_FORWARD_COUNT="${PORT_FORWARD_COUNT}"
export KUBECTL="${WORK_DIR}/kubectl"
export KUBE_CONTEXT="ignored-context"
export WORKFLOW_NAMESPACE="ignored-namespace"
export WORKFLOW_NAME="ignored-workflow"
export WORKFLOW_MANAGE_LOCAL_PORT="9999"

COMMON_ARGS=(
  --kubectl-context kind-developer-test
  --namespace migration-test
  --workflow-name migration-workflow
  --argo-server https://argo-web:2746
  --console-pod migration-console-test-0
  --console-statefulset migration-console-test
)

"${SCRIPT}" start "${COMMON_ARGS[@]}" --local-port 8181 >"${WORK_DIR}/start.out" 2>&1 &
start_pid="$!"
for _ in $(seq 1 100); do
  [[ -f "${PORT_FORWARD_COUNT}" ]] && [[ "$(cat "${PORT_FORWARD_COUNT}")" -ge 2 ]] && break
  sleep 0.05
done
[[ -f "${PORT_FORWARD_COUNT}" ]] && [[ "$(cat "${PORT_FORWARD_COUNT}")" -ge 2 ]] || {
  echo "Expected the failed port-forward to be restarted." >&2
  cat "${WORK_DIR}/start.out" >&2
  exit 1
}
kill -TERM "${start_pid}"
if wait "${start_pid}"; then
  start_status=0
else
  start_status="$?"
fi
start_pid=""
[[ "${start_status}" -eq 143 ]] || {
  echo "Expected the supervised start to stop with status 143, got ${start_status}." >&2
  exit 1
}

grep -F -- "--context kind-developer-test -n migration-test rollout status statefulset/migration-console-test --timeout=10m" "${CALLS}"
grep -F -- "--context kind-developer-test -n migration-test exec -i migration-console-test-0 -- env" "${CALLS}"
grep -F -- "MANAGE_WEB_ARGO_SERVER=https://argo-web:2746" "${CALLS}"
grep -F -- "MANAGE_WEB_WORKFLOW=migration-workflow" "${CALLS}"
[[ "$(grep -F -c -- "--context kind-developer-test -n migration-test port-forward statefulset/migration-console-test 8181:8000 --address 127.0.0.1" "${CALLS}")" -eq 2 ]]
grep -F -- "Port-forward exited with status 23; retrying" "${WORK_DIR}/start.out"
grep -F -- "Restarting port-forward (attempt 2)" "${WORK_DIR}/start.out"
# The in-pod server must stay loopback-only; kubectl port-forward is its only ingress.
grep -F -- "--web-host 127.0.0.1" "${EXEC_STDIN}"
if grep -F -- "0.0.0.0" "${EXEC_STDIN}"; then
  echo "The in-pod Web UI server must not bind all interfaces." >&2
  exit 1
fi

: >"${CALLS}"
"${SCRIPT}" status "${COMMON_ARGS[@]}"
grep -F -- "MANAGE_WEB_ACTION=status" "${CALLS}"

: >"${CALLS}"
"${SCRIPT}" logs "${COMMON_ARGS[@]}"
grep -F -- "MANAGE_WEB_ACTION=logs" "${CALLS}"
grep -F -- "MANAGE_WEB_FOLLOW_LOGS=false" "${CALLS}"

: >"${CALLS}"
"${SCRIPT}" logs "${COMMON_ARGS[@]}" --follow
grep -F -- "MANAGE_WEB_FOLLOW_LOGS=true" "${CALLS}"

: >"${CALLS}"
"${SCRIPT}" stop "${COMMON_ARGS[@]}"
grep -F -- "MANAGE_WEB_ACTION=stop" "${CALLS}"

: >"${CALLS}"
if "${SCRIPT}" start "${COMMON_ARGS[@]}" --local-port 70000 >"${WORK_DIR}/invalid.out" 2>&1; then
  echo "Expected an invalid local port to fail." >&2
  exit 1
fi
grep -F -- "port must be between 1 and 65535" "${WORK_DIR}/invalid.out"
[[ ! -s "${CALLS}" ]] || {
  echo "kubectl was called before the invalid port was rejected." >&2
  exit 1
}

"${SCRIPT}" --help >"${WORK_DIR}/help.out"
grep -F -- "Usage: ${SCRIPT} <action> [options]" "${WORK_DIR}/help.out"
grep -F -- "--kubectl-context <name>" "${WORK_DIR}/help.out"
grep -F -- "--namespace <val>" "${WORK_DIR}/help.out"
grep -F -- "--workflow-name <name>" "${WORK_DIR}/help.out"
grep -F -- "--local-port <port>" "${WORK_DIR}/help.out"

echo "workflowWeb.sh contract tests passed."
