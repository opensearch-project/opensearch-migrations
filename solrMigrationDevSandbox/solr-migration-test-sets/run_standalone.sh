#!/usr/bin/env bash
#
# run_standalone.sh — automate the standalone-mode steps in steps_standalone.md
#
# For each requested Solr version (6, 7, 8, 9) this script:
#   1. starts a single standalone Solr node in Docker
#   2. creates the `nyc_taxis` core from ./solr_configsets/nyc_taxis_<v>
#   3. runs solr-orbit to load data
#   4. backs up the core via the replication handler into ./backups/standalone
#   5. (v7, v8) reshapes the snapshot into the layout MA expects + stores the schema
#   6. (v9) also produces a single-segment (optimized) backup
#
# Before running the versions it (optionally) clones + installs apache/solr-orbit and
# apache/solr-orbit-workloads, pointing solr-orbit at the local workloads clone.
#
# Optionally syncs the snapshots up to S3 (step 6 of the doc).
#
# Step 7 (Migration Assistant) is interactive (kubectl exec into the console against a
# live target cluster) and is intentionally NOT automated here.

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults / config
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSIONS="6,7,8,9"          # which Solr versions to build (comma-delimited)
WORKLOAD="nyc_taxis"
TEST_MODE=0                 # set by --test-mode; passes --test-mode to solr-orbit
SKIP_INSTALL=0              # skip cloning/installing solr-orbit + workloads
TOOLS_DIR="${SOLR_ORBIT_TOOLS_DIR:-$HOME/perf}"   # where the repos get cloned
PY_VERSION="${PY_VERSION:-3.12}"   # Python version uv installs/uses for the venv
S3_UPLOAD=0
AWS_ACCOUNT=""              # required if --s3-upload is used
CHOWN_BACKUPS=0             # set by --chown-backups; sudo-chown backups for Linux hosts
AWS_CLI="${AWS_CLI:-aws}"   # the doc uses `aws-cli.aws`; override via AWS_CLI=

# Fork + branch that adds --allow-user-managed so solr-orbit will run against a
# standalone (user-managed, non-cloud) Solr node. Upstream apache/solr-orbit only
# supports SolrCloud mode.
ORBIT_REPO="https://github.com/epugh/solr-orbit"
ORBIT_BRANCH="allow_standalone_option"
WORKLOADS_REPO="https://github.com/apache/solr-orbit-workloads"

BACKUP_BASE="./backups/standalone"
CONTAINER="solr-node1"
SOLR_PORT=8983
SOLR_URL="http://localhost:${SOLR_PORT}"

usage() {
    cat <<'EOF'
Usage: ./run_standalone.sh [options]

Options:
  --versions=LIST     Comma-delimited Solr versions to build (default: 6,7,8,9)
                      e.g. --versions=8,9
  --test-mode         Pass --test-mode through to solr-orbit (smaller/faster load)
  --skip-install      Don't clone/install solr-orbit + workloads (assumes already set up)
  --tools-dir=DIR     Where to clone the solr-orbit repos (default: $HOME/perf)

Environment:
  PY_VERSION=3.12     Python version uv installs/uses for the venv (default: 3.12).
                      Requires uv (https://astral.sh/uv) on PATH.
  --s3-upload         Sync the resulting snapshots to S3 (step 6)
  --aws-account=NUM   AWS account number for the S3 bucket (required with --s3-upload)
  --aws-cli=CMD       AWS CLI command to use for S3 sync (default: aws; the doc uses
                      aws-cli.aws). Overrides the AWS_CLI env var.
  --chown-backups     sudo chown ./backups to uid 8983 (Linux hosts; not needed on
                      Docker Desktop / macOS). Prompts for your password.
  -h, --help          Show this help

Examples:
  ./run_standalone.sh --versions=9 --test-mode
  ./run_standalone.sh --versions=7,8,9
  ./run_standalone.sh --skip-install --versions=8 --s3-upload --aws-account=371015648411
EOF
}

# ---------------------------------------------------------------------------
# Arg parsing
# ---------------------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --versions=*)    VERSIONS="${arg#*=}" ;;
        --test-mode)     TEST_MODE=1 ;;
        --skip-install)  SKIP_INSTALL=1 ;;
        --tools-dir=*)   TOOLS_DIR="${arg#*=}" ;;
        --s3-upload)     S3_UPLOAD=1 ;;
        --aws-account=*) AWS_ACCOUNT="${arg#*=}" ;;
        --aws-cli=*)     AWS_CLI="${arg#*=}" ;;
        --chown-backups) CHOWN_BACKUPS=1 ;;
        -h|--help)       usage; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; usage; exit 1 ;;
    esac
done

if [[ "$S3_UPLOAD" -eq 1 && -z "$AWS_ACCOUNT" ]]; then
    echo "ERROR: --s3-upload requires --aws-account=<number>" >&2
    exit 1
