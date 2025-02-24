#!/bin/bash

START_TIME=$(date +%s)

helm dependency update charts/sharedResources/baseKafkaCluster
helm dependency update charts/components/bulkLoad
helm dependency update charts/components/captureProxy
helm dependency update charts/components/migrationConsole
helm dependency update charts/components/replayer

helm dependency update charts/aggregates/migrationAssistant
helm dependency update charts/aggregates/mockCustomerClusters

END_TIME=$(date +%s)
EXECUTION_TIME=$((END_TIME - START_TIME))

echo "Execution Time: $EXECUTION_TIME seconds"