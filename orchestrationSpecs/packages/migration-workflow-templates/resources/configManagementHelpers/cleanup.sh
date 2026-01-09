#!/bin/bash
set -e

# Import Library
source ./etcdClientHelper.sh

# PREFIX env var is set by the workflow template
echo "===== CLEANING UP ETCD KEYS FOR PREFIX $PREFIX ====="

# Record workflow completion time
etcd_safe_run put "/$PREFIX/workflow/info/completed" "$(date +%s)"

STARTED=$(etcd_safe_get "/$PREFIX/workflow/info/started")
if [ -z "$STARTED" ]; then STARTED=$(date +%s); fi
COMPLETED=$(date +%s)
DURATION=$((COMPLETED - STARTED))

echo "Workflow completed in $DURATION seconds"

# Get workflow stats for logging purposes
echo "Workflow completion stats:"

# Keep statistics in a separate key that will persist
STATS_KEY="workflow-stats/runs/$PREFIX"

# Save summarized workflow stats to a persistent key
etcd_safe_run put "/$STATS_KEY/started" "$STARTED"
etcd_safe_run put "/$STATS_KEY/completed" "$COMPLETED"
etcd_safe_run put "/$STATS_KEY/duration" "$DURATION"

# For each target, save its finalized status and completed processors
TARGET_KEYS=$(etcd_safe_get_keys "/$PREFIX/workflow/targets/" | grep "/latch$" | sort)
for TARGET_KEY in $TARGET_KEYS; do
  TARGET_PATH=$(echo "$TARGET_KEY" | sed "s|/$PREFIX/workflow/targets/||" | sed "s|/latch$||")
  TARGET_ENDPOINT=$(etcd_safe_get "/$PREFIX/workflow/targets/$TARGET_PATH/endpoint")
  LATCH_VALUE=$(etcd_safe_get "$TARGET_KEY")

  # Save target stats
  etcd_safe_run put "/$STATS_KEY/targets/$TARGET_PATH/endpoint" "$TARGET_ENDPOINT"
  etcd_safe_run put "/$STATS_KEY/targets/$TARGET_PATH/final_latch_value" "$LATCH_VALUE"

  # Save the list of completed processor chains
  RAW_PROCESSOR_KEYS=$(etcd_safe_get_keys "/$PREFIX/workflow/targets/$TARGET_PATH/finishedSubFlows/")

  # Count Processors
  if [ -z "$RAW_PROCESSOR_KEYS" ]; then
    COMPLETED_PROCESSORS_COUNT=0
    PROCESSOR_CHAINS=""
  else
    COMPLETED_PROCESSORS_COUNT=$(echo "$RAW_PROCESSOR_KEYS" | wc -l)
    PROCESSOR_CHAINS=$(echo "$RAW_PROCESSOR_KEYS" | sort | tr '\n' ',' | sed 's/,$//')
  fi

  etcd_safe_run put "/$STATS_KEY/targets/$TARGET_PATH/completed_processors" "$COMPLETED_PROCESSORS_COUNT"
  etcd_safe_run put "/$STATS_KEY/targets/$TARGET_PATH/processor_chains" "$PROCESSOR_CHAINS"

  echo "- Target $TARGET_ENDPOINT: Latch=$LATCH_VALUE, Completed Processors=$COMPLETED_PROCESSORS_COUNT"
done

# Delete all workflow keys for this run (but keep the stats)
echo "Deleting all workflow keys with prefix: /$PREFIX/workflow/"
etcd_safe_run del "/$PREFIX/workflow/" --prefix

echo "Cleanup complete. Workflow stats preserved under /$STATS_KEY/"