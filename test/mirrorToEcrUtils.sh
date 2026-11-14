#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcrUtils.sh
. "${REPO_ROOT}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcrUtils.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

crane() {
  [[ "$1" == "digest" ]] || return 2
  case "$2" in
    source:same|destination:same) echo "sha256:same" ;;
    source:different) echo "sha256:source" ;;
    destination:different) echo "sha256:destination" ;;
    *) return 1 ;;
  esac
}

mirror_image_is_current source:same destination:same \
  || fail "matching image digests were not recognized"
if mirror_image_is_current source:different destination:different; then
  fail "different image digests were considered current"
fi
if mirror_image_is_current source:same destination:missing; then
  fail "a missing destination was considered current"
fi

[[ "$(mirror_ecr_repo_name 'quay.io/example/image:v1')" == "mirrored/quay.io/example/image" ]] \
  || fail "tagged image repository parsing failed"
[[ "$(mirror_ecr_repo_name 'registry.example:5000/example/image@sha256:abc')" == \
    "mirrored/registry.example:5000/example/image" ]] \
  || fail "digest image repository parsing failed"

[[ "$(MIRROR_CONCURRENCY=6 mirror_copy_concurrency 4)" == "4" ]] \
  || fail "concurrency was not capped to the image count"
[[ "$(MIRROR_CONCURRENCY=3 mirror_copy_concurrency 10)" == "3" ]] \
  || fail "concurrency override was ignored"
if MIRROR_CONCURRENCY=invalid mirror_copy_concurrency 10 >/dev/null 2>&1; then
  fail "invalid concurrency was accepted"
fi

aws_calls="$(mktemp)"
trap 'rm -f "$aws_calls"' EXIT
aws() {
  echo "$*" >> "$aws_calls"
  if [[ "$1 $2" == "ecr describe-repositories" ]]; then
    printf 'mirrored/quay.io/already\n'
    return 0
  fi
  if [[ "$1 $2" == "ecr create-repository" ]]; then
    return 0
  fi
  return 1
}

ensure_mirror_ecr_repositories us-east-1 \
  $'quay.io/already:v1\nquay.io/new:v1\nquay.io/new:v2'

[[ "$(grep -c '^ecr describe-repositories ' "$aws_calls")" == "1" ]] \
  || fail "repositories were not listed exactly once"
[[ "$(grep -c 'create-repository --repository-name mirrored/quay.io/new ' "$aws_calls")" == "1" ]] \
  || fail "missing repository was not created exactly once"
if grep -q 'create-repository --repository-name mirrored/quay.io/already ' "$aws_calls"; then
  fail "existing repository was recreated"
fi

echo "mirrorToEcrUtils tests passed"
