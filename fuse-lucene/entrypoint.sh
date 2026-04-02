#!/bin/sh
set -e

# Entrypoint for the snapshot-fuse sidecar.
# If S3_BUCKET is set, mounts the S3 bucket via mount-s3 before starting the FUSE layer.
# Otherwise, assumes the repo root is already available (e.g., hostPath for testing).

S3_MOUNT="${S3_MOUNT_PATH:-/mnt/s3}"
CACHE_DIR="${CACHE_DIR:-/cache}"
MAX_CACHE_MB="${MAX_CACHE_MB:-5000}"

if [ -n "${S3_BUCKET:-}" ]; then
    echo "Mounting S3 bucket '${S3_BUCKET}' at ${S3_MOUNT} (cache: ${CACHE_DIR}, max ${MAX_CACHE_MB}MB)"
    mkdir -p "${S3_MOUNT}" "${CACHE_DIR}"

    MOUNT_ARGS="--read-only --cache ${CACHE_DIR} --max-cache-size ${MAX_CACHE_MB}"

    if [ -n "${S3_ENDPOINT_URL:-}" ]; then
        MOUNT_ARGS="${MOUNT_ARGS} --endpoint-url ${S3_ENDPOINT_URL}"
    fi
    if [ "${S3_FORCE_PATH_STYLE:-false}" = "true" ]; then
        MOUNT_ARGS="${MOUNT_ARGS} --force-path-style"
    fi
    if [ -n "${S3_PREFIX:-}" ]; then
        MOUNT_ARGS="${MOUNT_ARGS} --prefix ${S3_PREFIX}"
    fi

    # mount-s3 daemonizes by default; --foreground keeps it in the background via &
    # shellcheck disable=SC2086
    mount-s3 ${MOUNT_ARGS} --foreground "${S3_BUCKET}" "${S3_MOUNT}" &
    MOUNT_PID=$!

    # Wait for mount to be ready
    for i in $(seq 1 30); do
        if mountpoint -q "${S3_MOUNT}" 2>/dev/null; then
            echo "S3 mount ready"
            break
        fi
        if ! kill -0 "$MOUNT_PID" 2>/dev/null; then
            echo "mount-s3 exited unexpectedly"
            exit 1
        fi
        sleep 1
    done

    if ! mountpoint -q "${S3_MOUNT}" 2>/dev/null; then
        echo "Timed out waiting for S3 mount"
        exit 1
    fi
fi

# Start snapshot-fuse with all passed arguments
exec snapshot-fuse "$@"
