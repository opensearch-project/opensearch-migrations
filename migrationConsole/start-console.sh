#!/bin/bash
set -e

if [[ -f /root/loadServicesFromParameterStore.sh ]]; then
  /root/loadServicesFromParameterStore.sh
fi

# Mount artifact bucket if configured via REPO_ARTIFACTS_BUCKET (full URI: s3://... or gs://...)
if [[ -n "${REPO_ARTIFACTS_BUCKET}" ]]; then
  MOUNT_POINT="/artifacts"
  mkdir -p "$MOUNT_POINT"

  SCHEME="${REPO_ARTIFACTS_BUCKET%%://*}"
  BUCKET="${REPO_ARTIFACTS_BUCKET#*://}"

  if [[ "$SCHEME" == "s3" ]]; then
    MOUNT_ARGS=("$BUCKET" "$MOUNT_POINT" "--read-only")
    [[ -n "${REPO_ARTIFACTS_ENDPOINT_URL}" ]] && MOUNT_ARGS+=("--endpoint-url" "${REPO_ARTIFACTS_ENDPOINT_URL}" "--force-path-style" "--no-sign-request")

    echo "Waiting for S3 bucket ${BUCKET}..."
    until mount-s3 "${MOUNT_ARGS[@]}" 2>/dev/null; do
      echo "  bucket not available yet, retrying in 5s..."
      sleep 5
    done
    echo "Mounted s3://${BUCKET} at ${MOUNT_POINT}"

  elif [[ "$SCHEME" == "gs" ]]; then
    FUSE_OPTS="--implicit-dirs"
    [[ -n "${REPO_ARTIFACTS_ENDPOINT_URL}" ]] && FUSE_OPTS="$FUSE_OPTS --custom-endpoint=${REPO_ARTIFACTS_ENDPOINT_URL}"

    echo "Waiting for GCS bucket ${BUCKET}..."
    until gcsfuse $FUSE_OPTS "$BUCKET" "$MOUNT_POINT" 2>/dev/null; do
      echo "  bucket not available yet, retrying in 5s..."
      sleep 5
    done
    echo "Mounted gs://${BUCKET} at ${MOUNT_POINT}"

  else
    echo "Warning: unrecognized scheme in REPO_ARTIFACTS_BUCKET: ${REPO_ARTIFACTS_BUCKET}" >&2
  fi
fi

echo "Console ready."
trap 'exit 0' TERM
sleep infinity &
wait $!
