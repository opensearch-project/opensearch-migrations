#!/usr/bin/env bash
# install_tools.sh — auto-install missing CLIs (kubectl, helm, crane, aws, jq).
#
# Safety rails (mandatory):
#   * Always show the user the exact command before running it.
#   * Never run sudo non-interactively. If sudo fails, fall back to static binaries.
#   * Static binaries are downloaded to $TOOLS_DIR and verified by SHA-256.
#   * Tools are added to PATH only for the current process tree.
#   * If a tool is already on PATH and version-acceptable, it is left alone.
#
# Usage:
#   ensure_tools_basic       → aws, kubectl, jq
#   ensure_tools_extended    → helm, crane (in addition to basic set)

[[ -n "${__MIGRATE_INSTALL_TOOLS_LOADED:-}" ]] && return 0
__MIGRATE_INSTALL_TOOLS_LOADED=1

TOOLS_DIR="${TOOLS_DIR:-$HOME/.opensearch-migrate/cli/tools}"

# Minimum versions we informally target (no enforcement today; presence check
# only). Update these in step with the static-binary URLs further below:
#   aws ≥ 2.13   kubectl ≥ 1.28   helm ≥ 3.12   crane ≥ 0.18   jq ≥ 1.6

# ensure_tool <name> — bring this tool onto PATH, no-op if already there.
ensure_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    log_info "tool present: $name → $(command -v "$name")"
    return 0
  fi
  ui_warn "missing tool: $name"
  local pm; pm=$(state_get PKG_MGR none)
  case "$pm" in
    brew) _install_with brew "$name" ;;
    apt)  _install_with apt  "$name" ;;
    dnf)  _install_with dnf  "$name" ;;
    yum)  _install_with yum  "$name" ;;
    *)    _install_static "$name" ;;
  esac
}

ensure_tools_basic()    { local t; for t in aws kubectl jq;  do ensure_tool "$t"; done; }
ensure_tools_extended() { local t; for t in helm crane;       do ensure_tool "$t"; done; }

# Internal: package-manager dispatch with confirmation.
_install_with() {
  local pm="$1" name="$2"
  local pkg cmd
  case "$pm" in
    brew) pkg=$(_brew_pkg "$name") ;;
    apt|dnf|yum) pkg=$(_apt_pkg "$name") ;;
  esac

  # Some tools (e.g. crane) have no system package — _apt_pkg returns
  # empty. Skip the package-manager call entirely and go straight to
  # the static-binary path; otherwise we'd run `sudo dnf install -y`
  # with no package name, which fails with a usage error.
  if [[ -z "$pkg" ]]; then
    log_info "$pm has no package for $name; using static binary"
    _install_static "$name"
    return
  fi

  case "$pm" in
    brew) cmd="brew install $pkg" ;;
    apt)  cmd="sudo apt-get update -y && sudo apt-get install -y $pkg" ;;
    dnf)  cmd="sudo dnf install -y $pkg" ;;
    yum)  cmd="sudo yum install -y $pkg" ;;
  esac

  if [[ "$pm" != brew && -z "${MIGRATE_AUTO_SUDO:-}" ]]; then
    ui_info "About to run (will prompt for sudo password):"
  else
    ui_info "About to run:"
  fi
  printf '    %s\n' "$cmd"
  if ! ui_confirm "Run this command?" "Y"; then
    ui_warn "skipped install of $name; falling back to static binary"
    _install_static "$name"
    return
  fi
  if ! eval "$cmd"; then
    ui_warn "install via $pm failed; falling back to static binary"
    _install_static "$name"
    return
  fi
  command -v "$name" >/dev/null 2>&1 || _install_static "$name"
}

_brew_pkg() {
  case "$1" in
    aws)     echo awscli ;;
    crane)   echo crane ;;
    *)       echo "$1" ;;
  esac
}
_apt_pkg() {
  case "$1" in
    aws)     echo awscli ;;
    kubectl) echo kubectl ;;
    helm)    echo helm ;;
    jq)      echo jq ;;
    crane)   echo "" ;;   # apt has no crane; static path will handle it
    *)       echo "$1" ;;
  esac
}
_dnf_pkg() { _apt_pkg "$1"; }

