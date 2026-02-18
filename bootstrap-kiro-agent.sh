#!/bin/bash
# -----------------------------------------------------------------------------
# Bootstrap Kiro Migration Agent
#
# One-liner:
#   curl -sL -H "Accept: application/vnd.github.raw" \
#     "https://api.github.com/repos/opensearch-project/opensearch-migrations/contents/bootstrap-kiro-agent.sh?ref=kiro-agent" | bash
#
# This script:
#   1. Installs helm silently (if missing)
#   2. Installs kiro-cli (if missing)
#   3. Sets up .kiro agent configuration
#   4. Starts kiro-cli with the opensearch-migration agent
# -----------------------------------------------------------------------------

set -euo pipefail

BRANCH="kiro-agent"
ORG="opensearch-project"
REPO="opensearch-migrations"
API_BASE="https://api.github.com/repos/${ORG}/${REPO}/contents"
WORK_DIR="${WORK_DIR:-./kiro-migration-agent}"

info()  { printf '\033[1;34m[kiro]\033[0m %s\n' "$*"; }
error() { printf '\033[1;31m[kiro]\033[0m %s\n' "$*" >&2; }

# Download a file from GitHub via the API (no CDN caching)
gh_raw() { curl -fsSL -H "Accept: application/vnd.github.raw" "${API_BASE}/${1}?ref=${BRANCH}"; }

# --- 1. Install helm silently ---
if ! command -v helm &>/dev/null; then
  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash &>/dev/null
fi
info "helm $(helm version --short)"

# --- 2. Install kiro-cli ---
if ! command -v kiro-cli &>/dev/null; then
  ARCH=$(uname -m)
  OS=$(uname -s | tr '[:upper:]' '[:lower:]')
  case "$ARCH" in
    x86_64|amd64) ARCH="x64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    *) error "Unsupported architecture: $ARCH"; exit 1 ;;
  esac
  KIRO_URL="https://kiro.dev/downloads/latest/${OS}/${ARCH}/kiro-cli"
  curl -fsSL "$KIRO_URL" -o /usr/local/bin/kiro-cli
  chmod +x /usr/local/bin/kiro-cli
fi
info "kiro-cli $(kiro-cli --version 2>&1 || echo 'installed')"

# --- 3. Set up .kiro agent config ---
mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

KIRO_DIR=".kiro"
mkdir -p "${KIRO_DIR}/agents" "${KIRO_DIR}/prompts" "${KIRO_DIR}/settings" "${KIRO_DIR}/steering"

for f in agents/opensearch-migration.json prompts/start.md settings/hooks.json \
         steering/product.md steering/deployment.md steering/migration-prompt.md steering/workflow.md; do
  gh_raw "kiro-cli/kiro-cli-config/${f}" > "${KIRO_DIR}/${f}"
done

gh_raw "agent-sops/opensearch-migration-assistant-eks.sop.md" \
  > "${KIRO_DIR}/steering/opensearch-migration-assistant-eks.sop.md"

# --- 4. Start kiro-cli ---
# Redirect stdin from /dev/tty so kiro-cli gets the real terminal,
# not the exhausted pipe from `curl | bash`.
exec kiro-cli chat --agent opensearch-migration --trust-all-tools "@start" < /dev/tty
