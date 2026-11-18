#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<EOF
Start and access the packaged Workflow Web UI in the migration-console pod.

Usage: $0 <action> [options]

Actions:
  start                                     Start the Web UI server if needed and keep a resilient
                                            browser-facing port-forward open.
  status                                    Check the Web UI process and health endpoint in the pod.
  logs                                      Show the last 200 lines from the Web UI server log.
  stop                                      Stop the Web UI server running in the pod.

Options:
  --kubectl-context <name>                  Kubernetes context (default: current context).
                                            Matches aws-bootstrap.sh --kubectl-context.
  --namespace <val>                         K8s namespace (default: ma).
                                            Matches aws-bootstrap.sh --namespace.
  --workflow-name <name>                    Managed workflow name (default: migration-workflow).
  --local-port <port>                       Browser-facing local port (default: 8000).
  --argo-server <url>                       Argo server URL used inside the migration-console pod
                                            (default: https://argo-server:2746).
  --console-pod <name>                      Migration console pod (default: migration-console-0).
  --console-statefulset <name>              Migration console StatefulSet
                                            (default: migration-console).
  --follow                                  Follow server logs. Only valid with the logs action.
  -h, --help                                Show this help.

Examples:
  $0 start --kubectl-context kind-ma --namespace ma
  $0 start --kubectl-context kind-ma --local-port 8001
  $0 logs --kubectl-context kind-ma --follow
  $0 stop --kubectl-context kind-ma
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

KUBECTL="${KUBECTL:-kubectl}"
KUBE_CONTEXT="${KUBE_CONTEXT:-}"
NAMESPACE="${WORKFLOW_NAMESPACE:-ma}"
WORKFLOW_NAME="${WORKFLOW_NAME:-migration-workflow}"
LOCAL_PORT="${WORKFLOW_MANAGE_LOCAL_PORT:-8000}"
REMOTE_PORT=8000
POD_NAME="${MIGRATION_CONSOLE_POD:-migration-console-0}"
STATEFULSET_NAME="${MIGRATION_CONSOLE_STATEFULSET:-migration-console}"
ARGO_SERVER="${WORKFLOW_ARGO_SERVER:-https://argo-server:2746}"
ACTION=""
FOLLOW_LOGS=false

require_option_value() {
  local option="$1"
  local value="${2:-}"
  [[ -n "${value}" && "${value}" != --* ]] ||
    fail "${option} requires a value"
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    start|status|logs|stop)
      [[ -z "${ACTION}" ]] || fail "only one action may be specified"
      ACTION="$1"
      shift
      ;;
    --kubectl-context)
      require_option_value "$1" "${2:-}"
      KUBE_CONTEXT="$2"
      shift 2
      ;;
    --namespace)
      require_option_value "$1" "${2:-}"
      NAMESPACE="$2"
      shift 2
      ;;
    --workflow-name)
      require_option_value "$1" "${2:-}"
      WORKFLOW_NAME="$2"
      shift 2
      ;;
    --local-port)
      require_option_value "$1" "${2:-}"
      LOCAL_PORT="$2"
      shift 2
      ;;
    --argo-server)
      require_option_value "$1" "${2:-}"
      ARGO_SERVER="$2"
      shift 2
      ;;
    --console-pod)
      require_option_value "$1" "${2:-}"
      POD_NAME="$2"
      shift 2
      ;;
    --console-statefulset)
      require_option_value "$1" "${2:-}"
      STATEFULSET_NAME="$2"
      shift 2
      ;;
    --follow)
      FOLLOW_LOGS=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "unknown action or option: $1"
      ;;
  esac
done

if [[ -z "${ACTION}" ]]; then
  usage
  exit 0
fi
[[ "${FOLLOW_LOGS}" == "false" || "${ACTION}" == "logs" ]] ||
  fail "--follow is only valid with the logs action"

require_command "${KUBECTL}"
if [[ -z "${KUBE_CONTEXT}" ]]; then
  KUBE_CONTEXT="$("${KUBECTL}" config current-context)"
fi

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
echo "The port-forward will restart after unexpected exits."
echo "Press Ctrl-C to stop retrying. The in-pod server will remain running."

PORT_FORWARD_RETRY_SECONDS=2
port_forward_pid=""

cleanup_port_forward() {
  if [[ -n "${port_forward_pid}" ]] && kill -0 "${port_forward_pid}" 2>/dev/null; then
    kill "${port_forward_pid}" 2>/dev/null || true
    wait "${port_forward_pid}" 2>/dev/null || true
  fi
}

trap cleanup_port_forward EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

attempt=1
while true; do
  if (( attempt > 1 )); then
    echo "Restarting port-forward (attempt ${attempt})..." >&2
  fi

  "${KUBECTL}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    port-forward "statefulset/${STATEFULSET_NAME}" "${LOCAL_PORT}:${REMOTE_PORT}" \
    --address 127.0.0.1 &
  port_forward_pid="$!"

  if wait "${port_forward_pid}"; then
    port_forward_status=0
  else
    port_forward_status="$?"
  fi
  port_forward_pid=""

  echo "Port-forward exited with status ${port_forward_status}; retrying in ${PORT_FORWARD_RETRY_SECONDS} seconds." >&2
  sleep "${PORT_FORWARD_RETRY_SECONDS}"
  attempt=$((attempt + 1))
done
