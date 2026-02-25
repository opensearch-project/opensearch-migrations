#!/bin/bash

# Optional Parameters - 5 minute total retry window
ETCD_RETRIES=${ETCD_RETRIES:-30}
ETCD_RETRY_DELAY=${ETCD_RETRY_DELAY:-5}
ETCD_DIAL_TIMEOUT=${ETCD_DIAL_TIMEOUT:-"10s"}
ETCD_CMD_TIMEOUT=${ETCD_CMD_TIMEOUT:-"10s"}

# Required Parameters
if [ -z "$ETCD_ENDPOINTS" ]; then
  echo "Error: ETCD_ENDPOINTS is not set." >&2
  exit 1
fi

# Export the base command with timeouts so it fails fast rather than hanging
export ETCDCTL_API=3
export ETCD_CMD="etcdctl --endpoints=$ETCD_ENDPOINTS --user $ETCD_USER:$ETCD_PASSWORD --dial-timeout=$ETCD_DIAL_TIMEOUT --command-timeout=$ETCD_CMD_TIMEOUT"

# Run an arbitrary command that doesn't need to return/handle stdout from etcdctl (like WRITE/DELETE)
# Usage: etcd_safe_run put key value
etcd_safe_run() {
  local attempt=1
  local cmd="$*"

  while [ $attempt -le $ETCD_RETRIES ]; do
    # We temporarily disable 'set -e' to catch the failure
    set +e
    $ETCD_CMD "$@" > /dev/null
    local status=$?
    set -e

    if [ $status -eq 0 ]; then
      return 0
    fi

    echo "[etcd_lib] Command failed (Attempt $attempt/$ETCD_RETRIES): $cmd" >&2
    sleep $ETCD_RETRY_DELAY
    attempt=$((attempt + 1))
  done

  echo "[etcd_lib] Critical: Max retries reached for operation." >&2
  return 1
}

# Wrapper for getting values safely
# This is separate from etcd_safe_run since the output for get needs to be captured
# Usage: MY_VAR=$(etcd_safe_get "my/key")
etcd_safe_get() {
  local key="$1"
  local attempt=1

  while [ $attempt -le $ETCD_RETRIES ]; do
    set +e
    # Capture output and status separately, silence stderr
    local output
    output=$($ETCD_CMD get "$key" --print-value-only 2>/dev/null)
    local status=$?
    set -e

    if [ $status -eq 0 ]; then
      echo "$output"
      return 0
    fi

    # Log to stderr so we don't pollute the captured variable
    echo "[etcd_lib] Get failed (Attempt $attempt/$ETCD_RETRIES): $key" >&2
    sleep $ETCD_RETRY_DELAY
    attempt=$((attempt + 1))
  done

  return 1
}

# Wrapper for getting just keys safely
# Usage: MY_KEYS=$(etcd_safe_get_keys "my/prefix/")
# Note: Automatically adds --prefix and --keys-only
etcd_safe_get_keys() {
  local prefix="$1"
  local attempt=1

  while [ $attempt -le $ETCD_RETRIES ]; do
    set +e
    local output
    output=$($ETCD_CMD get "$prefix" --prefix --keys-only 2>/dev/null)
    local status=$?
    set -e

    if [ $status -eq 0 ]; then
      echo "$output"
      return 0
    fi

    echo "[etcd_lib] Key list fetch failed (Attempt $attempt/$ETCD_RETRIES): $prefix" >&2
    sleep $ETCD_RETRY_DELAY
    attempt=$((attempt + 1))
  done

  return 1
}
