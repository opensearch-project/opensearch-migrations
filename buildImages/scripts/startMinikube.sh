#!/bin/bash

set -eo pipefail

if minikube status >/dev/null 2>&1; then
    echo "minikube already running"
    export HOST_IP_FROM_WITHIN_MINIKUBE=$(minikube ssh -- ip route 2>/dev/null | awk '/default/ {print $3}' | tr -d '\r')
else
  INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"

  # we startup minikube once to see under which IP the host appears cause we need to access the host's registry
  minikube start
  export HOST_IP_FROM_WITHIN_MINIKUBE=$(minikube ssh -- ip route 2>/dev/null | awk '/default/ {print $3}' | tr -d '\r')
  minikube stop
  minikube delete

  # NOTE: the disk-size is set high since one of the the
  # workflow steps asks for 200GB
  # NOTE: host.minikube.internal only resolves to host for docker context. For colima we need
  # to use ip returned from `GW=$(minikube ssh -- ip route 2>/dev/null | awk '/default/ {print $3}' | tr -d '\r')`
  # and add this to insecure-registry setting, and set this as prefix for all repo access needed from host
  minikube start \
      --extra-config=kubelet.authentication-token-webhook=true \
      --extra-config=kubelet.authorization-mode=Webhook \
      --extra-config=scheduler.bind-address=0.0.0.0 \
      --extra-config=controller-manager.bind-address=0.0.0.0 \
      --insecure-registry="${INSECURE_REGISTRY_CIDR}" \
      --insecure-registry="${HOST_IP_FROM_WITHIN_MINIKUBE}:5001" \
      --cpus=6 \
      --memory=18gb \
      --disk-size=300gb

  echo "Enabling metrics-server addon"
  minikube addons enable metrics-server
fi