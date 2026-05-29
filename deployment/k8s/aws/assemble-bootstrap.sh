#!/usr/bin/env bash
# =============================================================================
# assemble-bootstrap.sh
#
# Produces the release artifacts for the EKS deploy path:
#
#   dist/aws-bootstrap.sh            backwards-compat shim (curl-friendly)
#   dist/migration-assistant-cli-<version>.tar.gz
#                                     full CLI tarball (bin + lib + skills)
#
# The shim is a single self-contained file that downloads + extracts the
# tarball into ~/.opensearch-migrate/cli/<version>/ on first run, then
# execs the unpacked migration-assistant binary. Equivalent UX to the old
# self-contained aws-bootstrap.sh but with the actual logic loaded from
# the tarball — so we only ship 50 lines of curl/tar/exec instead of
# inlining 1700 lines.
#
# Usage:
#   ./assemble-bootstrap.sh [--output-dir <path>] [--cli-version <ver>]
#
# Default: deployment/k8s/aws/dist/
# =============================================================================
set -o errexit
set -o nounset
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_DIR="$SCRIPT_DIR/cli"
OUTPUT_DIR="${SCRIPT_DIR}/dist"

# CLI_VERSION resolution order:
#   1. CLI_VERSION env var / --cli-version flag (release pipelines pin this).
#   2. Repo's git short-SHA, prefixed with "0.0.0-dev-" so each dev/CI
#      build lands in its own cache slot.
#   3. Hardcoded "0.0.0-dev" as a last-resort fallback for non-git checkouts.
if [[ -z "${CLI_VERSION:-}" ]]; then
  if git_sha=$(git -C "$SCRIPT_DIR" rev-parse --short=10 HEAD 2>/dev/null); then
    CLI_VERSION="0.0.0-dev-${git_sha}"
  else
    CLI_VERSION="0.0.0-dev"
  fi
fi

# DEFAULT_REPO baked into the shim. Override at assemble-time with
# DEFAULT_REPO=... ./assemble-bootstrap.sh so a fork preview release
# doesn't require operators to set MIGRATE_REPO at runtime.
DEFAULT_REPO="${DEFAULT_REPO:-opensearch-project/opensearch-migrations}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir)    OUTPUT_DIR="$2";   shift 2 ;;
    --cli-version)   CLI_VERSION="$2";  shift 2 ;;
    --default-repo)  DEFAULT_REPO="$2"; shift 2 ;;
    *) printf 'unknown arg: %s\n' "$1" >&2; exit 64 ;;
  esac
done

mkdir -p "$OUTPUT_DIR"

# 1. Tarball the CLI tree.
#
# Skill content lives in agent-skills/skills/ at the repo root and is
# packaged by the :agent-skills:agentSkillsTar Gradle task. We accept
# the path to that tarball via $AGENT_SKILLS_TAR (set by Gradle) OR
# fall back to copying the source dirs directly when running this
# script standalone (legacy / dev path). Either way the assembled
# tarball ends up with skills/Startup.md + skills/migrating-to-opensearch/
# + skills/aoss-nextgen/ + skills/kiro/.
TARBALL="$OUTPUT_DIR/migration-assistant-cli-${CLI_VERSION}.tar.gz"
stage=$(mktemp -d)
mkdir -p "$stage/migration-assistant-cli-${CLI_VERSION}/skills"
cp -R "$CLI_DIR/bin" "$CLI_DIR/lib" \
      "$CLI_DIR/install.sh" "$CLI_DIR/README.md" \
      "$stage/migration-assistant-cli-${CLI_VERSION}/"

if [[ -n "${AGENT_SKILLS_TAR:-}" && -f "$AGENT_SKILLS_TAR" ]]; then
  # Gradle path: extract the producer artifact directly.
  tar -xzf "$AGENT_SKILLS_TAR" -C "$stage/migration-assistant-cli-${CLI_VERSION}/" \
    --strip-components=0 || {
    printf 'ERROR: could not extract %s\n' "$AGENT_SKILLS_TAR" >&2
    exit 1
  }
