#!/bin/bash

set -eo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-ma}"
KIND_NODE="${CLUSTER_NAME}-control-plane"

HOST_IP_FROM_WITHIN_KIND=$(docker network inspect kind -f '{{range .IPAM.Config}}{{.Gateway}}{{"\n"}}{{end}}' | grep -v ':')
export imageRegistryPrefix="${HOST_IP_FROM_WITHIN_KIND}:5001/"

prune_kind_images() {
    local pattern=$1
    local matches

    # kind nodes run containerd, inspected via crictl (not docker) since
    # that's what actually manages the CRI-side image cache the kubelet uses
    matches=$(docker exec "${KIND_NODE}" crictl images -o json \
        | jq -r '.images[] | .repoTags[]?' \
        | grep -E "$pattern" || true)

    if [ -z "$matches" ]; then
      echo "no images match: $pattern"
      return 0
    fi

    echo "Removing:"
    echo "$matches" | sed 's/^/  /'
    echo "$matches" | xargs -n1 docker exec "${KIND_NODE}" crictl rmi
}

echo "Stopping migration-console so that we can remove respective image from cache"
kubectl scale statefulset/migration-console -n ma --replicas=0
kubectl wait --for=delete pod -l app=migration-console -n ma --timeout=180s

echo "Delete all stopped containers and dangling/unused images"
# the kind node itself is a docker container; docker prune runs one level up,
# against the host's docker daemon, cleaning up build/exited containers there
docker container prune -f
# separately clean containerd's own dangling image layers inside the node
docker exec "${KIND_NODE}" crictl rmi --prune

echo "Removing images cached at kind node level"
prune_kind_images 'migration_console'

echo "Scaling up again"
kubectl scale statefulset/migration-console -n ma --replicas=1

echo "Applying update"
helm upgrade ma ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo \
    -n ma \
    --wait \
    --timeout 20m \
    -f <(envsubst < ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8sWithEnvSubst.yaml)
