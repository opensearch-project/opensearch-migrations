#!/usr/bin/env bash
#
# installK6Chart.sh — install the standalone k6 load-test chart (operator + scenarios + RBAC).
#
# The single implementation, used by deployCdcLoadTestConfig.sh and by the test automation runner
# (libraries/testAutomation). It is deliberately separate from the migration assistant chart: a
# normal migration deploy has no k6 operator, no scenarios and no RBAC, and does not pay for them.
#
# Two images are resolved here, by different rules:
#   runner  — stock grafana/k6. Only the REPOSITORY is chosen (to reach a mirror); the k6 version is
#             pinned once in the chart's values.yaml. Pass a `:tag` to override the version too.
#   scripts — migrations/k6_scripts, the data image mounted at /scripts. It is a migrations/* image,
#             so its default is derived from the registry the migration's own images came from.
#
# Usage:
#   ./installK6Chart.sh [--context CTX] [--namespace ma] [--release k6-load-test]
#                       [--chart PATH] [--runner-image REF] [--scripts-image REF]
#                       [--registry-prefix PREFIX]
#
#   --scripts-image    complete reference; wins over --registry-prefix. Accepts repo:tag and
#                      repo@sha256:... (a digest pins exact content and wins over the tag).
#   --registry-prefix  where the migration images live, e.g. "localhost:5001/". Omit it and the
#                      prefix is read from the migration-image-config ConfigMap in the namespace.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
RELEASE="${K6_RELEASE:-k6-load-test}"
CHART="${K6_CHART:-${SCRIPT_DIR}/charts/components/k6LoadTest}"
RUNNER_IMAGE="${K6_IMAGE:-mirror.gcr.io/grafana/k6}"
SCRIPTS_IMAGE="${K6_SCRIPTS_IMAGE:-}"
REGISTRY_PREFIX="${REGISTRY_PREFIX:-}"

# ECR flattens every image into ONE repository and distinguishes them by tag
# (<repo>:migrations_k6_scripts_latest). Every other registry keeps the <prefix>migrations/<image>
# layout. The scripts-image default branches on this.
ECR_PATTERN='^[0-9]+\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/'

die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context)         CONTEXT="$2"; shift 2 ;;
    --namespace|-n)    NAMESPACE="$2"; shift 2 ;;
    --release)         RELEASE="$2"; shift 2 ;;
    --chart)           CHART="$2"; shift 2 ;;
    --runner-image)    RUNNER_IMAGE="$2"; shift 2 ;;
    --scripts-image)   SCRIPTS_IMAGE="$2"; shift 2 ;;
    --registry-prefix) REGISTRY_PREFIX="$2"; shift 2 ;;
    -h|--help)         sed -n '2,/^[^#]/p' "$0"; exit 0 ;;
    *)                 die "unknown option '$1'" ;;
  esac
done

command -v helm >/dev/null || die "helm not found (needed to install $CHART)"
[[ -d "$CHART" ]] || die "chart not found: $CHART"

# Split a reference into ref_repo/ref_tag/ref_digest. Only one of tag/digest is ever set. A colon
# that comes BEFORE the last slash is a registry port (host:5001/repo), not a tag.
ref_repo="" ref_tag="" ref_digest=""
split_ref() {
  local ref="$1"
  ref_repo="$ref" ref_tag="" ref_digest=""
  if [[ "$ref" == *@* ]]; then
    ref_repo="${ref%%@*}"; ref_digest="${ref#*@}"
  elif [[ "${ref##*/}" == *:* ]]; then
    ref_repo="${ref%:*}"; ref_tag="${ref##*:}"
  else
    ref_tag="latest"
  fi
}

# The registry the migration's own images came from, taken off captureProxyImage: everything before
# "migrations/". An ECR registry has no such prefix to strip, so the whole repository is the prefix.
derive_registry_prefix() {
  local proxy_img
  proxy_img="$(kubectl --context "$CONTEXT" -n "$NAMESPACE" get cm migration-image-config \
    -o jsonpath='{.data.captureProxyImage}' 2>/dev/null || true)"
  case "$proxy_img" in
    *migrations/*) printf '%s' "${proxy_img%migrations/*}" ;;
    *)             printf '' ;;
  esac
}

# ── Resolve the runner image ───────────────────────────────────────────────────
# Repository only unless the caller spelled out a tag — the chart owns the k6 version.
runner_repo="$RUNNER_IMAGE" runner_tag=""
if [[ "${RUNNER_IMAGE##*/}" == *:* ]]; then
  runner_repo="${RUNNER_IMAGE%:*}"; runner_tag="${RUNNER_IMAGE##*:}"
fi

# ── Resolve the scripts image ──────────────────────────────────────────────────
if [[ -z "$SCRIPTS_IMAGE" ]]; then
  [[ -n "$REGISTRY_PREFIX" ]] || REGISTRY_PREFIX="$(derive_registry_prefix)"
  if [[ "$REGISTRY_PREFIX" =~ $ECR_PATTERN ]]; then
    SCRIPTS_IMAGE="${REGISTRY_PREFIX%/}:migrations_k6_scripts_latest"
  else
    SCRIPTS_IMAGE="${REGISTRY_PREFIX}migrations/k6_scripts:latest"
  fi
fi
split_ref "$SCRIPTS_IMAGE"
scripts_repo="$ref_repo" scripts_tag="$ref_tag" scripts_digest="$ref_digest"

# ── Install ────────────────────────────────────────────────────────────────────
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
# Vendor the k6-operator subchart: offline from Chart.lock if already vendored, else fetch it.
helm dependency build "$CHART" >/dev/null 2>&1 \
  || helm dependency update "$CHART" >/dev/null 2>&1 \
  || die "helm dependency build failed for $CHART"

# Always re-pull the scripts image: it is rebuilt in place under a moving tag while iterating on
# scenarios, so IfNotPresent would pin runner pods to whatever the node cached first. A digest
# reference is immutable, so it needs no such treatment — but Always costs nothing there either.
helm --kube-context "$CONTEXT" upgrade --install "$RELEASE" "$CHART" -n "$NAMESPACE" --create-namespace \
  --set image.repository="$runner_repo" ${runner_tag:+--set image.tag="$runner_tag"} \
  --set image.pullPolicy=IfNotPresent \
  --set scriptsImage.repository="$scripts_repo" \
  ${scripts_digest:+--set scriptsImage.digest="$scripts_digest"} \
  ${scripts_tag:+--set scriptsImage.tag="$scripts_tag"} \
  --set scriptsImage.pullPolicy=Always \
  --timeout 300s 2>&1 | sed 's/^/  /'

kubectl --context "$CONTEXT" -n "$NAMESPACE" rollout status deploy \
  -l app.kubernetes.io/name=k6-operator --timeout=180s 2>&1 | tail -1 | sed 's/^/  /' || true

printf '  \033[1;32m✓\033[0m k6 chart installed (runner: %s, scripts: %s)\n' \
  "$RUNNER_IMAGE" "$SCRIPTS_IMAGE"
