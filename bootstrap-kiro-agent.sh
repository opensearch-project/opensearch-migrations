#!/bin/bash
# =============================================================================
# Bootstrap Kiro Migration Agent
#
# One-liner (downloads from latest GitHub release):
#   curl -sL https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/bootstrap-kiro-agent.sh | bash
#
# With a specific version:
#   curl -sL https://github.com/opensearch-project/opensearch-migrations/releases/download/2.9.0/bootstrap-kiro-agent.sh | bash -s -- --version 2.9.0
#
# Developer mode (from repo checkout):
#   ./bootstrap-kiro-agent.sh --build
#
# This script:
#   1. Installs helm (if missing)
#   2. Installs kiro-cli (if missing)
#   3. Downloads kiro-assistant.tar.gz from GitHub release OR builds from source
#   4. Extracts the .kiro agent configuration
#   5. Starts kiro-cli with the opensearch-migration agent
# =============================================================================

set -euo pipefail

# --- defaults ---
version=""
build=false

# --- argument parsing ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) version="$2"; shift 2 ;;
    --build) build=true; shift 1 ;;
    -h|--help)
      echo "Usage: bootstrap-kiro-agent.sh [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --version <tag>   Release version (default: latest)"
      echo "  --build           Build kiro-assistant.tar.gz from local repo checkout"
      echo "                    instead of downloading from GitHub release."
      echo "  -h, --help        Show this help message"
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

info()  { printf '\033[1;34m[kiro]\033[0m %s\n' "$*"; }
error() { printf '\033[1;31m[kiro]\033[0m %s\n' "$*" >&2; }

# --- 1. Install helm (if missing) ---
if ! command -v helm &>/dev/null; then
  info "Installing helm..."
  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash &>/dev/null
fi
info "helm $(helm version --short)"

# --- 2. Install kiro-cli (if missing) ---
if ! command -v kiro-cli &>/dev/null; then
  ARCH=$(uname -m)
  OS=$(uname -s | tr '[:upper:]' '[:lower:]')
  case "$ARCH" in
    x86_64|amd64) ARCH="x64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    *) error "Unsupported architecture: $ARCH"; exit 1 ;;
  esac
  KIRO_URL="https://kiro.dev/downloads/latest/${OS}/${ARCH}/kiro-cli"
  info "Installing kiro-cli from ${KIRO_URL}..."
  sudo curl -fsSL "$KIRO_URL" -o /usr/local/bin/kiro-cli
  sudo chmod +x /usr/local/bin/kiro-cli
fi
info "kiro-cli $(kiro-cli --version 2>&1 || echo 'installed')"

# --- 3. Get kiro-assistant.tar.gz ---
if [[ "$build" == "true" ]]; then
  # --- build from local repo checkout ---
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if [[ ! -f "$SCRIPT_DIR/kiro-cli/build.gradle" ]]; then
    error "--build requires running from the repo root (expected kiro-cli/build.gradle)."
    exit 1
  fi
  info "Building kiro-assistant.tar.gz from source..."
  "$SCRIPT_DIR/gradlew" :kiro-cli:packageKiro
  TARBALL="$SCRIPT_DIR/kiro-cli/build/kiro-assistant.tar.gz"
else
  # --- download from GitHub release ---
  if [[ -z "$version" || "$version" == "latest" ]]; then
    RELEASE_VERSION=$(curl -sf https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest \
      | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
    RELEASE_VERSION=$(echo "$RELEASE_VERSION" | tr -d '[:space:]')
    [[ -n "$RELEASE_VERSION" ]] || { error "Could not determine latest release version from GitHub."; exit 1; }
    info "Resolved latest release version: $RELEASE_VERSION"
  else
    RELEASE_VERSION="$version"
    info "Using specified version: $RELEASE_VERSION"
  fi
  TARBALL_URL="https://github.com/opensearch-project/opensearch-migrations/releases/download/${RELEASE_VERSION}/kiro-assistant.tar.gz"
  TARBALL=$(mktemp)
  info "Downloading agent config from ${TARBALL_URL}..."
  curl -fsSL "$TARBALL_URL" -o "$TARBALL" \
    || { error "Failed to download kiro-assistant.tar.gz for version ${RELEASE_VERSION}"; rm -f "$TARBALL"; exit 1; }
fi

# --- 4. Extract agent configuration ---
WORK_DIR="${WORK_DIR:-./kiro-migration-agent}"
mkdir -p "$WORK_DIR"
tar -xzf "$TARBALL" -C "$WORK_DIR"
info "Agent config extracted to ${WORK_DIR}/.kiro"

# --- 5. Start kiro-cli ---
cd "$WORK_DIR"
# Redirect stdin from /dev/tty — in `curl | bash`, stdin is EOF after the
# script is fully read. kiro-cli needs a live terminal for interactive input.
info "Starting migration assistant..."

# kiro-cli v2+ defaults to a TUI that spawns ACP mode internally, which doesn't
# work well in curl|bash. Use --legacy-ui for v2+ to get a direct interactive chat.
KIRO_VERSION=$(kiro-cli --version 2>&1 | sed 's/[^0-9]*\([0-9]*\).*/\1/' || echo "0")
if [[ "$KIRO_VERSION" -ge 2 ]]; then
  kiro-cli chat --legacy-ui --agent opensearch-migration "@start" </dev/tty
else
  kiro-cli chat --agent opensearch-migration "@start" </dev/tty
fi
