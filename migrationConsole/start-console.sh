#!/bin/bash
set -e

if [[ -f /root/loadServicesFromParameterStore.sh ]]; then
  /root/loadServicesFromParameterStore.sh
fi

# Mount S3 bucket if configured via environment variables
if [[ -n "${S3_ARTIFACTS_BUCKET_NAME}" ]]; then
  MOUNT_POINT="/s3/artifacts"
  mkdir -p "$MOUNT_POINT"

  MOUNT_ARGS=("$S3_ARTIFACTS_BUCKET_NAME" "$MOUNT_POINT" "--read-only")
  [[ -n "${S3_ARTIFACTS_REGION}" ]] && MOUNT_ARGS+=("--region" "${S3_ARTIFACTS_REGION}")
  [[ -n "${S3_ARTIFACTS_ENDPOINT_URL}" ]] && MOUNT_ARGS+=("--endpoint-url" "${S3_ARTIFACTS_ENDPOINT_URL}" "--force-path-style" "--no-sign-request")

  echo "Waiting for S3 bucket ${S3_ARTIFACTS_BUCKET_NAME}..."
  until mount-s3 "${MOUNT_ARGS[@]}" 2>/dev/null; do
    echo "  bucket not available yet, retrying in 5s..."
    sleep 5
  done
  echo "Mounted ${S3_ARTIFACTS_BUCKET_NAME} at ${MOUNT_POINT}"
fi

echo "Starting API server..."
LOG_DIR="${SHARED_LOGS_DIR_PATH:-/var/log}/api/logs"
mkdir -p "$LOG_DIR"

cd /root/lib/console_link
export FASTAPI_ROOT_PATH=/api
exec pipenv run gunicorn console_link.api.main:app \
    -k uvicorn.workers.UvicornWorker \
    -w 4 \
    -b 0.0.0.0:8000 \
    --access-logfile "$LOG_DIR/access.log" \
    --error-logfile "$LOG_DIR/error.log"
