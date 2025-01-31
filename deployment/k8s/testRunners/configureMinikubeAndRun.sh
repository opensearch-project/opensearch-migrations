#!/bin/sh

if [ -z "$MINIKUBE_ACTIVE_DOCKERD" ]; then
  minikube start
  eval $(minikube docker-env)
  ../../cdk/opensearch-service-migration/buildDockerImages.sh

  kubectl create namespace ma
fi

"$@"