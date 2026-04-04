#!/bin/sh
set -e

S3_MOUNT="${S3_MOUNT_PATH:-/mnt/s3}"
CACHE_DIR="${CACHE_DIR:-/cache}"
MAX_CACHE_MB="${MAX_CACHE_MB:-10000}"

# Forcefully clean a path that may be a stale FUSE mount
force_clean() {
    umount -l "$1" 2>/dev/null || fusermount -uz "$1" 2>/dev/null || true
    rm -f "$1" 2>/dev/null || true
    rmdir "$1" 2>/dev/null || true
}

if [ -n "${POD_NAME:-}" ]; then
    POD_DIR="/mnt/.pods/${POD_NAME}"
    mkdir -p "${POD_DIR}"

    S3_MOUNT="${POD_DIR}/s3"
    FUSE_MOUNT="${POD_DIR}/lucene"

    # Clean stale mounts at well-known paths before creating symlinks
    force_clean /mnt/s3
    force_clean /mnt/lucene

    ln -sfn "${POD_DIR}/s3" /mnt/s3
    ln -sfn "${POD_DIR}/lucene" /mnt/lucene

    # Rewrite args to use per-pod paths
    NEW_ARGS=""
    for arg in "$@"; do
        case "$arg" in
            --mount-point=*) NEW_ARGS="${NEW_ARGS} --mount-point=${FUSE_MOUNT}" ;;
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

exec snapshot-fuse "$@"
