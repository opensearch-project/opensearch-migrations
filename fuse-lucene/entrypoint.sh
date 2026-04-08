#!/bin/sh
set -e

# Entrypoint for the snapshot-fuse sidecar.
#
# Three modes of S3 access (checked in order):
# 1. S3 Files (NFS): S3_FILES_FS_ID is set — mount via NFS, no mount-s3 needed.
# 2. mount-s3 (FUSE): S3_BUCKET is set — mount S3 via mount-s3.
# 3. Pre-mounted: Neither set — /mnt/s3 is already mounted (e.g. NFS PVC).

S3_MOUNT="${S3_MOUNT_PATH:-/mnt/s3}"
CACHE_DIR="${CACHE_DIR:-/cache}"
MAX_CACHE_MB="${MAX_CACHE_MB:-10000}"

# When POD_NAME is set, use per-pod directories to avoid conflicts
# between pods sharing the same hostPath on a node.
if [ -n "${POD_NAME:-}" ]; then
    POD_DIR="/mnt/.pods/${POD_NAME}"
    mkdir -p "${POD_DIR}"
    S3_MOUNT="${POD_DIR}/s3"

    # Rewrite args to use per-pod paths
    NEW_ARGS=""
    for arg in "$@"; do
        case "$arg" in
            --mount-point=*) NEW_ARGS="${NEW_ARGS} --mount-point=${POD_DIR}/lucene" ;;
            --repo-root=/mnt/s3/*) NEW_ARGS="${NEW_ARGS} --repo-root=${S3_MOUNT}/${arg#--repo-root=/mnt/s3/}" ;;
            *) NEW_ARGS="${NEW_ARGS} ${arg}" ;;
        esac
    done
    set -- ${NEW_ARGS}
fi

if [ -n "${S3_FILES_FS_ID:-}" ]; then
    # Mode 1: S3 Files — mount the file system via NFS
    echo "Mounting S3 Files filesystem '${S3_FILES_FS_ID}' at ${S3_MOUNT} via NFS"
    mkdir -p "${S3_MOUNT}"
    S3_FILES_DNS="${S3_FILES_FS_ID}.s3files.${AWS_REGION}.amazonaws.com"
    mount -t nfs4 -o nfsvers=4.1,rsize=1048576,wsize=1048576,hard,timeo=600,retrans=2,noresvport \
        "${S3_FILES_DNS}:/" "${S3_MOUNT}"
    echo "S3 Files NFS mount ready"

elif [ -n "${S3_BUCKET:-}" ]; then
    # Mode 2: mount-s3 — mount S3 bucket via FUSE
    echo "Mounting S3 bucket '${S3_BUCKET}' at ${S3_MOUNT} (cache: ${CACHE_DIR}, max ${MAX_CACHE_MB}MB)"
    mkdir -p "${S3_MOUNT}" "${CACHE_DIR}"

    MOUNT_ARGS="--read-only --cache ${CACHE_DIR} --max-cache-size ${MAX_CACHE_MB}"
    [ -n "${S3_ENDPOINT_URL:-}" ] && MOUNT_ARGS="${MOUNT_ARGS} --endpoint-url ${S3_ENDPOINT_URL}"
    [ "${S3_FORCE_PATH_STYLE:-false}" = "true" ] && MOUNT_ARGS="${MOUNT_ARGS} --force-path-style"
    [ -n "${S3_PREFIX:-}" ] && MOUNT_ARGS="${MOUNT_ARGS} --prefix ${S3_PREFIX}"

    # shellcheck disable=SC2086
    mount-s3 ${MOUNT_ARGS} --foreground "${S3_BUCKET}" "${S3_MOUNT}" &
    MOUNT_PID=$!

    for i in $(seq 1 30); do
        if mountpoint -q "${S3_MOUNT}" 2>/dev/null; then
            echo "S3 mount ready"
            break
        fi
        if ! kill -0 "$MOUNT_PID" 2>/dev/null; then
            echo "mount-s3 exited unexpectedly"; exit 1
        fi
        sleep 1
    done

    if ! mountpoint -q "${S3_MOUNT}" 2>/dev/null; then
        echo "Timed out waiting for S3 mount"; exit 1
    fi
fi

# Pass tunable parameters from env vars
FUSE_EXTRA_ARGS=""
[ -n "${FUSE_THREADS:-}" ] && FUSE_EXTRA_ARGS="${FUSE_EXTRA_ARGS} --threads ${FUSE_THREADS}"

# shellcheck disable=SC2086
exec snapshot-fuse "$@" ${FUSE_EXTRA_ARGS}
