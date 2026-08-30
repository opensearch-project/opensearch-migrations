#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Start and access the packaged Workflow Web UI in the migration-console pod.

Usage:
  deployment/k8s/workflowWeb.sh start
  deployment/k8s/workflowWeb.sh status
  deployment/k8s/workflowWeb.sh logs [--follow]
  deployment/k8s/workflowWeb.sh stop

Environment:
  KUBE_CONTEXT                 Kubernetes context (default: current context)
  WORKFLOW_NAMESPACE          Kubernetes namespace (default: ma)
  WORKFLOW_NAME               Managed workflow name (default: migration-workflow)
  WORKFLOW_MANAGE_LOCAL_PORT  Browser-facing local port (default: 8000)
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

validate_port() {
  local value="$1"
  [[ "${value}" =~ ^[0-9]+$ ]] || fail "port must be an integer: ${value}"
  (( value >= 1 && value <= 65535 )) || fail "port must be between 1 and 65535: ${value}"
}

validate_kubernetes_name() {
  local label="$1"
  local value="$2"
  [[ "${value}" =~ ^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$ ]] ||
    fail "${label} must be a lowercase Kubernetes name: ${value}"
}

ACTION="${1:-}"
case "${ACTION}" in
  start|status|stop)
    [[ "$#" -eq 1 ]] || fail "${ACTION} does not accept additional arguments"
    ;;
  logs)
    [[ "$#" -le 2 ]] || fail "logs accepts only the optional --follow argument"
    [[ "$#" -eq 1 || "${2}" == "--follow" ]] || fail "unknown logs argument: ${2}"
    ;;
  -h|--help|"")
    usage
    exit 0
    ;;
  *)
    usage >&2
    fail "unknown action: ${ACTION}"
    ;;
esac

KUBECTL="${KUBECTL:-kubectl}"
require_command "${KUBECTL}"

KUBE_CONTEXT="${KUBE_CONTEXT:-$("${KUBECTL}" config current-context)}"
NAMESPACE="${WORKFLOW_NAMESPACE:-ma}"
WORKFLOW_NAME="${WORKFLOW_NAME:-migration-workflow}"
LOCAL_PORT="${WORKFLOW_MANAGE_LOCAL_PORT:-8000}"
REMOTE_PORT=8000
POD_NAME="${MIGRATION_CONSOLE_POD:-migration-console-0}"
STATEFULSET_NAME="${MIGRATION_CONSOLE_STATEFULSET:-migration-console}"
ARGO_SERVER="${WORKFLOW_ARGO_SERVER:-https://argo-server:2746}"
FOLLOW_LOGS=false
[[ "${ACTION}" == "logs" && "${2:-}" == "--follow" ]] && FOLLOW_LOGS=true

[[ -n "${KUBE_CONTEXT}" ]] || fail "no Kubernetes context is configured"
validate_kubernetes_name "namespace" "${NAMESPACE}"
validate_kubernetes_name "workflow name" "${WORKFLOW_NAME}"
validate_kubernetes_name "migration console pod" "${POD_NAME}"
validate_kubernetes_name "migration console StatefulSet" "${STATEFULSET_NAME}"
validate_port "${LOCAL_PORT}"

kube() {
  "${KUBECTL}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" "$@"
}

if [[ "${ACTION}" == "start" ]]; then
  echo "Waiting for statefulset/${STATEFULSET_NAME} in ${KUBE_CONTEXT}/${NAMESPACE}..."
  kube rollout status "statefulset/${STATEFULSET_NAME}" --timeout=10m
fi

kube exec -i "${POD_NAME}" -- env \
  MANAGE_WEB_ACTION="${ACTION}" \
  MANAGE_WEB_ARGO_SERVER="${ARGO_SERVER}" \
  MANAGE_WEB_FOLLOW_LOGS="${FOLLOW_LOGS}" \
  MANAGE_WEB_NAMESPACE="${NAMESPACE}" \
  MANAGE_WEB_PORT="${REMOTE_PORT}" \
  MANAGE_WEB_WORKFLOW="${WORKFLOW_NAME}" \
  bash -s <<'REMOTE_SCRIPT'
set -euo pipefail

