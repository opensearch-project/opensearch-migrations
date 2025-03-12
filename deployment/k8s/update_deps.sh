#!/bin/bash

# Operates by calculating a hash of the files and their content within a given chart directory while excluding the
# generated charts/ directory that 'helm dependency update' generates. Several events can trigger a chart to update
# with this script including: no existing hash in HASH_FILE, hash change, dependent chart change (including library chart),
# no charts/ directory for charts that require this.

HASH_FILE="./.chart_hashes"
TEMP_FILE="./chart_hashes_tmp"
UPDATED_CHARTS=()

# List of specific Helm chart directories to track
COMPONENT_CHART_DIRS=(
    "charts/components/bulkLoad"
    "charts/components/captureProxy"
    "charts/components/migrationConsole"
    "charts/components/replayer"
    # Storing this here for now
    "charts/sharedResources/baseKafkaCluster"
    "charts/sharedResources/sharedConfigs"
)

helm_update_with_hash() {
    chart=$1
    chart_name=$2
    skip_charts_check=$3
    must_update=$4

    if [[ ! -d "$chart" ]]; then
        echo "Warning: Directory $chart does not exist. Skipping..."
        return
    fi
    if [[ "$skip_charts_check" != "true" && ! -d "$chart/charts" ]]; then
        echo "The generated $chart/charts directory does not exist. Running Helm dependency update..."
        helm dependency update "$chart"
        UPDATED_CHARTS+=("$chart_name")
        return
    fi

    #echo "Chart: $chart"
    # Compute hash on files in given chart dir but exclude generated charts dir from calculation
    hash_value=$(find "$chart" -path "$chart/charts" -prune -o -type f -print0 | sort -z | xargs -0 sha1sum | sha1sum)
    #echo "New hash: $hash_value"
    echo "$chart_name $hash_value" >> "$TEMP_FILE"

    # Check if the chart exists in the previous hash file
    prev_hash=$(grep "^$chart_name " "$HASH_FILE" | awk '{print $2 "  " $3}')
    #echo "Old hash: $prev_hash"

    if [[ "$must_update" == "true" || "$prev_hash" != "$hash_value" ]]; then
        echo "Changes detected in $chart_name. Running Helm dependency update..."
        helm dependency update "$chart"
        UPDATED_CHARTS+=("$chart_name")
    fi
}

update_directories () {
  must_update="$1"
  shift
  dirs=("$@")
  # Iterate all charts in array
  for chart in "${dirs[@]}"; do
      chart_name=$(basename "$chart")
      helm_update_with_hash "$chart" "$chart_name" "false" "$must_update"
  done
}

START_TIME=$(date +%s)

# Create hash file if it doesn't exist, create or clear temp file
touch "$HASH_FILE"
echo -n > "$TEMP_FILE"

must_update="false"
helm_update_with_hash "charts/sharedResources/helmCommon" "helmCommon" "true" "$must_update"
# If a library chart has updated we should propagate everywhere
if [ ${#UPDATED_CHARTS[@]} -gt 0 ]; then
  must_update="true"
fi

update_directories "$must_update" "${COMPONENT_CHART_DIRS[@]}"
# These charts do not depend on any charts or libraries and are only used by the MA chart
helm_update_with_hash "charts/sharedResources/logsVolume" "logsVolume" "true" "false"
helm_update_with_hash "charts/sharedResources/snapshotVolume" "snapshotVolume" "true" "false"
# If a component chart has updated we should ensure migration assistant is updated
if [ ${#UPDATED_CHARTS[@]} -gt 0 ]; then
  must_update="true"
fi

helm_update_with_hash "charts/aggregates/migrationAssistant" "migrationAssistant" "false" "$must_update"
helm_update_with_hash "charts/aggregates/testClusters" "testClusters" "false" "false"
helm_update_with_hash "charts/components/elasticsearchCluster" "elasticsearchCluster" "false" "false"
helm_update_with_hash "charts/components/opensearchCluster" "opensearchCluster" "false" "false"

# Update stored hashes
mv "$TEMP_FILE" "$HASH_FILE"

if [ ${#UPDATED_CHARTS[@]} -gt 0 ]; then
  printf -v joined '%s,' "${UPDATED_CHARTS[@]}"
  echo "The following charts were updated: ${joined%,}"
fi

END_TIME=$(date +%s)
EXECUTION_TIME=$((END_TIME - START_TIME))

echo "Execution Time: $EXECUTION_TIME seconds"
