#!/bin/bash

set -eo pipefail

SCRIPT_DIR="$(dirname "$0")"

source "$SCRIPT_DIR"/startKind.sh

echo "Installing the needed helm charts"
export imageRegistryPrefix="${HOST_IP_FROM_WITHIN_KIND}:5001/"
echo "HOST_IP_FROM_WITHIN_KIND: $HOST_IP_FROM_WITHIN_KIND"
# installs kyverno, argo, argo-workflow-controller, cert-manager, fluent, jaeger, localhost, ma-helm-installer, strimzi-cluster-operator
# kube-prometheus, kube-grafana, otel service manager, s3 bucket (e.g localstack)
helm install ma ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo -n ma --create-namespace --wait --timeout 20m \
  -f  <(envsubst < ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8sWithEnvSubst.yaml)
