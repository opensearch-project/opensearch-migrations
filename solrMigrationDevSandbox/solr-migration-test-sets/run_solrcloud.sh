#!/usr/bin/env bash
#
# run_solrcloud.sh — automate the SolrCloud-mode steps in steps_solrcloud.md
#
# For each requested Solr version (6, 7, 8, 9) this script:
#   1. starts a 3-node SolrCloud cluster via docker-compose-<v>.yml
#   2. creates the `nyc_taxis` collection (3 shards, RF1) from ./solr_configsets/nyc_taxis_<v>/conf
#   3. runs solr-orbit to load data
#   4. backs up the collection via the Collections API into ./backups/solrcloud/<name>
#      (SolrCloud BACKUP already writes the layout Migration Assistant expects — no
#      manual reshape needed, unlike the standalone path)
#   5. (v9) also produces a single-segment (optimized) backup
#
# Before running the versions it (optionally) clones + installs solr-orbit and
# solr-orbit-workloads, pointing solr-orbit at the local workloads clone.
#
# Optionally syncs the backups up to S3 (step 3 of the doc).
#
# Step 4 (Migration Assistant) is interactive (kubectl exec into the console against a
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

# solr-orbit source. The fork is a superset of upstream apache/solr-orbit (it just
# adds --allow-user-managed, unused here) and is what run_standalone.sh installs, so
# reuse it to share the same checkout/venv.
ORBIT_REPO="https://github.com/epugh/solr-orbit"
ORBIT_BRANCH="allow_standalone_option"
WORKLOADS_REPO="https://github.com/apache/solr-orbit-workloads"

BACKUP_BASE="./backups/solrcloud"
# Host ports for the three cluster nodes (see docker-compose-<v>.yml).
CLUSTER_PORTS=(8983 8984 8985)
SOLR_PORT=8983                       # node1 — used for Collections API / orbit target
SOLR_URL="http://localhost:${SOLR_PORT}"
COMPOSE=(docker compose)

