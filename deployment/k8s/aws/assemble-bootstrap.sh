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

# 2. Emit the curl-pipe shim by substituting placeholders in the
# template. The template is the canonical shim source; both this
# script and the Gradle generateAwsBootstrapShim task render it.
TEMPLATE="$SCRIPT_DIR/aws-bootstrap.shim.tmpl"
if [[ ! -f "$TEMPLATE" ]]; then
  printf 'ERROR: %s missing; cannot generate shim\n' "$TEMPLATE" >&2
  exit 1
fi
sed -e "s|@CLI_VERSION@|${CLI_VERSION}|g" \
    -e "s|@DEFAULT_REPO@|${DEFAULT_REPO}|g" \
    "$TEMPLATE" >"$OUTPUT_DIR/aws-bootstrap.sh"
chmod +x "$OUTPUT_DIR/aws-bootstrap.sh"

printf 'wrote %s\n' "$TARBALL"
printf 'wrote %s (curl-pipe shim)\n' "$OUTPUT_DIR/aws-bootstrap.sh"