else
  # Standalone path: pull from the on-disk agent-skills tree at the
  # repo root.
  AGENT_SKILLS_SRC="$SCRIPT_DIR/../../../agent-skills"
  if [[ ! -d "$AGENT_SKILLS_SRC/skills" ]]; then
    printf 'ERROR: %s/skills not found; run from the repo or set AGENT_SKILLS_TAR\n' \
      "$AGENT_SKILLS_SRC" >&2
    exit 1
  fi
  cp -R "$AGENT_SKILLS_SRC/skills/." \
        "$stage/migration-assistant-cli-${CLI_VERSION}/skills/"
  if [[ -d "$AGENT_SKILLS_SRC/kiro" ]]; then
    cp -R "$AGENT_SKILLS_SRC/kiro" \
          "$stage/migration-assistant-cli-${CLI_VERSION}/skills/kiro"
  fi
fi

MANIFEST_SRC="$SCRIPT_DIR/../charts/aggregates/migrationAssistantWithArgo/scripts/privateEcrManifest.sh"
if [[ -f "$MANIFEST_SRC" ]]; then
  cp "$MANIFEST_SRC" \
     "$stage/migration-assistant-cli-${CLI_VERSION}/skills/privateEcrManifest.sh"
else
  printf 'ERROR: %s missing; cannot bundle image manifest\n' \
    "$MANIFEST_SRC" >&2
  exit 1
fi

# Pull LICENSE from the repo root (Apache-2.0) rather than vendoring a
# per-CLI copy. Hard-fail if absent so the published tarball never lacks
# its license.
LICENSE_SRC="$SCRIPT_DIR/../../../LICENSE"
if [[ -f "$LICENSE_SRC" ]]; then
  cp "$LICENSE_SRC" "$stage/migration-assistant-cli-${CLI_VERSION}/LICENSE"
else
  printf 'ERROR: %s missing; refusing to build a tarball without a LICENSE\n' \
    "$LICENSE_SRC" >&2
  exit 1
fi
tar -czf "$TARBALL" -C "$stage" "migration-assistant-cli-${CLI_VERSION}"
rm -rf "$stage"

# 2. Emit the curl-pipe shim. Layered jobs:
#    a) install: download tarball if not cached, extract to PREFIX
#    b) self-link: symlink ~/.local/bin/migration-assistant → \$BIN so
#       \`migration-assistant\` works after the first run, even if the
#       operator never re-runs the curl
#    c) execute: exec the unpacked CLI with the operator's args
#
# When invoked via curl | bash (no script path on disk), only (a)+(b)
# run if no args were passed (so the curl-pipe acts as a pure
# installer); the operator runs the linked binary on the next line.
#
# When run as a saved file (./aws-bootstrap.sh ARGS or
# ~/.local/bin/migration-assistant ARGS), all three steps run normally.
cat > "$OUTPUT_DIR/aws-bootstrap.sh" <<SHIM
#!/usr/bin/env bash
# Self-contained migration-assistant bootstrap.
# Generated by deployment/k8s/aws/assemble-bootstrap.sh
# CLI version: ${CLI_VERSION}
# Default repo: ${DEFAULT_REPO}
set -o errexit
set -o nounset
set -o pipefail

CLI_VERSION='${CLI_VERSION}'
PREFIX="\${MIGRATE_PREFIX:-\$HOME/.opensearch-migrate/cli/\$CLI_VERSION}"
BIN="\$PREFIX/bin/migration-assistant"
TARBALL_NAME="migration-assistant-cli-\${CLI_VERSION}.tar.gz"

# Tarball resolution order (each can be overridden):
#   1. \$MIGRATE_TARBALL_URL — full URL override.
#   2. sibling tarball next to this shim (CI assemble flow).
#   3. \$MIGRATE_REPO override of the default repo.
#   4. DEFAULT_REPO baked at assemble time.
DEFAULT_REPO='${DEFAULT_REPO}'
REPO="\${MIGRATE_REPO:-\$DEFAULT_REPO}"
TARBALL_URL="\${MIGRATE_TARBALL_URL:-https://github.com/\${REPO}/releases/download/\${CLI_VERSION}/\${TARBALL_NAME}}"

