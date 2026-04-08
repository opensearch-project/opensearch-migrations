#!/bin/sh
set -e

# Entrypoint for the snapshot-fuse sidecar.
# S3 data is available at /mnt/s3 via NFS PersistentVolume (S3 Files).
# We only run snapshot-fuse to translate snapshot blobs → virtual Lucene files.

FUSE_EXTRA_ARGS=""
[ -n "${FUSE_THREADS:-}" ] && FUSE_EXTRA_ARGS="${FUSE_EXTRA_ARGS} --threads ${FUSE_THREADS}"

# shellcheck disable=SC2086
exec snapshot-fuse "$@" ${FUSE_EXTRA_ARGS}
