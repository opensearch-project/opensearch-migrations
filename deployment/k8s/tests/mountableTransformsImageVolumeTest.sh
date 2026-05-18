#!/usr/bin/env bash

set -euo pipefail

MIGRATIONS_REPO_ROOT_DIR="$(git rev-parse --show-toplevel)"
cd "${MIGRATIONS_REPO_ROOT_DIR}"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-mountable-transforms}"
KIND_CONTEXT="${KUBE_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
KIND_REGISTRY_NAME="${KIND_REGISTRY_NAME:-docker-registry}"
KIND_REGISTRY_PORT="${KIND_REGISTRY_PORT:-5002}"
LOCAL_REGISTRY="${LOCAL_REGISTRY:-localhost:${KIND_REGISTRY_PORT}}"
TEST_NAMESPACE="${TEST_NAMESPACE:-mountable-transforms-test}"
IMAGE_REPO="${TRANSFORMS_TEST_IMAGE_REPO:-${LOCAL_REGISTRY}/migrations/mountable-transforms-smoke}"
IMAGE_TAG="${TRANSFORMS_TEST_IMAGE_TAG:-ci}"
POD_NAME="mountable-transforms-smoke"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_command docker
require_command kind
require_command kubectl

if ! kind get clusters | grep -qx "${KIND_CLUSTER_NAME}"; then
  echo "Expected kind cluster '${KIND_CLUSTER_NAME}' to exist." >&2
  echo "Create it with deployment/k8s/tests/kindImageVolumeConfig.yaml before running this test." >&2
  exit 1
fi

if ! kubectl --context "${KIND_CONTEXT}" get nodes >/dev/null 2>&1; then
  echo "Unable to talk to Kubernetes context '${KIND_CONTEXT}'." >&2
  exit 1
fi

if ! docker inspect "${KIND_REGISTRY_NAME}" >/dev/null 2>&1; then
  docker run -d --restart=always \
    -p "127.0.0.1:${KIND_REGISTRY_PORT}:5000" \
    --name "${KIND_REGISTRY_NAME}" \
    registry:2 >/dev/null
elif [[ "$(docker inspect -f '{{.State.Running}}' "${KIND_REGISTRY_NAME}")" != "true" ]]; then
  docker start "${KIND_REGISTRY_NAME}" >/dev/null
fi

if ! docker network inspect kind >/dev/null 2>&1; then
  echo "Expected docker network 'kind' to exist after kind cluster creation." >&2
  exit 1
fi

if [[ "$(docker inspect -f '{{json .NetworkSettings.Networks.kind}}' "${KIND_REGISTRY_NAME}")" == "null" ]]; then
  docker network connect kind "${KIND_REGISTRY_NAME}"
fi

REGISTRY_DIR="/etc/containerd/certs.d/localhost:${KIND_REGISTRY_PORT}"
for node in $(kind get nodes --name "${KIND_CLUSTER_NAME}"); do
  if ! docker exec "${node}" grep -q 'config_path = "/etc/containerd/certs.d"' /etc/containerd/config.toml; then
    echo "Kind node ${node} is missing containerd registry config_path support." >&2
    echo "Recreate the cluster with deployment/k8s/tests/kindImageVolumeConfig.yaml." >&2
    exit 1
  fi

  docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
  cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${KIND_REGISTRY_NAME}:5000"]
EOF
done

WORK_DIR="$(mktemp -d)"
cleanup() {
  kubectl --context "${KIND_CONTEXT}" delete namespace "${TEST_NAMESPACE}" --ignore-not-found=true --wait=false >/dev/null 2>&1 || true
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

TRANSFORMS_DIR="${WORK_DIR}/transforms"
mkdir -p "${TRANSFORMS_DIR}/helpers"
cat > "${TRANSFORMS_DIR}/document.js" <<'EOF'
function main(context) {
  return (document) => {
    document.set(context.get("fieldName") || "mountable_transform_seen", true);
    return document;
  };
}
(() => main)();
EOF
cat > "${TRANSFORMS_DIR}/request.js" <<'EOF'
function main(context) {
  return (request) => {
    const headers = request.get("headers");
    headers.set("x-mountable-transform", context.get("headerValue") || "seen");
    return request;
  };
}
(() => main)();
EOF
cat > "${TRANSFORMS_DIR}/helpers/helper.py" <<'EOF'
VALUE = "mountable_transform_seen"
EOF

PACKAGE_OUTPUT="$(deployment/k8s/package-transforms.sh "${TRANSFORMS_DIR}" "${IMAGE_REPO}" "${IMAGE_TAG}")"
echo "${PACKAGE_OUTPUT}"

PINNED_REF="$(printf '%s\n' "${PACKAGE_OUTPUT}" | sed -n 's/^[[:space:]]*image: "\(.*\)"/\1/p' | tail -1)"
if [[ -z "${PINNED_REF}" ]]; then
  echo "Unable to determine digest-pinned transform image reference from package output." >&2
  exit 1
fi

kubectl --context "${KIND_CONTEXT}" create namespace "${TEST_NAMESPACE}" >/dev/null

cat <<EOF | kubectl --context "${KIND_CONTEXT}" -n "${TEST_NAMESPACE}" apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: ${POD_NAME}
spec:
  restartPolicy: Never
  containers:
    - name: verify
      image: busybox:1.37.0
      command:
        - sh
        - -c
        - |
          set -eu
          test -f /transforms/document.js
          test -f /transforms/request.js
          test -f /transforms/helpers/helper.py
          grep -q mountable_transform_seen /transforms/document.js
          grep -q x-mountable-transform /transforms/request.js
          find /transforms -maxdepth 2 -type f | sort
      volumeMounts:
        - name: user-transforms
          mountPath: /transforms
          readOnly: true
  volumes:
    - name: user-transforms
      image:
        reference: "${PINNED_REF}"
        pullPolicy: Always
EOF

deadline=$((SECONDS + 180))
while (( SECONDS < deadline )); do
  phase="$(kubectl --context "${KIND_CONTEXT}" -n "${TEST_NAMESPACE}" get pod "${POD_NAME}" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  case "${phase}" in
    Succeeded)
      kubectl --context "${KIND_CONTEXT}" -n "${TEST_NAMESPACE}" logs "${POD_NAME}"
      echo "Mountable transforms image volume smoke test passed."
      exit 0
      ;;
    Failed)
      kubectl --context "${KIND_CONTEXT}" -n "${TEST_NAMESPACE}" describe pod "${POD_NAME}" >&2 || true
      kubectl --context "${KIND_CONTEXT}" -n "${TEST_NAMESPACE}" logs "${POD_NAME}" >&2 || true
      exit 1
      ;;
  esac
  sleep 3
done

kubectl --context "${KIND_CONTEXT}" -n "${TEST_NAMESPACE}" describe pod "${POD_NAME}" >&2 || true
kubectl --context "${KIND_CONTEXT}" -n "${TEST_NAMESPACE}" logs "${POD_NAME}" >&2 || true
echo "Timed out waiting for ${POD_NAME} to complete." >&2
exit 1
