export ETCDCTL_API=3
etcdctl_cmd="etcdctl --endpoints=$ETCD_ENDPOINTS --user $ETCD_USER:$ETCD_PASSWORD"

PREFIX="$WORKFLOW_PREFIX"
echo "===== CLEANING UP ETCD KEYS FOR PREFIX $PREFIX ====="

# Record workflow completion time
$etcdctl_cmd put /$PREFIX/workflow/info/completed "$(date +%s)"
STARTED=$($etcdctl_cmd get /$PREFIX/workflow/info/started --print-value-only)
COMPLETED=$(date +%s)
DURATION=$((COMPLETED - STARTED))

echo "Workflow completed in $DURATION seconds"

# Get workflow stats for logging purposes
echo "Workflow completion stats:"

# Keep statistics in a separate key that will persist
STATS_KEY="workflow-stats/runs/$PREFIX"

# Save summarized workflow stats to a persistent key
$etcdctl_cmd put /$STATS_KEY/started "$STARTED"
$etcdctl_cmd put /$STATS_KEY/completed "$COMPLETED"
$etcdctl_cmd put /$STATS_KEY/duration "$DURATION"

# For each target, save its finalized status and completed processors
for TARGET_KEY in $($etcdctl_cmd get /$PREFIX/workflow/targets/ --prefix --keys-only | grep "/latch$" | sort); do
  TARGET_PATH=$(echo "$TARGET_KEY" | sed "s|/$PREFIX/workflow/targets/||" | sed "s|/latch$||")
  TARGET_ENDPOINT=$($etcdctl_cmd get /$PREFIX/workflow/targets/$TARGET_PATH/endpoint --print-value-only)
  LATCH_VALUE=$($etcdctl_cmd get $TARGET_KEY --print-value-only)

  # Save the target stats
  $etcdctl_cmd put /$STATS_KEY/targets/$TARGET_PATH/endpoint "$TARGET_ENDPOINT"
  $etcdctl_cmd put /$STATS_KEY/targets/$TARGET_PATH/final_latch_value "$LATCH_VALUE"

  # Save the list of completed processor chains
  COMPLETED_PROCESSORS=$($etcdctl_cmd get /$PREFIX/workflow/targets/$TARGET_PATH/finishedSubFlows/ --prefix --keys-only | wc -l)
  $etcdctl_cmd put /$STATS_KEY/targets/$TARGET_PATH/completed_processors "$COMPLETED_PROCESSORS"

  # Save the list of processor chain names
  PROCESSOR_CHAINS=$($etcdctl_cmd get /$PREFIX/workflow/targets/$TARGET_PATH/finishedSubFlows/ --prefix --keys-only | sort | tr '\n' ',' | sed 's/,$//')
  $etcdctl_cmd put /$STATS_KEY/targets/$TARGET_PATH/processor_chains "$PROCESSOR_CHAINS"

  echo "- Target $TARGET_ENDPOINT: Latch=$LATCH_VALUE, Completed Processors=$COMPLETED_PROCESSORS"
done

# Delete all workflow keys for this run (but keep the stats)
echo "Deleting all workflow keys with prefix: /$PREFIX/workflow/"
$etcdctl_cmd del /$PREFIX/workflow/ --prefix

echo "Cleanup complete. Workflow stats preserved under /$STATS_KEY/"