PID_FILE=/tmp/workflow-manage-web.pid
LOG_FILE=/tmp/workflow-manage-web.log
STATIC_INDEX=/root/lib/console_link/console_link/workflow/web/static/index.html

health_check() {
  python3 - "${MANAGE_WEB_PORT}" <<'PY'
import json
import sys
import urllib.request

with urllib.request.urlopen(
    f"http://127.0.0.1:{sys.argv[1]}/api/v1/system/health",
    timeout=2,
) as response:
    body = json.load(response)
    if response.status != 200 or body.get("status") != "ok":
        raise SystemExit(1)
PY
}

managed_pid() {
  [[ -s "${PID_FILE}" ]] || return 1
  local pid
  pid="$(cat "${PID_FILE}")"
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  kill -0 "${pid}" 2>/dev/null || return 1
  printf '%s\n' "${pid}"
}

case "${MANAGE_WEB_ACTION}" in
  start)
    [[ -s "${STATIC_INDEX}" ]] || {
      echo "The packaged frontend is missing at ${STATIC_INDEX}." >&2
      echo "Rebuild and redeploy the migration-console image." >&2
      exit 1
    }

    if health_check >/dev/null 2>&1; then
      echo "Workflow Web UI is already healthy in the pod."
      exit 0
    fi

    if pid="$(managed_pid)"; then
      echo "Workflow Web UI process ${pid} is running but is not healthy." >&2
      tail -n 80 "${LOG_FILE}" >&2 2>/dev/null || true
      exit 1
    fi

    rm -f "${PID_FILE}"
    nohup workflow manage \
      --web \
      --namespace "${MANAGE_WEB_NAMESPACE}" \
      --workflow-name "${MANAGE_WEB_WORKFLOW}" \
      --argo-server "${MANAGE_WEB_ARGO_SERVER}" \
      --web-host 0.0.0.0 \
      --web-port "${MANAGE_WEB_PORT}" \
      >"${LOG_FILE}" 2>&1 </dev/null &
    echo "$!" >"${PID_FILE}"

    for _ in $(seq 1 60); do
      if health_check >/dev/null 2>&1; then
        echo "Workflow Web UI started as process $(cat "${PID_FILE}")."
        exit 0
      fi
      if ! managed_pid >/dev/null; then
        echo "Workflow Web UI exited before becoming healthy." >&2
        tail -n 120 "${LOG_FILE}" >&2 2>/dev/null || true
        exit 1
      fi
      sleep 1
    done

    echo "Workflow Web UI did not become healthy within 60 seconds." >&2
    tail -n 120 "${LOG_FILE}" >&2 2>/dev/null || true
    exit 1
    ;;
  status)
    if pid="$(managed_pid)" && health_check >/dev/null 2>&1; then
      echo "Workflow Web UI is healthy (process ${pid}, port ${MANAGE_WEB_PORT})."
      exit 0
    fi
    echo "Workflow Web UI is not running or not healthy." >&2
    exit 1
    ;;
  logs)
    [[ -f "${LOG_FILE}" ]] || {
      echo "No Workflow Web UI log exists at ${LOG_FILE}." >&2
      exit 1
    }
    if [[ "${MANAGE_WEB_FOLLOW_LOGS}" == "true" ]]; then
      exec tail -n 200 -f "${LOG_FILE}"
    fi
    tail -n 200 "${LOG_FILE}"
    ;;
  stop)
    if ! pid="$(managed_pid)"; then
      rm -f "${PID_FILE}"
      echo "Workflow Web UI is not running."
      exit 0
    fi

    kill "${pid}"
    for _ in $(seq 1 10); do
      kill -0 "${pid}" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "${pid}" 2>/dev/null; then
      kill -KILL "${pid}"
    fi
    rm -f "${PID_FILE}"
    echo "Stopped Workflow Web UI process ${pid}."
    ;;
esac
REMOTE_SCRIPT

if [[ "${ACTION}" != "start" ]]; then
  exit 0
fi

echo
echo "Forwarding http://127.0.0.1:${LOCAL_PORT}"
echo "Press Ctrl-C to stop the port-forward. The in-pod server will remain running."
exec "${KUBECTL}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  port-forward "statefulset/${STATEFULSET_NAME}" "${LOCAL_PORT}:${REMOTE_PORT}" \
  --address 127.0.0.1
