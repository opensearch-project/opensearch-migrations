#!/bin/bash
# -----------------------------------------------------------------------------
# Bootstrap Kiro Migration Agent
#
# One-liner: curl -sL https://raw.githubusercontent.com/opensearch-project/opensearch-migrations/kiro-agent/bootstrap-kiro-agent.sh | bash
#
# This script:
#   1. Installs helm (if missing)
#   2. Installs kiro-cli (if missing)
#   3. Sets up .kiro agent configuration
#   4. Starts kiro-cli with the opensearch-migration agent (which handles AWS bootstrap via @start prompt)
# -----------------------------------------------------------------------------

set -euo pipefail

BRANCH="kiro-agent"
ORG="opensearch-project"
REPO="opensearch-migrations"
RAW_BASE="https://raw.githubusercontent.com/${ORG}/${REPO}/${BRANCH}"
WORK_DIR="${WORK_DIR:-./kiro-migration-agent}"

info()  { printf '\033[1;34m[kiro]\033[0m %s\n' "$*"; }
error() { printf '\033[1;31m[kiro]\033[0m %s\n' "$*" >&2; }

# --- 1. Install helm ---
if ! command -v helm &>/dev/null; then
  info "Installing helm..."
  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
fi
info "helm $(helm version --short)"

# --- 2. Install kiro-cli ---
if ! command -v kiro-cli &>/dev/null; then
  info "Installing kiro-cli..."
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
  info "kiro-cli installed to /usr/local/bin/kiro-cli"
fi
info "kiro-cli: $(kiro-cli --version 2>&1 || echo 'installed')"

# --- 3. Set up .kiro agent config ---
info "Setting up .kiro agent configuration..."
mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

KIRO_DIR=".kiro"
mkdir -p "${KIRO_DIR}/agents" "${KIRO_DIR}/prompts" "${KIRO_DIR}/settings" "${KIRO_DIR}/steering"

# Download agent config files
for f in agents/opensearch-migration.json prompts/start.md settings/hooks.json \
         steering/product.md steering/deployment.md steering/migration-prompt.md steering/workflow.md; do
  curl -fsSL "${RAW_BASE}/kiro-cli/kiro-cli-config/${f}" -o "${KIRO_DIR}/${f}"
done

# Download SOP into steering (same as build.gradle setupKiro task)
curl -fsSL "${RAW_BASE}/agent-sops/opensearch-migration-assistant-eks.sop.md" \
  -o "${KIRO_DIR}/steering/opensearch-migration-assistant-eks.sop.md"

info "Agent config ready at ${WORK_DIR}/${KIRO_DIR}"

# --- 4. Start kiro-cli agent ---
info "Starting kiro-cli with opensearch-migration agent..."
exec kiro-cli chat --agent opensearch-migration --trust-all-tools "@start"
