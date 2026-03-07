#!/bin/bash
# Test the snapshot-fuse FUSE sidecar in minikube.
# Prerequisites: minikube running, kubectl configured, Docker available.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FUSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$FUSE_DIR/.." && pwd)"

echo "=== Building snapshot-fuse Docker image ==="
cd "$FUSE_DIR"
eval $(minikube docker-env 2>/dev/null) || true
docker build -t snapshot-fuse:latest .

echo "=== Verifying image ==="
docker run --rm snapshot-fuse:latest --help 2>&1 | head -5

echo ""
echo "=== snapshot-fuse image built successfully ==="
echo ""
echo "To deploy as a sidecar, patch the RFS deployment with:"
echo "  kubectl patch deployment <rfs-deployment> --patch-file $SCRIPT_DIR/fuse-sidecar-patch.yaml"
echo ""
echo "Or apply the standalone test pod:"
echo "  kubectl apply -f $SCRIPT_DIR/test-pod.yaml"