fi

cd "$SCRIPT_DIR"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Per-version configuration (mirrors steps_standalone.md)
# ---------------------------------------------------------------------------
solr_image() {
    case "$1" in
        6) echo "solr:6.6.6" ;;
        7) echo "solr:7.7.3" ;;
        8) echo "solr:8.11.2" ;;
        9) echo "solr:9.10" ;;
        *) die "Unsupported Solr version: $1 (supported: 6,7,8,9)" ;;
    esac
}

# Extra `docker run` flags per version (path allow-listing / jetty host binding).
solr_docker_extra() {
    case "$1" in
        8) echo "-e SOLR_OPTS=-Dsolr.allowPaths=/backups" ;;
        9) echo "-e SOLR_JETTY_HOST=0.0.0.0 -e SOLR_OPTS=-Dsolr.allowPaths=/backups" ;;
        *) echo "" ;;
    esac
}

# Platform override per version. solr:6.6.6 is published amd64-only, so on Apple
# Silicon it must run under emulation; the flag is a no-op on a native amd64 host.
solr_docker_platform() {
    case "$1" in
        6) echo "--platform linux/amd64" ;;
        *) echo "" ;;
    esac
}

# ---------------------------------------------------------------------------
# Prereqs (step 1)
# ---------------------------------------------------------------------------
prereqs() {
    log "Prereqs: creating ${BACKUP_BASE}"
    mkdir -p "${BACKUP_BASE}"
    # Solr in the container runs as uid 8983 and needs to write into /backups.
    # On Docker Desktop (macOS) the file-sharing layer handles this, so we only
    # attempt a plain chown (no sudo). Pass --chown-backups on a Linux host where
    # the bind mount needs the uid set explicitly.
    if [[ "$CHOWN_BACKUPS" -eq 1 ]]; then
        sudo chown -R 8983:8983 backups \
            || warn "could not chown backups to 8983:8983"
    else
        chown -R 8983:8983 backups 2>/dev/null || true
    fi
}

# ---------------------------------------------------------------------------
# Clone + install solr-orbit and solr-orbit-workloads
# ---------------------------------------------------------------------------
ORBIT_DIR=""
VENV_ACTIVE=0

# clone_or_update <url> <dest> [branch]
clone_or_update() {
    local url="$1" dest="$2" branch="${3:-}"
    if [[ -d "$dest/.git" ]]; then
        log "Updating $(basename "$dest") ($dest)"
        # Make sure the existing checkout tracks the requested remote/branch.
        git -C "$dest" remote set-url origin "$url"
        git -C "$dest" fetch origin
        if [[ -n "$branch" ]]; then
            git -C "$dest" checkout "$branch"
            git -C "$dest" pull --ff-only origin "$branch" \
                || warn "git pull failed for $dest; using existing checkout"
        else
            git -C "$dest" pull --ff-only || warn "git pull failed for $dest; using existing checkout"
        fi
    else
        log "Cloning $url${branch:+ (branch $branch)} -> $dest"
        if [[ -n "$branch" ]]; then
            git clone -b "$branch" "$url" "$dest"
        else
            git clone "$url" "$dest"
        fi
    fi
}

install_orbit() {
    mkdir -p "$TOOLS_DIR"
    ORBIT_DIR="$TOOLS_DIR/solr-orbit"
    local workloads_dir="$TOOLS_DIR/solr-orbit-workloads"

    clone_or_update "$ORBIT_REPO" "$ORBIT_DIR" "$ORBIT_BRANCH"
    clone_or_update "$WORKLOADS_REPO" "$workloads_dir"

    command -v uv >/dev/null 2>&1 \
        || die "uv not found. Install it first: https://docs.astral.sh/uv/ (e.g. 'curl -LsSf https://astral.sh/uv/install.sh | sh')"
    log "Installing solr-orbit into a uv virtualenv (Python ${PY_VERSION})"
    uv python install "${PY_VERSION}"
    uv venv --python "${PY_VERSION}" --clear "$ORBIT_DIR/.venv"
    # shellcheck disable=SC1091
    source "$ORBIT_DIR/.venv/bin/activate"
    VENV_ACTIVE=1
    uv pip install -e "$ORBIT_DIR"

    # Generate ~/.solr-orbit/benchmark.ini from solr-orbit's own template (so the
    # config.version stays in sync with the installed code), then point the
    # workloads repo at our local clone so runs are offline/reproducible.
    local confdir="$HOME/.solr-orbit"
    local ini="$confdir/benchmark.ini"
    local template="$ORBIT_DIR/solrorbit/resources/benchmark.ini"
    log "Generating $ini from template (workloads -> local clone)"
    mkdir -p "$confdir"
    [[ -f "$template" ]] || die "solr-orbit config template not found at $template"
    if [[ -f "$ini" ]]; then
        cp "$ini" "$ini.bak.$$" && warn "backed up existing config to $ini.bak.$$"
    fi
    # Substitute ${CONFIG_DIR} and replace the default workloads URL with the local clone.
    CONFDIR="$confdir" WL="$workloads_dir" python3 - "$template" "$ini" <<'PY'
import os, re, sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src).read().replace("${CONFIG_DIR}", os.environ["CONFDIR"])
text = re.sub(r'(?m)^default\.url\s*=.*$', "default.url = " + os.environ["WL"], text, count=1)
open(dst, "w").write(text)
PY

    solr-orbit --version || warn "solr-orbit --version failed"
}

