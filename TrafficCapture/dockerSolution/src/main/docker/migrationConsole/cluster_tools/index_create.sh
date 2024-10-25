#!/bin/bash

# Variables passed as arguments
INDEX_NAME=$1
TARGET_SHARDS=$2

# Check if both arguments are provided
if [ -z "$INDEX_NAME" ] || [ -z "$TARGET_SHARDS" ]; then
    echo "Usage: $0 <index_name> <number_of_primary_shards>"
    exit 1
fi

# Create index with the specified name and shard count using default settings
echo "Creating index: $INDEX_NAME with $TARGET_SHARDS primary shards"
console clusters curl -XPUT target_cluster /$INDEX_NAME -H 'Content-Type: application/json' -d "{
  \"settings\": {
    \"number_of_shards\": $TARGET_SHARDS,
    \"number_of_replicas\": 1
  }
}"

if [ $? -eq 0 ]; then
    echo "Index $INDEX_NAME created successfully."
else
    echo "Failed to create index $INDEX_NAME."
    exit 1
fi