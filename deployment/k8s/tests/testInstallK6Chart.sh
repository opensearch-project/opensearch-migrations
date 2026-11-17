#!/usr/bin/env bash
set -euo pipefail

# Exercise installK6Chart.sh without a cluster. The fake clients record the Helm invocation so this
# test verifies the ECR flat-repository image mapping rather than merely checking source text.
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/test-install-k6-chart.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

FAKE_BIN="$TEST_ROOT/bin"
HELM_ARGS="$TEST_ROOT/helm-args"
mkdir -p "$FAKE_BIN"

cat > "$FAKE_BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == "config current-context" ]]; then
  printf 'test-context\n'
elif [[ "$*" == *"get cm provider-config"* ]]; then
  exit 0
elif [[ "$*" == *"rollout status deploy"* ]]; then
  printf 'deployment successfully rolled out\n'
else
  printf 'unexpected kubectl invocation: %s\n' "$*" >&2
  exit 1
fi
EOF

cat > "$FAKE_BIN/helm" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  repo|dependency)
    exit 0
    ;;
  --kube-context)
    printf '%s\n' "$@" > "$CAPTURED_HELM_ARGS"
    ;;
  *)
    printf 'unexpected helm invocation: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF

chmod +x "$FAKE_BIN/kubectl" "$FAKE_BIN/helm"

ECR_REPOSITORY="123456789012.dkr.ecr.us-east-2.amazonaws.com/migration-ecr-test"
PATH="$FAKE_BIN:$PATH" CAPTURED_HELM_ARGS="$HELM_ARGS" \
  "$REPO_ROOT/deployment/k8s/installK6Chart.sh" install \
  --context test-context \
  --namespace ma \
  --registry-prefix "$ECR_REPOSITORY"

grep -Fx -- "--set" "$HELM_ARGS" >/dev/null
grep -Fx -- "image.repository=$ECR_REPOSITORY" "$HELM_ARGS" >/dev/null
grep -Fx -- "image.tag=migrations_k6_runner_latest" "$HELM_ARGS" >/dev/null
grep -Fx -- "k6-operator.manager.image.registry=123456789012.dkr.ecr.us-east-2.amazonaws.com" \
  "$HELM_ARGS" >/dev/null
grep -Fx -- "k6-operator.manager.image.repository=migration-ecr-test" "$HELM_ARGS" >/dev/null
grep -Fx -- "k6-operator.manager.image.tag=migrations_k6_operator_latest" "$HELM_ARGS" >/dev/null

PATH="$FAKE_BIN:$PATH" CAPTURED_HELM_ARGS="$HELM_ARGS" \
  "$REPO_ROOT/deployment/k8s/installK6Chart.sh" install \
  --context test-context \
  --namespace ma \
  --runner-image docker-registry:5000/migrations/k6_runner:local \
  --operator-image docker-registry:5000/migrations/k6_operator:local

grep -Fx -- "image.repository=docker-registry:5000/migrations/k6_runner" "$HELM_ARGS" >/dev/null
grep -Fx -- "image.tag=local" "$HELM_ARGS" >/dev/null
grep -Fx -- "k6-operator.manager.image.registry=docker-registry:5000" "$HELM_ARGS" >/dev/null
grep -Fx -- "k6-operator.manager.image.repository=migrations/k6_operator" "$HELM_ARGS" >/dev/null
grep -Fx -- "k6-operator.manager.image.tag=local" "$HELM_ARGS" >/dev/null

printf 'installK6Chart image wiring: PASS\n'
