SOURCE_CONFIG=$(echo "$CONFIGURATION")
TARGETS_CONFIG=$(echo "$TARGETS")
echo "source config = $SOURCE_CONFIG"
echo "targets config = $TARGETS_CONFIG"

# Function to normalize endpoint for use in etcd keys
# Keeps protocol and port, but normalizes slashes
normalize_endpoint() {
  echo "$1" | base64
}

# Check for duplicate source endpoints
echo "Checking for duplicate source endpoints..."
SOURCE_ENDPOINTS=$(echo "$SOURCE_CONFIG" | jq -r '[.[] | .source.endpoint] | unique | length')
TOTAL_SOURCES=$(echo "$SOURCE_CONFIG" | jq -r 'length')

if [ "$SOURCE_ENDPOINTS" -ne "$TOTAL_SOURCES" ]; then
  echo "Error: Duplicate source endpoints detected" >&2
  echo "$SOURCE_CONFIG" | jq -r '.[] | .source.endpoint' | sort | uniq -d >&2
  exit 1
fi

# Check for duplicate target endpoints
echo "Checking for duplicate target endpoints..."
TARGET_ENDPOINTS=$(echo "$TARGETS_CONFIG" | jq -r '[.[] | .endpoint] | unique | length')
TOTAL_TARGETS=$(echo "$TARGETS_CONFIG" | jq -r 'length')

if [ "$TARGET_ENDPOINTS" -ne "$TOTAL_TARGETS" ]; then
  echo "Error: Duplicate target endpoints detected" >&2
  echo "$TARGETS_CONFIG" | jq -r '.[] | .endpoint' | sort | uniq -d >&2
  exit 1
fi

# Check for duplicate snapshot/metadata/backfill configurations within each source
echo "Checking for duplicate configurations..."
echo "$SOURCE_CONFIG" | jq -c '.[] | .["snapshot-and-migration-configs"]' | while read -r configs; do
  # Check for duplicate indices in snapshot configs
  UNIQUE_INDICES=$(echo "$configs" | jq -r '[.[] | .indices | sort | join(",")] | unique | length')
  TOTAL_CONFIGS=$(echo "$configs" | jq -r 'length')

  if [ "$UNIQUE_INDICES" -ne "$TOTAL_CONFIGS" ]; then
    echo "Error: Duplicate snapshot configurations detected for the same indices" >&2
    exit 1
  fi

  # Check for duplicate migrations within each snapshot config
  echo "$configs" | jq -c '.[] | .migrations' | while read -r migrations; do
    UNIQUE_MIGRATIONS=$(echo "$migrations" | jq -r 'map(@json) | unique | length')
    TOTAL_MIGRATIONS=$(echo "$migrations" | jq -r 'length')

    if [ "$UNIQUE_MIGRATIONS" -ne "$TOTAL_MIGRATIONS" ]; then
      echo "Error: Duplicate migration configurations detected" >&2
      exit 1
    fi
  done
done

# Calculate the total number of processors
# Count the total number of migrations across all sources and snapshot configs
PROCESSOR_COUNT=$(echo "$SOURCE_CONFIG" | jq -r '[.[] | .["snapshot-and-migration-configs"][] | .migrations | length] | add')
echo "Total processor count: $PROCESSOR_COUNT"

echo "$PREFIX" > /tmp/prefix
export ETCDCTL_API=3

# Run etcdctl with configured endpoints and authentication
etcdctl_cmd="etcdctl --endpoints=$ETCD_ENDPOINTS --user $ETCD_USER:$ETCD_PASSWORD"

# Store the workflow prefix in etcd for future reference
$etcdctl_cmd put /$PREFIX/workflow/info/prefix "$PREFIX"
$etcdctl_cmd put /$PREFIX/workflow/info/started "$(date +%s)"

# Initialize target latches
echo "$TARGETS_CONFIG" | jq -c '.[]' | while read -r target_json; do
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