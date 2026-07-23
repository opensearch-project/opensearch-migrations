#!/bin/bash

set -eo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-ma}"

if kind get clusters 2>/dev/null | grep -qx "${CLUSTER_NAME}"; then
    echo "kind cluster already running"
else
  INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"

  # kind nodes are docker containers on the "kind" docker network. That
  # network's gateway IP is how a node reaches the host.
  # The "kind" network only exists once a cluster has been created, so we
  # do a throwaway create/delete pass first, same as the old minikube dance.
  kind create cluster --name "${CLUSTER_NAME}"
  HOST_IP_FROM_WITHIN_KIND=$(docker network inspect kind -f '{{range .IPAM.Config}}{{.Gateway}}{{"\n"}}{{end}}' | grep -v ':')
  export HOST_IP_FROM_WITHIN_KIND
  kind delete cluster --name "${CLUSTER_NAME}"

  KIND_CONFIG=$(mktemp)
  cat > "${KIND_CONFIG}" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
    - role: control-plane
    - role: worker
    - role: worker
containerdConfigPatches:
  - |-
    [plugins."io.containerd.grpc.v1.cri".registry.mirrors."${HOST_IP_FROM_WITHIN_KIND}:5001"]
      endpoint = ["http://${HOST_IP_FROM_WITHIN_KIND}:5001"]
    [plugins."io.containerd.grpc.v1.cri".registry.configs."${HOST_IP_FROM_WITHIN_KIND}:5001".tls]
      insecure_skip_verify = true
EOF

  kind create cluster --config "${KIND_CONFIG}" --name "${CLUSTER_NAME}"
  rm -f "${KIND_CONFIG}"

  echo "Installing metrics-server (kind has no addon system, so apply the manifest directly)"
  kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
  # kind's kubelet serving cert isn't valid for the node's docker IP, so
  # metrics-server needs --kubelet-insecure-tls or it CrashLoopBackOffs
  kubectl patch deployment metrics-server -n kube-system --type='json' \
      -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi
