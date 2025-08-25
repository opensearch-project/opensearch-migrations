set -x -e
PROCESSOR_ID="$PROCESSOR_ID"
TARGET_NAME="$TARGET_NAME"
ETCD_ENDPOINT=$ETCD_ENDPOINTS
PREFIX="$WORKFLOW_PREFIX"

normalize_endpoint() {
  echo "$1" | base64
}

NORMALIZED_TARGET=$(normalize_endpoint "$TARGET_NAME")

USERNAME=$ETCD_USER
PASSWORD=$ETCD_PASSWORD
LATCH_KEY_NAME=/$PREFIX/workflow/targets/$NORMALIZED_TARGET/latch

FRIENDLY_NAME="${NORMALIZED_TARGET}-${PROCESSOR_ID}"

export ETCDCTL_API=3

# Run etcdctl with configured endpoints
etcdctl_cmd="etcdctl --endpoints=$ETCD_ENDPOINT --user $USERNAME:$PASSWORD"

# Record this processor as finished
$etcdctl_cmd put /$PREFIX/workflow/targets/$NORMALIZED_TARGET/finishedSubFlows/$FRIENDLY_NAME "completed"

execute_transaction() {
local current_value="$1"
local next_value="$2"

echo "LATCH_KEY_NAME=$LATCH_KEY_NAME"
echo "current_value=$current_value"
echo "next_value=$next_value"
echo "etcdctl_cmd=$etcdctl_cmd"

# be very mindful of the empty lines in the file being sent to the transaction command!
$etcdctl_cmd txn  --write-out=json << EOF | jq -e '.succeeded == true'
val("$LATCH_KEY_NAME") = "$current_value"

put $LATCH_KEY_NAME "$next_value"


EOF
}

# Transaction retry loop
while true; do
  CURRENT_COUNT=$($etcdctl_cmd get  $LATCH_KEY_NAME --print-value-only)
  NEW_COUNT=$((CURRENT_COUNT - 1))
  if execute_transaction "$CURRENT_COUNT" "$NEW_COUNT"; then
    echo "Transaction succeeded"
    break
  else
    echo "Transaction failed, retrying..."
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