# Ensure the solr-orbit venv is active (used when --skip-install was passed).
ensure_orbit() {
    if command -v solr-orbit >/dev/null 2>&1; then
        return
    fi
    local candidate="$TOOLS_DIR/solr-orbit/.venv/bin/activate"
    if [[ -f "$candidate" ]]; then
        # shellcheck disable=SC1090
        source "$candidate"
        VENV_ACTIVE=1
    fi
    command -v solr-orbit >/dev/null 2>&1 \
        || die "solr-orbit not found. Run without --skip-install, or activate its venv first."
}

# ---------------------------------------------------------------------------
# Solr lifecycle helpers
# ---------------------------------------------------------------------------
start_solr() {
    local version="$1"
    local image extra platform
    image="$(solr_image "$version")"
    extra="$(solr_docker_extra "$version")"
    platform="$(solr_docker_platform "$version")"

    log "Starting standalone Solr ${version} (${image})${platform:+ [$platform]}"
    docker rm -f "$CONTAINER" 2>/dev/null || true
    # shellcheck disable=SC2086
    docker run -d \
        --name "$CONTAINER" \
        $platform \
        -p "${SOLR_PORT}:8983" \
        --memory 16g \
        -e SOLR_HEAP=8g \
        $extra \
        -v "$(pwd)/backups:/backups" \
        "$image"

    wait_for_solr
}

wait_for_solr() {
    # Up to ~5 min: emulated (amd64-on-arm64) Solr 6 can be slow to start under qemu.
    log "Waiting for Solr to come up..."
    for _ in $(seq 1 150); do
        if curl -fsS "${SOLR_URL}/solr/admin/info/system?wt=json" >/dev/null 2>&1; then
            echo "Solr is up."
            return 0
        fi
        sleep 2
    done
    die "Solr did not become ready in time"
}

create_core() {
    local version="$1"
    local cfg="nyc_taxis_${version}"
    log "Creating core nyc_taxis from ./solr_configsets/${cfg}"
    docker cp "./solr_configsets/${cfg}" "${CONTAINER}:/tmp/${cfg}"
    docker exec "$CONTAINER" solr create_core -c nyc_taxis -d "/tmp/${cfg}"
}

run_orbit() {
    log "Running solr-orbit to load data (workload=${WORKLOAD})"
    local args=(
        run
        --pipeline=benchmark-only
        --target-host="localhost:${SOLR_PORT}"
        --kill-running-processes
        --allow-user-managed
        --workload="${WORKLOAD}"
        --include-tasks="check-cluster-health,index"
    )
    if [[ "$TEST_MODE" -eq 1 ]]; then
        args+=(--test-mode)
    fi
    solr-orbit "${args[@]}"
}

# Trigger a replication-handler backup and wait for it to finish (it's async).
backup_core() {
    local name="$1"
    # Hard-commit first: the replication handler only snapshots committed segments,
    # and solr-orbit's load may leave docs uncommitted (otherwise the backup is empty).
    curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?commit=true" >/dev/null
    # Start fresh so re-runs are a clean overwrite (and the v7/v8 reshape isn't
    # re-applied on top of an already-reshaped snapshot). Delete from *inside* the
    # container so Solr's view of the bind mount is immediately consistent.
    docker exec "$CONTAINER" rm -rf "/backups/standalone/snapshot.${name}" 2>/dev/null || true
    log "Backing up core -> ${BACKUP_BASE}/snapshot.${name}"
    curl -fsS "${SOLR_URL}/solr/nyc_taxis/replication?command=backup&location=/backups/standalone&name=${name}&wt=json" >/dev/null
    wait_for_backup "$name"
}

wait_for_backup() {
    local name="$1"
    echo "Waiting for backup '${name}' to complete..."
    for _ in $(seq 1 120); do
        local details
        details="$(curl -fsS "${SOLR_URL}/solr/nyc_taxis/replication?command=details&wt=json" 2>/dev/null || true)"
        if echo "$details" | grep -q '"status":"success"' \
           && echo "$details" | grep -q "snapshot.${name}"; then
            echo "Backup '${name}' complete."
            return 0
        fi
        # Fallback: snapshot dir exists and contains a segments file.
        if compgen -G "${BACKUP_BASE}/snapshot.${name}/segments_*" >/dev/null 2>&1; then
            echo "Backup '${name}' complete (segments present)."
            return 0
        fi
        sleep 2
    done
    warn "Timed out waiting for backup '${name}'; continuing anyway"
}

