#!/bin/bash

set -eo pipefail

HOST_IP_FROM_WITHIN_MINIKUBE=$(minikube ssh -- ip route 2>/dev/null | awk '/default/ {print $3}' | tr -d '\r')
export imageRegistryPrefix="${HOST_IP_FROM_WITHIN_MINIKUBE}:5001/"


prune_minikube_images() {
    local pattern=$1
    local matches
    matches=$(minikube image ls | grep -E "$pattern" || true)

    if [ -z "$matches" ]; then
      echo "no images match: $pattern"
      return 0
    fi

    echo "Removing:"
    echo "$matches" | sed 's/^/  /'
    echo "$matches" | xargs -n1 minikube image rm
  }

echo "Stopping migration-console so that we can remove respective image from cache"
kubectl scale statefulset/migration-console -n ma --replicas=0
kubectl wait --for=delete pod -l app=migration-console -n ma --timeout=180s

echo "Delete all stopped containers and dangling/unused images"
minikube ssh -- docker container prune -f

echo "Removing images cached at minikube level"
prune_minikube_images 'migration_console'

echo "Scaling up again"
kubectl scale statefulset/migration-console -n ma --replicas=1

echo "Applying update"
helm upgrade ma ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo \
    -n ma \
    --wait \
    --timeout 20m \
    -f <(envsubst < ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8sWithEnvSubst.yaml)
