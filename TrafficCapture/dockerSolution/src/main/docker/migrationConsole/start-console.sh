#!/bin/bash
set -e

if [[ -f /root/loadServicesFromParameterStore.sh ]]; then
  /root/loadServicesFromParameterStore.sh
fi

echo "Starting API server..."
if [ -n "$SHARED_LOGS_DIR_PATH" ]; then
    LOG_DIR="$SHARED_LOGS_DIR_PATH/api/logs"
    mkdir -p "$LOG_DIR"
else
    LOG_DIR="/var/log"
fi

cd /root/lib/console_link
export FASTAPI_ROOT_PATH=/api
pipenv run gunicorn console_link.api.main:app \
    -k uvicorn.workers.UvicornWorker \
    -w 4 \
    -b 0.0.0.0:8000 \
    --access-logfile "$LOG_DIR/access.log" \
    --error-logfile "$LOG_DIR/error.log" &

sleep 2

cd /root

# Keep container running and interactive
exec tail -f /dev/null
