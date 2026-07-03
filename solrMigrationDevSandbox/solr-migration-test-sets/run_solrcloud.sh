#!/usr/bin/env bash
#
# run_solrcloud.sh — automate the SolrCloud-mode steps in steps_solrcloud.md
#
# For each requested Solr version (6, 7, 8, 9) this script:
#   1. starts a 3-node SolrCloud cluster via docker-compose-<v>.yml
#   2. creates the `nyc_taxis` collection (3 shards, RF1) from ./solr_configsets/nyc_taxis_<v>/conf
#   3. runs solr-orbit to load data
#   4. backs up the collection via the Collections API into ./backups/solrcloud/<name>
#      (raw Solr output; no post-processing)
#      (v8/v9 incremental BACKUP already writes the <name>/<collection>/ layout MA
#      expects; v6/v7 non-incremental BACKUP omits the <collection>/ level)
#   5. (v9) also produces a single-segment (optimized) backup
#
# --increments=N repeats steps 3-4 N times (default 1): N index+backup cycles
# against the same collection, each ingesting 0.006*cycle of the workload. Each
# cycle re-runs the backup to the same name, so the retained backup reflects the
# cumulative state after the final cycle. Mutually exclusive with --test-mode;
# when neither is given, a single cycle ingests the full dataset.
#
# When --s3-upload is used, each backup is copied from ./backups/solrcloud/ into
# ./s3_upload/solrcloud/, where v6/v7 backups are reshaped (adding the nyc_taxis/
# collection subdirectory MA expects) before syncing to S3.
#
# Step 4 (Migration Assistant) is interactive and is intentionally NOT automated here.

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

# solr-orbit source. SolrCloud runs in cloud mode and never needs the fork's
# --allow-user-managed flag, so use upstream apache/solr-orbit main — the same repo
# + branch run_standalone.sh installs. (They share one checkout/venv under
# $TOOLS_DIR; keeping both on the same ref makes reinstalls idempotent instead of
# thrashing each other.)
ORBIT_REPO="https://github.com/apache/solr-orbit"
ORBIT_BRANCH="main"
WORKLOADS_REPO="https://github.com/apache/solr-orbit-workloads"

BACKUP_BASE="./backups/solrcloud"
S3_UPLOAD_BASE="./s3_upload/solrcloud"
# Host ports for the three cluster nodes (see docker-compose-<v>.yml).
CLUSTER_PORTS=(8983 8984 8985)
SOLR_PORT=8983                       # node1 — used for Collections API / orbit target
SOLR_URL="http://localhost:${SOLR_PORT}"
COMPOSE=(docker compose)

# ---------------------------------------------------------------------------
# Shared helpers (logging, arg parsing, orbit install)
# ---------------------------------------------------------------------------
# shellcheck source=lib/orbit.sh
source "${SCRIPT_DIR}/lib/orbit.sh"

