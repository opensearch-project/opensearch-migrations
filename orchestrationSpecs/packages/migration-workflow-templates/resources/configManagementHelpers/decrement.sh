#!/bin/bash
set -e

# Import Library
source ./etcdClientHelper.sh

# PREFIX, PROCESSOR_ID, and TARGET_NAME env vars are set by the workflow template

normalize_endpoint() {
  echo "$1" | base64
}

NORMALIZED_TARGET=$(normalize_endpoint "$TARGET_NAME")
LATCH_KEY_NAME=/$PREFIX/workflow/targets/$NORMALIZED_TARGET/latch
FRIENDLY_NAME="${NORMALIZED_TARGET}-${PROCESSOR_ID}"

# Record processor completion
etcd_safe_run put "/$PREFIX/workflow/targets/$NORMALIZED_TARGET/finishedSubFlows/$FRIENDLY_NAME" "completed"

execute_transaction() {
  local current_value="$1"
  local next_value="$2"

  echo "Attempting Transaction (LATCH_KEY_NAME={$LATCH_KEY_NAME}): $current_value -> $next_value"

  # We use raw $ETCD_CMD here because heredocs don't play nice with wrapper functions
  set +e
  $ETCD_CMD txn --write-out=json << EOF | jq -e '.succeeded == true' > /dev/null
val("$LATCH_KEY_NAME") = "$current_value"

put $LATCH_KEY_NAME "$next_value"


EOF
  local status=$?
  set -e

  return $status
}

# Transaction retry loop
while true; do
  # retry for transient service errors is already built into safe get.
  # Our loop deals with the content of the key OR (later) a transient error while running the transaction
  CURRENT_COUNT=$(etcd_safe_get "$LATCH_KEY_NAME")

  # Handle case where key doesn't exist yet (empty string)
  if [ -z "$CURRENT_COUNT" ]; then
    CURRENT_COUNT=0 # Or handle error depending on your logic
  fi

  NEW_COUNT=$((CURRENT_COUNT - 1))

  if execute_transaction "$CURRENT_COUNT" "$NEW_COUNT"; then
    echo "Transaction succeeded"
    break
  else
    # This catches both:
    # A. Logical failure (value changed by someone else)
    # B. Network failure (etcd command failed)
    echo "Transaction failed (Network or compare-and-swap mismatch), retrying..."
    sleep 1
  fi
done

# Default: don't finalize yet
SHOULD_FINALIZE="false"

# Check if latch has reached zero
if [ "$NEW_COUNT" -eq 0 ]; then
  echo "All processors for target $TARGET_NAME have completed" >&2
  SHOULD_FINALIZE="true"
else
  echo "Target $TARGET_NAME still has $NEW_COUNT processors pending" >&2
fi

# Output just the boolean value to stdout for the result
echo $SHOULD_FINALIZE > /tmp/should-finalize
echo $SHOULD_FINALIZE