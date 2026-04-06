#!/bin/sh
set -e

# Entrypoint for the snapshot-fuse sidecar.
# Creates per-pod FUSE/S3 mounts inside a shared hostPath volume.
# The bulk-loader container rewrites its config to use the per-pod paths directly.

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

if [ -n "${S3_BUCKET:-}" ]; then
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

exec snapshot-fuse "$@" ${FUSE_EXTRA_ARGS}