usage() {
    cat <<'EOF'
Usage: ./run_solrcloud.sh [options]

Options:
  --versions=LIST     Comma-delimited Solr versions to build (default: 6,7,8,9)
                      e.g. --versions=8,9
  --increments=N      Index+backup cycles per version; each cycle ingests
                      0.006*cycle of the workload (mutually exclusive with
                      --test-mode). When omitted (and not --test-mode), one cycle
                      ingests the full dataset (ingest_percentage=100).
  --test-mode         solr-orbit's tiny/fast load; forces a single cycle
                      (mutually exclusive with --increments)
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
    local increment="${1:-1}"
    local args=(
        run
        --pipeline=benchmark-only
        --target-host="localhost:${SOLR_PORT}"
        --kill-running-processes
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

# Back up the collection via the Collections API. SolrCloud BACKUP is synchronous
# by default and writes <location>/<name>/ in the MA-expected layout.
backup_collection() {
    local name="$1"
    local snap_name="solrcloud_backup_$$"

    # Hard-commit first: BACKUP only captures committed segments, and solr-orbit's
    # load may leave docs uncommitted (otherwise the backup is empty).
    # openSearcher=true ensures the searcher is reopened on this commit so its
    # getIndexCommit() refers to a commit whose files are on disk.
    curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?commit=true&openSearcher=true" >/dev/null

    # Pin the current commit via CREATESNAPSHOT. The root cause of the
    # NoSuchFileException on Solr 6 is that autoCommit fires every 15s with
    # openSearcher=false: it writes segments_N+1 and Lucene's
    # KeepOnlyLastCommitDeletionPolicy deletes segments_N, but the searcher's NRT
    # reader still reports segments_N via getIndexCommit(). CREATESNAPSHOT calls
    # IndexDeletionPolicyWrapper.snapshot() on each shard leader, which pins the
    # commit so the deletion policy cannot remove its files even if new auto-commits
    # arrive before BACKUP finishes. Passing commitName to BACKUP tells BackupCoreOp
    # to use the pinned commit directly rather than the (potentially stale) NRT reader.
    log "Creating snapshot ${snap_name} to pin commit before backup"
    curl -fsS "${SOLR_URL}/solr/admin/collections?action=CREATESNAPSHOT&collection=nyc_taxis&commitName=${snap_name}&wt=json" >/dev/null \
        || warn "CREATESNAPSHOT failed; backup may hit NoSuchFileException on Solr 6"

    # Start fresh: Solr 6/7 refuse to back up into an existing directory (HTTP 400),
    # and Solr 8/9 would otherwise pile up extra incremental revisions. Delete from
    # *inside* the container so Solr's view of the bind mount is immediately consistent
    # — a host-side rm can race the mount and make Solr 500 mid-backup.
    docker exec solr-node1 rm -rf "/backups/solrcloud/${name}" 2>/dev/null || true
    log "Backing up collection -> ${BACKUP_BASE}/${name}"
    local resp
    resp="$(curl -fsS "${SOLR_URL}/solr/admin/collections?action=BACKUP&name=${name}&collection=nyc_taxis&location=/backups/solrcloud&commitName=${snap_name}&wt=json")" \
        || die "BACKUP request failed for ${name}"

    # Release the snapshot so the pinned commit can be cleaned up by the deletion policy
    curl -fsS "${SOLR_URL}/solr/admin/collections?action=DELETESNAPSHOT&collection=nyc_taxis&commitName=${snap_name}&wt=json" >/dev/null 2>&1 || true

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

# v6/v7 only: nest the backup under a `nyc_taxis/` collection directory in s3_upload.
# Operates on the copy in ${S3_UPLOAD_BASE} (not the raw backup in ${BACKUP_BASE}).
reshape_solrcloud_collection() {
    local version="$1"
    local dir="${S3_UPLOAD_BASE}/nyc_taxis_${version}"
    local coll="${dir}/nyc_taxis"
    [[ -d "$dir" ]] || { warn "backup dir ${dir} not found; skipping reshape"; return; }
    # Idempotent: if the contents are already nested (e.g. a re-run), do nothing.
    if [[ -f "${coll}/backup.properties" ]]; then
        log "v${version}: backup already nested under nyc_taxis/; skipping reshape"
        return
    fi
    log "v${version}: nesting backup under ${coll}/ for the MA collection layout"
    mkdir -p "$coll"
    find "$dir" -maxdepth 1 -mindepth 1 ! -name nyc_taxis \
        -exec mv {} "$coll/" \;
}

# ---------------------------------------------------------------------------
# Per-version driver
# ---------------------------------------------------------------------------
run_version() {
    local version="$1"
    log "######## Solr ${version} ########"

    compose_up "$version"
    create_collection "$version"

    # Run INCREMENTS index+backup cycles against the same collection. Each cycle
    # adds another round of indexing; backup_collection overwrites the same backup
    # name, so the retained backup reflects the cumulative state after the last cycle.
    local i
    for (( i = 1; i <= INCREMENTS; i++ )); do
        [[ "$INCREMENTS" -gt 1 ]] && log "Index + backup cycle ${i}/${INCREMENTS}"
        run_orbit "$i"
        backup_collection "nyc_taxis_${version}"
    done

    if [[ "$version" == "9" ]]; then
        log "Optimizing to a single segment"
        curl -fsS "${SOLR_URL}/solr/nyc_taxis/update?optimize=true&maxSegments=1" >/dev/null
        backup_collection "nyc_taxis_9_onesegment"
    fi

    compose_down "$version"
}

# ---------------------------------------------------------------------------
# S3 upload (step 3)
# ---------------------------------------------------------------------------
version_backups() {
    local v="$1"
    echo "nyc_taxis_${v}"
    case "$v" in
        9) echo "nyc_taxis_9_onesegment" ;;
    esac
}

prepare_s3_upload() {
    log "Preparing ${S3_UPLOAD_BASE} from backups for versions ${VERSIONS}"
    local v name src dst
    for v in "${VERSION_LIST[@]}"; do
        v="$(echo "$v" | tr -d '[:space:]')"
        [[ -n "$v" ]] || continue
        for name in $(version_backups "$v"); do
            src="${BACKUP_BASE}/${name}"
            dst="${S3_UPLOAD_BASE}/${name}"
            if [[ ! -d "$src" ]]; then
                warn "expected backup ${src} not found; skipping"; continue
            fi
            log "Copying ${src} -> ${dst}"
            rm -rf "$dst"
            cp -a "$src" "$dst"
            if [[ "$v" == "6" || "$v" == "7" ]]; then
                reshape_solrcloud_collection "$v"
            fi
        done
    done
}

s3_upload() {
    local s3_base="s3://migrations-default-${AWS_ACCOUNT}-dev-us-east-1/solr-migration-test-sets/solrcloud"
    log "Syncing ${S3_UPLOAD_BASE} for versions ${VERSIONS} to ${s3_base}"
    local v name dst
    for v in "${VERSION_LIST[@]}"; do
        v="$(echo "$v" | tr -d '[:space:]')"
        [[ -n "$v" ]] || continue
        for name in $(version_backups "$v"); do
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

    log "Done. Raw backups are under ${BACKUP_BASE}/; S3-ready copies under ${S3_UPLOAD_BASE}/"
    log "Step 4 (Migration Assistant) is interactive — see steps_solrcloud.md."
}

main
