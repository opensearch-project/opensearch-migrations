#!/bin/sh

ORIGINAL_DIR=$(pwd)
cd "$(dirname "$0")/../" || exit

helm install -n ma tc charts/aggregates/testClusters
if [ $? -eq 0 ]; then
  echo "installed testClusters in 'ma' namespace"
else
  echo Rebuilding dependency
  helm dependency build charts/aggregates/testClusters
  helm install -n ma tc charts/aggregates/testClusters
fi

helm install -n ma ma charts/aggregates/migrationAssistant
if [ $? -eq 0 ]; then
  echo "installed testClusters in 'ma' namespace"
else
  echo Rebuilding dependency
  helm dependency build charts/aggregates/migrationAssistant
  helm install -n ma ma charts/aggregates/migrationAssistant
fi

helm install -n ma ma charts/tests/testConsole
if [ $? -eq 0 ]; then
  echo "installed testConsole in 'ma' namespace"
else
  echo Rebuilding dependency
  helm dependency build charts/tests/testConsole
  helm install -n ma ma charts/tests/testConsole
fi

cd $ORIGINAL_DIR || exit

migration_pod=$(kubectl get pods -n ma -l app=migration-console --field-selector status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
export migration_pod

test_pod=$(kubectl get pods -n ma -l app=test-test-console --field-selector status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
export test_pod

"$@"

# Final cleanup so that future runs have a clean environment
#helm delete -n ma ma
#helm delete -n ma tc
