#!/usr/bin/env bash
#
# installK6Chart.sh — install or uninstall the standalone k6 load-test chart (operator + scenarios
# + RBAC).
#
# The single implementation, used by deployCdcLoadTestConfig.sh and by the test automation runner
# (libraries/testAutomation). It is deliberately separate from the migration assistant chart: a
# normal migration deploy has no k6 operator, no scenarios and no RBAC, and does not pay for them.
#
# It owns the whole release lifecycle: a caller that tears the chart down comes through here too,
# and never runs helm itself. A caller may still choose the release with --release (or K6_RELEASE),
# but must then pass the SAME name to both commands. One that passes neither gets one default for
# both, which is why deployCdcLoadTestConfig.sh names no release at all.
#
# The load-test-only migrations/k6_runner image contains the pinned k6 executable, its compiled
# extensions, and the scenarios under /scripts. Its default is derived from the registry holding the
# migration images.
#
# Usage:
#   ./installK6Chart.sh [install|uninstall] [--context CTX] [--namespace ma]
#                       [--release k6-load-test] [--chart PATH] [--runner-image REF]
#                       [--registry-prefix PREFIX]
#
#   The command is optional and defaults to `install`. `uninstall` takes --context, --namespace and
#   --release; the image and chart options are an error there, because they would say nothing about
#   the release to remove. An absent release is not an error — uninstall is safe to run on a
#   namespace that never had the chart.
#
#   --runner-image     complete reference; wins over --registry-prefix. Accepts repo:tag and
#                      repo@sha256:... (a digest pins exact content and wins over the tag).
#   --registry-prefix  where the migration images live, e.g. "localhost:5001/". Omit it and the
#                      prefix is read from the migration-image-config ConfigMap in the namespace.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
RELEASE="${K6_RELEASE:-k6-load-test}"
CHART="${K6_CHART:-${SCRIPT_DIR}/charts/components/k6LoadTest}"
RUNNER_IMAGE="${K6_IMAGE:-}"
REGISTRY_PREFIX="${REGISTRY_PREFIX:-}"

# ECR flattens every image into ONE repository and distinguishes them by tag
# (<repo>:migrations_k6_runner_latest). Every other registry keeps the <prefix>migrations/<image>
# layout. The runner-image default branches on this.
ECR_PATTERN='^[0-9]+\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/'

die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# The command is a leading bare word. Anything that starts with `-` is an option, so the older
# option-only call form still means `install`.
COMMAND=install
[[ $# -gt 0 && "$1" != -* ]] && { COMMAND="$1"; shift; }

# Flags that say nothing about an uninstall. A flag is an explicit statement about THIS command, so
# an irrelevant one is a mistake worth reporting. The matching environment variables stay ignored:
# they are ambient, and a developer with K6_IMAGE exported must still be able to tear down.
INSTALL_ONLY=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context)         CONTEXT="$2"; shift 2 ;;
    --namespace|-n)    NAMESPACE="$2"; shift 2 ;;
    --release)         RELEASE="$2"; shift 2 ;;
    --chart)           CHART="$2";           INSTALL_ONLY+=("$1"); shift 2 ;;
    --runner-image)    RUNNER_IMAGE="$2";    INSTALL_ONLY+=("$1"); shift 2 ;;
    --registry-prefix) REGISTRY_PREFIX="$2"; INSTALL_ONLY+=("$1"); shift 2 ;;
    -h|--help)         sed -n '2,/^[^#]/p' "$0"; exit 0 ;;
    *)                 die "unknown option '$1'" ;;
  esac
done

[[ "$COMMAND" == uninstall && ${#INSTALL_ONLY[@]} -gt 0 ]] \
  && die "these options apply to install only: ${INSTALL_ONLY[*]}"

command -v helm >/dev/null || die "helm not found (needed to manage the $RELEASE release)"

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
    -o jsonpath='{.data.captureProxyImage}' 2>/dev/null)"
  case "$proxy_img" in
    *migrations/*) printf '%s' "${proxy_img%migrations/*}" ;;
    *)             printf '' ;;
  esac
}

# ── Install ────────────────────────────────────────────────────────────────────
cmd_install() {
  [[ -d "$CHART" ]] || die "chart not found: $CHART"

  # Resolve the combined runner image.
  if [[ -z "$RUNNER_IMAGE" ]]; then
    [[ -n "$REGISTRY_PREFIX" ]] || REGISTRY_PREFIX="$(derive_registry_prefix)"
    if [[ "$REGISTRY_PREFIX" =~ $ECR_PATTERN ]]; then
      RUNNER_IMAGE="${REGISTRY_PREFIX%/}:migrations_k6_runner_latest"
    else
      RUNNER_IMAGE="${REGISTRY_PREFIX}migrations/k6_runner:latest"
    fi
  fi
  split_ref "$RUNNER_IMAGE"
  local runner_repo="$ref_repo" runner_tag="$ref_tag" runner_digest="$ref_digest"

  helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1
  # Vendor the k6-operator subchart
  helm dependency build "$CHART" >/dev/null 2>&1 \
    || helm dependency update "$CHART" >/dev/null 2>&1 \
    || die "helm dependency build failed for $CHART"

  # Always re-pull the runner image: it is rebuilt in place under a moving tag while iterating on
  # scenarios, so IfNotPresent would pin runner pods to whatever the node cached first. A digest
  # reference is immutable, so it needs no such treatment — but Always costs nothing there either.
  helm --kube-context "$CONTEXT" upgrade --install "$RELEASE" "$CHART" -n "$NAMESPACE" --create-namespace \
    --set image.repository="$runner_repo" \
    ${runner_digest:+--set image.digest="$runner_digest"} \
    ${runner_tag:+--set image.tag="$runner_tag"} \
    --set image.pullPolicy=Always \
    --timeout 300s 2>&1 | sed 's/^/  /'

  kubectl --context "$CONTEXT" -n "$NAMESPACE" rollout status deploy \
    -l app.kubernetes.io/name=k6-operator --timeout=180s 2>&1 | tail -1 | sed 's/^/  /'

  printf '  \033[1;32m✓\033[0m k6 chart installed (release: %s, runner: %s)\n' \
    "$RELEASE" "$RUNNER_IMAGE"
}

# ── Uninstall ──────────────────────────────────────────────────────────────────
# A release that is not there is not an error: teardown runs on namespaces that never had the chart,
# and it must not stop the rest of a caller's teardown. Only a true helm failure exits non-zero.
# CRDs the chart brought are left alone — helm does not remove them, and neither did the caller this
# replaces.
cmd_uninstall() {
  if ! helm --kube-context "$CONTEXT" status "$RELEASE" -n "$NAMESPACE" >/dev/null 2>&1; then
    printf '  \033[1;32m✓\033[0m k6 chart not installed (release: %s, namespace: %s)\n' \
      "$RELEASE" "$NAMESPACE"
    return 0
  fi

  helm --kube-context "$CONTEXT" uninstall "$RELEASE" -n "$NAMESPACE" --timeout 300s 2>&1 | sed 's/^/  /'

  printf '  \033[1;32m✓\033[0m k6 chart uninstalled (release: %s)\n' "$RELEASE"
}

case "$COMMAND" in
  install)   cmd_install ;;
  uninstall) cmd_uninstall ;;
  *)         die "unknown command '$COMMAND' (use install | uninstall)" ;;
esac