SHIM_DIR=\$(cd "\$(dirname "\${BASH_SOURCE[0]:-\$0}")" && pwd 2>/dev/null) || SHIM_DIR=""
LOCAL_TARBALL="\${SHIM_DIR:+\$SHIM_DIR/\$TARBALL_NAME}"

# Detect "curl … | bash" — no real script file. The shim acts as
# installer-and-help only; the operator runs the linked binary next.
PIPED=0
if [[ -z "\${BASH_SOURCE[0]:-}" ]] || [[ "\${BASH_SOURCE[0]:-}" == "/dev/stdin" ]] \\
   || [[ "\${BASH_SOURCE[0]:-}" == "bash" ]]; then
  PIPED=1
fi

# Refresh the install when:
#   * binary doesn't exist
#   * MIGRATE_REINSTALL=1
#   * sibling tarball is NEWER than the cached binary
need_install=0
if [[ ! -x "\$BIN" ]]; then
  need_install=1
elif [[ "\${MIGRATE_REINSTALL:-0}" == "1" ]]; then
  need_install=1
elif [[ -n "\$LOCAL_TARBALL" && -f "\$LOCAL_TARBALL" && "\$LOCAL_TARBALL" -nt "\$BIN" ]]; then
  need_install=1
fi

if (( need_install )); then
  rm -rf "\$PREFIX"
  mkdir -p "\$PREFIX"
  tmp=\$(mktemp -d)
  if [[ -n "\$LOCAL_TARBALL" && -f "\$LOCAL_TARBALL" ]]; then
    cp "\$LOCAL_TARBALL" "\$tmp/cli.tgz"
  else
    printf 'Downloading migration-assistant %s …\\n' "\$CLI_VERSION" >&2
    curl -fsSL --max-time 120 -o "\$tmp/cli.tgz" "\$TARBALL_URL" \\
      || { echo "could not download \$TARBALL_URL" >&2; rm -rf "\$tmp"; exit 1; }
  fi
  tar -xzf "\$tmp/cli.tgz" -C "\$PREFIX" --strip-components=1
  rm -rf "\$tmp"
  chmod +x "\$BIN"
fi

# Make the CLI runnable by name. ~/.local/bin is the canonical
# user-bin path on macOS + most Linux. We symlink the unpacked binary
# (not this shim) so the symlink still works after \$PREFIX is the
# current install.
LINK_DIR="\${MIGRATE_LINK_DIR:-\$HOME/.local/bin}"
LINK="\$LINK_DIR/migration-assistant"
mkdir -p "\$LINK_DIR"
ln -sfn "\$BIN" "\$LINK"

# Curl-piped, no extra args: print install summary + run-it-now hint,
# then exec the CLI so the operator gets the mode picker without a
# second command. Args (e.g. \`bash -s -- console\`) bypass this.
if (( PIPED )) && [[ \$# -eq 0 ]]; then
  printf '\\n\\033[1m✓ migration-assistant %s installed\\033[0m\\n' "\$CLI_VERSION" >&2
  printf '  bin     : %s\\n' "\$BIN" >&2
  printf '  link    : %s\\n' "\$LINK" >&2
  printf '\\nNext time, just run: \\033[1mmigration-assistant\\033[0m\\n' >&2
  if ! printf '%s' "\$PATH" | tr ':' '\\n' | grep -qxF "\$LINK_DIR"; then
    printf '\\n\\033[33m! \033[0m%s is not on your PATH; add to your shell rc:\\n' "\$LINK_DIR" >&2
    printf '    export PATH="%s:\$PATH"\\n' "\$LINK_DIR" >&2
  fi
  printf '\\nStarting now…\\n\\n' >&2
fi

exec "\$BIN" "\$@"
SHIM
chmod +x "$OUTPUT_DIR/aws-bootstrap.sh"

printf 'wrote %s\n' "$TARBALL"
printf 'wrote %s (curl-pipe shim)\n' "$OUTPUT_DIR/aws-bootstrap.sh"
