#!/usr/bin/env bash
#
# run_standalone.sh — automate the standalone-mode steps in steps_standalone.md
#
# For each requested Solr version (6, 7, 8, 9) this script:
#   1. starts a single standalone Solr node in Docker
#   2. creates the `nyc_taxis` core from ./solr_configsets/nyc_taxis_<v>
#   3. runs solr-orbit to load data
#   4. backs up the core via the replication handler into ./backups/standalone
#      (raw Solr output; no post-processing)
#   5. (v9) also produces a single-segment (optimized) backup
#
# --increments=N repeats steps 3-4 N times (default 1): N index+backup cycles
# against the same core. Each cycle re-runs the backup to the same snapshot name,
# so the retained snapshot reflects the cumulative state after the final cycle.
#
# When --s3-upload is used, each snapshot is copied from ./backups/standalone/ into
# ./s3_upload/standalone/, where v7/v8/v9 snapshots are reshaped into the layout MA
# expects before syncing to S3.
#
# Step 7 (Migration Assistant) is interactive and is intentionally NOT automated here.

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults / config
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSIONS="6,7,8,9"
WORKLOAD="nyc_taxis"
INCREMENTS=1                # number of index+backup cycles per version (--increments)
INCREMENTS_SET=0            # 1 once --increments is explicitly passed
TEST_MODE=0
SKIP_INSTALL=0
TOOLS_DIR="${SOLR_ORBIT_TOOLS_DIR:-$HOME/perf}"
PY_VERSION="${PY_VERSION:-3.12}"
S3_UPLOAD=0
AWS_ACCOUNT=""
CHOWN_BACKUPS=0
AWS_CLI="${AWS_CLI:-aws}"

ORBIT_REPO="https://github.com/apache/solr-orbit"
ORBIT_BRANCH="main"
WORKLOADS_REPO="https://github.com/apache/solr-orbit-workloads"

BACKUP_BASE="./backups/standalone"
S3_UPLOAD_BASE="./s3_upload/standalone"
CONTAINER="solr-node1"
SOLR_PORT=8983
SOLR_URL="http://localhost:${SOLR_PORT}"

# ---------------------------------------------------------------------------
# Shared helpers (logging, arg parsing, orbit install)
# ---------------------------------------------------------------------------
# shellcheck source=lib/orbit.sh
source "${SCRIPT_DIR}/lib/orbit.sh"

usage() {
    cat <<'EOF'
Usage: ./run_standalone.sh [options]

Options:
  --versions=LIST     Comma-delimited Solr versions to build (default: 6,7,8,9)
  --increments=N      Index+backup cycles per version; each cycle ingests
                      0.006*cycle of the workload (mutually exclusive with
                      --test-mode). When omitted (and not --test-mode), one cycle
                      ingests the full dataset (ingest_percentage=100).
  --test-mode         solr-orbit's tiny/fast load; forces a single cycle
                      (mutually exclusive with --increments)
  --skip-install      Don't clone/install solr-orbit + workloads
  --tools-dir=DIR     Where to clone the solr-orbit repos (default: $HOME/perf)
  --s3-upload         Sync the resulting snapshots to S3 (step 6)
  --aws-account=NUM   AWS account number for the S3 bucket (required with --s3-upload)
  --aws-cli=CMD       AWS CLI command to use for S3 sync (default: aws)
  --chown-backups     sudo chown ./backups to uid 8983 (Linux hosts)
  -h, --help          Show this help

Environment:
  PY_VERSION=3.12     Python version uv installs/uses for the venv (default: 3.12).
                      Requires uv (https://astral.sh/uv) on PATH.

Examples:
  ./run_standalone.sh --versions=9 --test-mode
  ./run_standalone.sh --versions=7,8,9
  ./run_standalone.sh --versions=8 --increments=3
  ./run_standalone.sh --skip-install --versions=8 --s3-upload --aws-account=371015648411
EOF
}

parse_common_args "$@"
# --test-mode and --increments are mutually exclusive: test-mode forces a single
# cycle (INCREMENTS=1) and a tiny solr-orbit --test-mode load.
if [[ "$TEST_MODE" -eq 1 && "$INCREMENTS_SET" -eq 1 ]]; then
    die "--test-mode and --increments cannot be used together"
fi
[[ "$TEST_MODE" -eq 1 ]] && INCREMENTS=1
[[ "$INCREMENTS" =~ ^[1-9][0-9]*$ ]] \
    || die "--increments must be a positive integer (got: '${INCREMENTS}')"
cd "$SCRIPT_DIR"

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

solr_docker_extra() {
    case "$1" in
        8) echo "-e SOLR_OPTS=-Dsolr.allowPaths=/backups" ;;
        9) echo "-e SOLR_JETTY_HOST=0.0.0.0 -e SOLR_OPTS=-Dsolr.allowPaths=/backups" ;;
        *) echo "" ;;
    esac
}