usage() {
    cat <<'EOF'
Usage: ./run_solrcloud.sh [options]

Options:
  --versions=LIST     Comma-delimited Solr versions to build (default: 6,7,8,9)
                      e.g. --versions=8,9
  --test-mode         Pass --test-mode through to solr-orbit (smaller/faster load)
  --skip-install      Don't clone/install solr-orbit + workloads (assumes already set up)
  --tools-dir=DIR     Where to clone the solr-orbit repos (default: $HOME/perf)
  --s3-upload         Sync the resulting backups to S3 (step 3)
  --aws-account=NUM   AWS account number for the S3 bucket (required with --s3-upload)
  --aws-cli=CMD       AWS CLI command to use for S3 sync (default: aws; the doc uses
                      aws-cli.aws). Overrides the AWS_CLI env var.
  --chown-backups     sudo chown ./backups to uid 8983 (Linux hosts; not needed on
                      Docker Desktop / macOS). Prompts for your password.
  -h, --help          Show this help

Environment:
  PY_VERSION=3.12     Python version uv installs/uses for the venv (default: 3.12).
                      Requires uv (https://astral.sh/uv) on PATH.

Examples:
  ./run_solrcloud.sh --versions=9 --test-mode
  ./run_solrcloud.sh --versions=7,8,9
  ./run_solrcloud.sh --skip-install --versions=8 --s3-upload --aws-account=371015648411
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
# Prereqs (step 1)
# ---------------------------------------------------------------------------
prereqs() {
    log "Prereqs: creating ${BACKUP_BASE}"
    mkdir -p "${BACKUP_BASE}"
    # Solr in the containers runs as uid 8983 and needs to write into /backups.
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
# Cluster lifecycle helpers
# ---------------------------------------------------------------------------
compose_up() {
    local version="$1" file="docker-compose-${version}.yml"
    [[ -f "$file" ]] || die "compose file $file not found"

    # solr:6.6.6 is published amd64-only, so force emulation on Apple Silicon via
    # DOCKER_DEFAULT_PLATFORM (a no-op on a native amd64 host).
    if [[ "$version" == "6" ]]; then
        log "Starting Solr ${version} cluster (${file}) [linux/amd64]"
        DOCKER_DEFAULT_PLATFORM=linux/amd64 "${COMPOSE[@]}" -f "$file" up -d --wait \
            || warn "compose --wait did not report healthy; polling cluster readiness"
    else
        log "Starting Solr ${version} cluster (${file})"
        "${COMPOSE[@]}" -f "$file" up -d --wait \
            || warn "compose --wait did not report healthy; polling cluster readiness"
    fi
    wait_for_cluster
}

compose_down() {
    local version="$1" file="docker-compose-${version}.yml"
    log "Shutting down Solr ${version} cluster"
    "${COMPOSE[@]}" -f "$file" down || warn "compose down failed for $file"
}

wait_for_cluster() {
    # Up to ~5 min: emulated (amd64-on-arm64) Solr 6 can be slow to start under qemu.
    log "Waiting for all ${#CLUSTER_PORTS[@]} cluster nodes to come up..."
    local _ p up
    for _ in $(seq 1 150); do
        up=1
        for p in "${CLUSTER_PORTS[@]}"; do
            curl -fsS "http://localhost:${p}/solr/admin/info/system?wt=json" >/dev/null 2>&1 || up=0
        done
        if [[ "$up" -eq 1 ]]; then echo "All nodes are up."; return 0; fi
        sleep 2
    done
    die "Solr cluster did not become ready in time"
}

create_collection() {
    local version="$1" cfg="nyc_taxis_${version}"
    # Delete any pre-existing nyc_taxis collection first so re-runs start clean
    # (create_collection fails if the collection already exists). Ignore errors —
    # on a fresh cluster there's nothing to delete.
    log "Deleting existing collection nyc_taxis (if present)"
    curl -fsS "${SOLR_URL}/solr/admin/collections?action=DELETE&name=nyc_taxis&wt=json" >/dev/null 2>&1 \
        || warn "no existing nyc_taxis collection to delete (or DELETE failed)"
    log "Creating collection nyc_taxis from ./solr_configsets/${cfg}/conf (3 shards, RF1)"
    docker cp "./solr_configsets/${cfg}/conf" "solr-node1:/tmp/${cfg}"
    docker exec solr-node1 solr create_collection \
        -c nyc_taxis \
        -d "/tmp/${cfg}" \
        -shards 3 \
        -replicationFactor 1 \
        -p 8983
}

run_orbit() {
    log "Running solr-orbit to load data (workload=${WORKLOAD})"
    local args=(
        run
        --pipeline=benchmark-only
        --target-host="localhost:${SOLR_PORT}"
        --kill-running-processes
        --workload="${WORKLOAD}"
        --include-tasks="check-cluster-health,index"
    )
    if [[ "$TEST_MODE" -eq 1 ]]; then
        args+=(--test-mode)
    fi
    solr-orbit "${args[@]}"
}

# Back up the collection via the Collections API. SolrCloud BACKUP is synchronous
# by default and writes <location>/<name>/ in the MA-expected layout.
backup_collection() {
    local name="$1"
    # Hard-commit first: BACKUP only captures committed segments, and solr-orbit's
    # load may leave docs uncommitted (otherwise the backup is empty).
    curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?commit=true" >/dev/null
    # Start fresh: Solr 6/7 refuse to back up into an existing directory (HTTP 400),
    # and Solr 8/9 would otherwise pile up extra incremental revisions. Delete from
    # *inside* the container so Solr's view of the bind mount is immediately consistent
    # — a host-side rm can race the mount and make Solr 500 mid-backup.
    docker exec solr-node1 rm -rf "/backups/solrcloud/${name}" 2>/dev/null || true
    log "Backing up collection -> ${BACKUP_BASE}/${name}"
    local resp
    resp="$(curl -fsS "${SOLR_URL}/solr/admin/collections?action=BACKUP&name=${name}&collection=nyc_taxis&location=/backups/solrcloud&wt=json")" \
        || die "BACKUP request failed for ${name}"
    if echo "$resp" | grep -qiE '"(exception|error)"'; then
        warn "BACKUP for ${name} reported an error: ${resp}"
    fi
    # The BACKUP response can return before files are flushed to the shared mount,
    # so wait for the backup properties file (written last on a successful backup).
    # Solr 6/7 (non-incremental) write `backup.properties`; Solr 8/9 (incremental)
    # write `backup_<N>.properties` one level deeper — match either.
    local _
    for _ in $(seq 1 60); do
        if find "${BACKUP_BASE}/${name}" -name 'backup*.properties' 2>/dev/null | grep -q .; then
            echo "Backup '${name}' present ($(find "${BACKUP_BASE}/${name}" -type f | wc -l | tr -d ' ') files)."
            return 0
        fi
        sleep 2
    done
    warn "backup ${BACKUP_BASE}/${name} did not produce a backup properties file in time"
}

# ---------------------------------------------------------------------------
# Per-version driver
# ---------------------------------------------------------------------------
run_version() {
    local version="$1"
    log "######## Solr ${version} ########"

    compose_up "$version"
    create_collection "$version"
    run_orbit
    backup_collection "nyc_taxis_${version}"

    if [[ "$version" == "9" ]]; then
        # v9: also produce a single-segment (optimized) backup.
        log "Optimizing to a single segment"
        curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?optimize=true&maxSegments=1" >/dev/null
        backup_collection "nyc_taxis_9_onesegment"
    fi

    compose_down "$version"
}

# ---------------------------------------------------------------------------
# S3 upload (step 3)
# ---------------------------------------------------------------------------
# The backup directory names a given version produces under ${BACKUP_BASE}.
version_backups() {
    local v="$1"
    echo "nyc_taxis_${v}"
    case "$v" in
        9) echo "nyc_taxis_9_onesegment" ;;  # v9 single-segment backup
    esac
}

# Sync only the backups produced by the requested --versions (not everything
# that happens to be sitting under ${BACKUP_BASE}).
s3_upload() {
    local s3_base="s3://migrations-default-${AWS_ACCOUNT}-dev-us-east-1/solr-migration-test-sets/solrcloud"
    log "Syncing backups for versions ${VERSIONS} to ${s3_base}"
    local v name snap
    for v in "${VERSION_LIST[@]}"; do
        v="$(echo "$v" | tr -d '[:space:]')"
        [[ -n "$v" ]] || continue
        for name in $(version_backups "$v"); do
            snap="${BACKUP_BASE}/${name}"
            if [[ ! -d "$snap" ]]; then
                warn "expected backup ${snap} not found; skipping"
                continue
            fi
            echo "  $snap -> ${s3_base}/${name}"
            # --delete: mirror exactly, removing stale objects (e.g. older backup
            # revisions) already in S3 that are no longer in the local backup dir.
            "$AWS_CLI" s3 sync --delete "$snap" "${s3_base}/${name}"
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

    log "Done. Backups are under ${BACKUP_BASE}/"
    log "Step 4 (Migration Assistant) is interactive — see steps_solrcloud.md."
}

main
