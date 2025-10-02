#echo "source clusters = $WF_SETUP_SOURCE_CLUSTERS"
#echo "target clusters = $WF_SETUP_TARGET_CLUSTERS"
#echo "sourceMigrationConfigs = $WF_SETUP_SOURCE_MIGRATION_CONFIGS"
#echo "snapshot repo configs = $WF_SETUP_SNAPSHOT_REPO_CONFIGS"

# Function to normalize endpoint for use in etcd keys
# Keeps protocol and port, but normalizes slashes
normalize_endpoint() {
  echo "$1" | base64
}

# Calculate the total number of processors
# Count the total number of migrations across all sources and snapshot configs
PROCESSOR_COUNT=$(echo "$WF_SETUP_SOURCE_CLUSTERS" | jq -r '[.[] | .["snapshot-and-migration-configs"][] | .migrations | length] | add')
echo "Total processor count: $PROCESSOR_COUNT"

echo "$PREFIX" > /tmp/prefix
export ETCDCTL_API=3

# Run etcdctl with configured endpoints and authentication
etcdctl_cmd="etcdctl --endpoints=$ETCD_ENDPOINTS --user $ETCD_USER:$ETCD_PASSWORD"

# Store the workflow prefix in etcd for future reference
$etcdctl_cmd put /$PREFIX/workflow/info/prefix "$PREFIX"
$etcdctl_cmd put /$PREFIX/workflow/info/started "$(date +%s)"

# Initialize target latches
echo "$WF_SETUP_TARGET_CLUSTERS" | jq -c '.[]' | while read -r target_json; do
  TARGET_ENDPOINT=$(echo "$target_json" | jq -r '.endpoint')
  NORMALIZED_TARGET=$(normalize_endpoint "$TARGET_ENDPOINT")

  # Initialize the latch with processor count
  $etcdctl_cmd put /$PREFIX/workflow/targets/$NORMALIZED_TARGET/endpoint "$TARGET_ENDPOINT"
  $etcdctl_cmd put /$PREFIX/workflow/targets/$NORMALIZED_TARGET/latch "$PROCESSOR_COUNT"

  echo "Target $TARGET_ENDPOINT ($NORMALIZED_TARGET) latch initialized with count $PROCESSOR_COUNT"
done

# Output the processor count per target for workflow output
echo "{\"processor_count\": $PROCESSOR_COUNT}" > /tmp/processors-per-target

echo "Etcd keys initialized with prefix: $PREFIX"