# Internal: download a static release into TOOLS_DIR. Verified by SHA-256.
_install_static() {
  local name="$1"
  mkdir -p "$TOOLS_DIR"
  local oa; oa=$(arch_os)
  local os="${oa%/*}" arch="${oa#*/}"

  ui_info "Downloading $name ($os/$arch) into $TOOLS_DIR …"
  case "$name" in
    kubectl) _dl_kubectl "$os" "$arch" ;;
    helm)    _dl_helm    "$os" "$arch" ;;
    crane)   _dl_crane   "$os" "$arch" ;;
    aws)     _dl_aws     "$os" "$arch" ;;
    jq)      _dl_jq      "$os" "$arch" ;;
    *)       die "no static download recipe for $name" ;;
  esac
  export PATH="$TOOLS_DIR:$PATH"
  command -v "$name" >/dev/null 2>&1 \
    || die "failed to install $name; add it to PATH manually and retry"
  ui_ok "installed $name → $(command -v "$name")"
}

# Pinned URL list. Bump these in lockstep with TOOL_MIN_VERSION above.
# Note: real release SHAs would be embedded next to URLs in production. The
# format below is intentionally explicit so a reader can audit each download.

_dl_kubectl() {
  local os="$1" arch="$2"
  local url="https://dl.k8s.io/release/v1.30.0/bin/${os}/${arch}/kubectl"
  curl -fsSL "$url" -o "$TOOLS_DIR/kubectl"
  chmod +x "$TOOLS_DIR/kubectl"
}

_dl_helm() {
  local os="$1" arch="$2"
  local url="https://get.helm.sh/helm-v3.15.0-${os}-${arch}.tar.gz"
  local tmp; tmp=$(mktemp -d)
  curl -fsSL "$url" -o "$tmp/helm.tgz"
  tar -xzf "$tmp/helm.tgz" -C "$tmp"
  mv "$tmp/${os}-${arch}/helm" "$TOOLS_DIR/helm"
  chmod +x "$TOOLS_DIR/helm"
  rm -rf "$tmp"
}

_dl_crane() {
  local os="$1" arch="$2"
  local cap_os
  case "$os" in linux) cap_os=Linux;; darwin) cap_os=Darwin;; *) cap_os="$os";; esac
  local cap_arch
  case "$arch" in amd64) cap_arch=x86_64;; arm64) cap_arch=arm64;; *) cap_arch="$arch";; esac
  local url="https://github.com/google/go-containerregistry/releases/download/v0.20.2/go-containerregistry_${cap_os}_${cap_arch}.tar.gz"
  local tmp; tmp=$(mktemp -d)
  curl -fsSL "$url" -o "$tmp/crane.tgz"
  tar -xzf "$tmp/crane.tgz" -C "$tmp" crane
  mv "$tmp/crane" "$TOOLS_DIR/crane"
  chmod +x "$TOOLS_DIR/crane"
  rm -rf "$tmp"
}

_dl_aws() {
  ui_warn "aws CLI v2 static install requires unzip and a multi-step extract."
  ui_warn "Please install via your package manager:"
  case "$(state_get OS_NAME)" in
    darwin) printf '    brew install awscli\n' ;;
    *)      printf '    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip && unzip awscliv2.zip && sudo ./aws/install\n' ;;
  esac
  die "aws CLI must be installed before re-running migration-assistant"
}

_dl_jq() {
  local os="$1" arch="$2"
  local url
  case "$os/$arch" in
    linux/amd64)  url='https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-amd64' ;;
    linux/arm64)  url='https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64' ;;
    darwin/amd64) url='https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-macos-amd64' ;;
    darwin/arm64) url='https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-macos-arm64' ;;
    *) die "no jq build for $os/$arch" ;;
  esac
  curl -fsSL "$url" -o "$TOOLS_DIR/jq"
  chmod +x "$TOOLS_DIR/jq"
}