solr_docker_platform() {
    case "$1" in
        6) echo "--platform linux/amd64" ;;
        *) echo "" ;;
    esac
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
    local increment="${1:-1}"
    local args=(
        run
        --pipeline=benchmark-only
        --target-host="localhost:${SOLR_PORT}"
        --kill-running-processes
        --allow-unsupported-user-managed
        --workload="${WORKLOAD}"
        --include-tasks="check-cluster-health,index"
    )
    if [[ "$TEST_MODE" -eq 1 ]]; then
        # --test-mode: solr-orbit's own tiny/fast load (no ingest_percentage).
        log "Running solr-orbit to load data (workload=${WORKLOAD}, test-mode)"
        args+=(--test-mode)
    else
        local ingest_percentage
        if [[ "$INCREMENTS_SET" -eq 1 ]]; then
            # --increments given: scale the load with the cycle number, 0.0006 per
            # increment (cycle 1 -> 0.0006, cycle 2 -> 0.0012, ...). awk handles the float.
            ingest_percentage="$(awk -v i="$increment" 'BEGIN { printf "%g", 0.0006 * i }')"
        else
            # Neither --increments nor --test-mode: ingest the full dataset (100%).
            ingest_percentage=100
        fi
        log "Running solr-orbit to load data (workload=${WORKLOAD}, ingest_percentage=${ingest_percentage})"
        args+=(--workload-params="ingest_percentage:${ingest_percentage}")
    fi
    solr-orbit "${args[@]}"
}

backup_core() {
    local name="$1"
    curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?commit=true" >/dev/null
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
            echo "Backup '${name}' complete."; return 0
        fi
        if compgen -G "${BACKUP_BASE}/snapshot.${name}/segments_*" >/dev/null 2>&1; then
            echo "Backup '${name}' complete (segments present)."; return 0
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
# Snapshot reshaping — operates on the copy in ${S3_UPLOAD_BASE}
# ---------------------------------------------------------------------------
reshape_snapshot() {
    local name="$1"
    local version="$2"
    local dir="${S3_UPLOAD_BASE}/${name}"

    log "Reshaping snapshot ${dir} into the MA layout"
    mkdir -p "$dir/nyc_taxis/index"
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

    # Run INCREMENTS index+backup cycles against the same core. Each cycle adds
    # another round of indexing; backup_core overwrites the same snapshot name, so
    # the retained snapshot reflects the cumulative state after the last cycle.
    local i
    for (( i = 1; i <= INCREMENTS; i++ )); do
        [[ "$INCREMENTS" -gt 1 ]] && log "Index + backup cycle ${i}/${INCREMENTS}"
        run_orbit "$i"
        backup_core "nyc_taxis_${version}"
    done

    if [[ "$version" == "9" ]]; then
        log "Optimizing to a single segment"
        curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?optimize=true&maxSegments=1" >/dev/null
        backup_core "nyc_taxis_9_onesegment"
    fi

    stop_solr
}

# ---------------------------------------------------------------------------
# S3 upload (step 6)
# ---------------------------------------------------------------------------
version_snapshots() {
    local v="$1"
    echo "snapshot.nyc_taxis_${v}"
    case "$v" in
        9) echo "snapshot.nyc_taxis_9_onesegment" ;;
    esac
}

prepare_s3_upload() {
    log "Preparing ${S3_UPLOAD_BASE} from backups for versions ${VERSIONS}"
    local v name src dst
    for v in "${VERSION_LIST[@]}"; do
        v="$(echo "$v" | tr -d '[:space:]')"
        [[ -n "$v" ]] || continue
        for name in $(version_snapshots "$v"); do
            src="${BACKUP_BASE}/${name}"
            dst="${S3_UPLOAD_BASE}/${name}"
            if [[ ! -d "$src" ]]; then
                warn "expected snapshot ${src} not found; skipping"; continue
            fi
            log "Copying ${src} -> ${dst}"
            rm -rf "$dst"
            cp -a "$src" "$dst"
            if [[ "$v" == "7" || "$v" == "8" || "$v" == "9" ]]; then
                reshape_snapshot "$name" "$v"
            fi
        done
    done
}

s3_upload() {
    local s3_base="s3://migrations-default-${AWS_ACCOUNT}-dev-us-east-1/solr-migration-test-sets/standalone"
    log "Syncing ${S3_UPLOAD_BASE} for versions ${VERSIONS} to ${s3_base}"
    local v name dst
    for v in "${VERSION_LIST[@]}"; do
        v="$(echo "$v" | tr -d '[:space:]')"
        [[ -n "$v" ]] || continue
        for name in $(version_snapshots "$v"); do
            dst="${S3_UPLOAD_BASE}/${name}"
            if [[ ! -d "$dst" ]]; then
                warn "expected s3_upload dir ${dst} not found; skipping"; continue
            fi
            echo "  $dst -> ${s3_base}/${name}"
            "$AWS_CLI" s3 sync --delete "$dst" "${s3_base}/${name}"
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

    prepare_s3_upload

    if [[ "$S3_UPLOAD" -eq 1 ]]; then
        s3_upload
    fi

    log "Done. Raw snapshots are under ${BACKUP_BASE}/; S3-ready copies under ${S3_UPLOAD_BASE}/"
    log "Step 7 (Migration Assistant) is interactive — see steps_standalone.md."
}

main