stop_solr() {
    log "Shutting down Solr node"
    docker rm -f "$CONTAINER" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Snapshot reshaping (v7, v8) — the layout Migration Assistant expects
# ---------------------------------------------------------------------------
reshape_snapshot() {
    local version="$1"
    local dir="${BACKUP_BASE}/snapshot.nyc_taxis_${version}"

    log "Reshaping snapshot ${dir} into the MA layout"
    mkdir -p "$dir/nyc_taxis/index"
    # Per-file `mv {} dest/` is portable; GNU's `mv -t` is not available on BSD/macOS.
    find "$dir" -maxdepth 1 -mindepth 1 \
        ! -name nyc_taxis ! -name shard_backup_metadata \
        -exec mv {} "$dir/nyc_taxis/index/" \;

    log "Generating shard_backup_metadata manifest"
    mkdir -p "$dir/nyc_taxis/shard_backup_metadata"
    local out="$dir/nyc_taxis/shard_backup_metadata/md_shard1_0.json"
    printf '{' > "$out"
    local first=true f
    for f in $(ls "$dir/nyc_taxis/index"); do
        if [[ "$first" == true ]]; then first=false; else printf ',' >> "$out"; fi
        printf '"%s":{"fileName":"%s"}' "$f" "$f" >> "$out"
    done
    printf '}' >> "$out"

    log "Storing schema for version ${version}"
    mkdir -p "$dir/nyc_taxis/zk_backup_0/configs/nyc_taxies"
    cp "./solr_configsets/nyc_taxis_${version}/conf/schema.xml" \
        "$dir/nyc_taxis/zk_backup_0/configs/nyc_taxies/managed-schema"
}

# ---------------------------------------------------------------------------
# Per-version driver
# ---------------------------------------------------------------------------
run_version() {
    local version="$1"
    log "######## Solr ${version} ########"

    start_solr "$version"
    create_core "$version"
    run_orbit
    backup_core "nyc_taxis_${version}"

    if [[ "$version" == "9" ]]; then
        # v9: also produce a single-segment (optimized) backup.
        log "Optimizing to a single segment"
        curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?optimize=true&maxSegments=1" >/dev/null
        backup_core "nyc_taxis_9_onesegment"
    fi

    stop_solr

    if [[ "$version" == "7" || "$version" == "8" ]]; then
        reshape_snapshot "$version"
    fi
}

# ---------------------------------------------------------------------------
# S3 upload (step 6)
# ---------------------------------------------------------------------------
# The snapshot directory names a given version produces under ${BACKUP_BASE}.
version_snapshots() {
    local v="$1"
    echo "snapshot.nyc_taxis_${v}"
    case "$v" in
        9) echo "snapshot.nyc_taxis_9_onesegment" ;;  # v9 single-segment backup
    esac
}

# Sync only the snapshots produced by the requested --versions (not everything
# that happens to be sitting under ${BACKUP_BASE}).
s3_upload() {
    local s3_base="s3://migrations-default-${AWS_ACCOUNT}-dev-us-east-1/solr-migration-test-sets/standalone"
    log "Syncing snapshots for versions ${VERSIONS} to ${s3_base}"
    local v name snap
    for v in "${VERSION_LIST[@]}"; do
        v="$(echo "$v" | tr -d '[:space:]')"
        [[ -n "$v" ]] || continue
        for name in $(version_snapshots "$v"); do
            snap="${BACKUP_BASE}/${name}"
            if [[ ! -d "$snap" ]]; then
                warn "expected snapshot ${snap} not found; skipping"
                continue
            fi
            echo "  $snap -> ${s3_base}/${name}"
            "$AWS_CLI" s3 sync "$snap" "${s3_base}/${name}"
        done
    done
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    IFS=',' read -ra VERSION_LIST <<< "$VERSIONS"

    prereqs

    if [[ "$SKIP_INSTALL" -eq 1 ]]; then
        ensure_orbit
    else
        install_orbit
    fi

    for v in "${VERSION_LIST[@]}"; do
        v="$(echo "$v" | tr -d '[:space:]')"
        [[ -n "$v" ]] || continue
        run_version "$v"
    done

    if [[ "$S3_UPLOAD" -eq 1 ]]; then
        s3_upload
    fi

    log "Done. Snapshots are under ${BACKUP_BASE}/"
    log "Step 7 (Migration Assistant) is interactive — see steps_standalone.md."
}

main
