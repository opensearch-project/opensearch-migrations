#!/bin/bash

set -eo pipefail

INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"
minikube start \
    --extra-config=kubelet.authentication-token-webhook=true \
    --extra-config=kubelet.authorization-mode=Webhook \
    --extra-config=scheduler.bind-address=0.0.0.0 \
    --extra-config=controller-manager.bind-address=0.0.0.0 \
    --insecure-registry="${INSECURE_REGISTRY_CIDR}" \
    --insecure-registry="localhost:5000" \
    --insecure-registry="host.docker.internal:5000" \
    --cpus=6 \
    --memory=15958 \
    --disk-size=60000mb

helm install buildkit ../deployment/k8s/charts/components/buildImages \
  --create-namespace \
  -n buildkit \
  --set skipBuildJob=true \
  --set namespace=buildkit \
  --set deployBuildkitPods=true \
  --set keepJobAlive=false

kubectl apply -f docker-registry.yaml -n buildkit
kubectl get pods -n buildkit

echo "Wait for both pods to be Running"
kubectl wait --for=condition=ready pod -l app=buildkitd -n buildkit --timeout=120s
kubectl wait --for=condition=ready pod -l app=docker-registry -n buildkit --timeout=60s

echo "Port forward BuildKit & Docker Registry (run in background)"
nohup kubectl port-forward -n buildkit svc/buildkitd 1234:1234 > /tmp/buildkit-forward.log 2>&1 &
nohup kubectl port-forward -n buildkit svc/docker-registry 5001:5000 > /tmp/registry-forward.log 2>&1 &

sleep 2s

echo "Test registry (should return empty repositories list)"
curl http://localhost:5001/v2/_catalog
echo "^^ Expected output: {"repositories":[]}"

echo "Test BuildKit (will return HTTP/0.9 error - this is expected for gRPC services)"
curl http://localhost:1234 || true
echo "^^ Expected output: curl: (1) Received HTTP/0.9 when not allowed"

echo "Remove any existing builder with this name"
docker buildx rm builder-minikube 2>/dev/null || true

# see backends/k8sHostedBuildkit.sh to resolve
echo "Create the builder pointing to localhost:1234 (port-forwarded to the BuildKit service)"
docker buildx create --name builder-minikube --driver remote tcp://localhost:1234

echo "Set it as the active builder"
docker buildx use builder-minikube

echo "Bootstrap and verify connection"
docker buildx inspect --bootstrap

# see build.gradle. The buildKitTestAll_* carries the testBuildKitProjects to load the needed repos
PLATFORM=$(uname -m)
../gradlew "buildImagesToRegistry_${PLATFORM}" "buildKitTestAll_${PLATFORM}"

curl http://localhost:5001/v2/_catalog

# Installing helm resources
echo "Installing the needed helm charts"
# installs kyverno, argo, argo-workflowßcontroller, cert-manager, fluent, jaeger, localhost, ma-helm-installer, strimzi-cluster-operator
# kube-prometheus, kube-grafana, otel service manager, s3 bucket (simulated with localstack)
helm install ma ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo -n ma --create-namespace --wait --timeout 20m -f ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml
