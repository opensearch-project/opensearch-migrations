#!/bin/bash
# End-to-end test for snapshot-fuse FUSE sidecar with RFS document migration.
# Tests that the FUSE layer correctly presents Lucene files from ES snapshot blobs,
# and that the RFS worker can read them directly (skipping unpack) to migrate documents.
#
# Prerequisites:
#   - minikube running (minikube start --driver=docker)
#   - snapshot-fuse:latest image loaded (minikube image load snapshot-fuse:latest)
#   - reindex-from-snapshot:latest image loaded (minikube image load reindex-from-snapshot:latest)
#   - Test snapshot data copied to minikube node at /data/snapshot
#
# Usage: ./test-e2e-minikube.sh

set -euo pipefail
export PATH="/usr/local/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SNAPSHOT_NAME="global_state_snapshot"
SOURCE_VERSION="ES_7.10"
SNAPSHOT_DATA="${REPO_ROOT}/RFS/test-resources/snapshots/ES_7_10_Single"

cleanup() {
    echo "Cleaning up..."
    kubectl delete pod opensearch-target rfs-fuse-test --force --ignore-not-found 2>/dev/null || true
    kubectl delete svc opensearch-target --ignore-not-found 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Step 1: Copy test snapshot data to minikube ==="
MINIKUBE_CONTAINER=$(docker ps --filter "name=minikube" --format "{{.ID}}")
docker exec "$MINIKUBE_CONTAINER" mkdir -p /data/snapshot
cd "$SNAPSHOT_DATA" && tar cf - . | docker exec -i "$MINIKUBE_CONTAINER" tar xf - -C /data/snapshot/
echo "✅ Snapshot data copied"

echo ""
echo "=== Step 2: Build combined test image ==="
# Build a combined image with both FUSE binary and RFS jars
# (avoids mount propagation issues with minikube Docker driver)
cat > /tmp/Dockerfile.fuse-rfs-test <<'EOF'
FROM docker.io/library/reindex-from-snapshot:latest
RUN dnf install -y fuse fuse-libs && dnf clean all
COPY --from=docker.io/library/snapshot-fuse:latest /usr/local/bin/snapshot-fuse /usr/local/bin/snapshot-fuse
EOF
docker build -t fuse-rfs-test:latest -f /tmp/Dockerfile.fuse-rfs-test . 2>&1 | tail -3
minikube image load fuse-rfs-test:latest
echo "✅ Test image built and loaded"

echo ""
echo "=== Step 3: Deploy OpenSearch target ==="
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: opensearch-target
  labels:
    app: opensearch-target
spec:
  containers:
  - name: opensearch
    image: opensearchproject/opensearch:2.19.0
    ports:
    - containerPort: 9200
    env:
    - name: discovery.type
      value: single-node
    - name: DISABLE_SECURITY_PLUGIN
      value: "true"
    - name: OPENSEARCH_JAVA_OPTS
      value: "-Xms512m -Xmx512m"
    resources:
      requests: { memory: "1Gi", cpu: "500m" }
      limits: { memory: "1Gi", cpu: "1000m" }
    readinessProbe:
      httpGet: { path: /_cluster/health, port: 9200 }
      initialDelaySeconds: 30
      periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: opensearch-target
spec:
  selector:
    app: opensearch-target
  ports:
  - port: 9200
    targetPort: 9200
EOF
kubectl wait --for=condition=ready pod/opensearch-target --timeout=180s
echo "✅ OpenSearch target ready"

echo ""
echo "=== Step 4: Run FUSE + RFS migration ==="
# Note: Uses single-container approach because minikube Docker driver
# doesn't support mount propagation between containers (emptyDir).
# In EKS/production, the sidecar approach with Bidirectional mount propagation works.
kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: rfs-fuse-test
spec:
  containers:
  - name: rfs-worker
    image: docker.io/library/fuse-rfs-test:latest
    imagePullPolicy: Never
    command: ["sh", "-c"]
    args:
    - |
      mkdir -p /mnt/lucene
      RUST_LOG=info snapshot-fuse \\
        --repo-root=/data/snapshot \\
        --snapshot-name=${SNAPSHOT_NAME} \\
        --mount-point=/mnt/lucene &
      sleep 3
      /rfs-app/runJavaWithClasspathWithRepeat.sh \\
        org.opensearch.migrations.RfsMigrateDocuments \\
        --snapshot-name ${SNAPSHOT_NAME} \\
        --snapshot-local-dir /data/snapshot \\
        --lucene-dir /mnt/lucene \\
        --target-host http://opensearch-target:9200 \\
        --source-version ${SOURCE_VERSION}
    securityContext:
      privileged: true
    volumeMounts:
    - name: snapshot-data
      mountPath: /data/snapshot
      readOnly: true
  volumes:
  - name: snapshot-data
    hostPath:
      path: /data/snapshot
      type: Directory
  restartPolicy: Never
EOF

echo "Waiting for migration to complete..."
MAX_WAIT=120
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
    STATUS=$(kubectl get pod rfs-fuse-test -o jsonpath='{.status.phase}' 2>/dev/null || echo "Pending")
    if [ "$STATUS" = "Succeeded" ] || [ "$STATUS" = "Failed" ]; then
        break
    fi
    sleep 5
    ELAPSED=$((ELAPSED + 5))
done

echo ""
echo "=== Step 5: Verify results ==="
PASS=true

# Check posts_2023_02_25
COUNT1=$(kubectl exec opensearch-target -- curl -s "http://localhost:9200/posts_2023_02_25/_count" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['count'])" 2>/dev/null || echo "0")
if [ "$COUNT1" = "1" ]; then
    echo "✅ posts_2023_02_25: $COUNT1 doc (expected 1)"
else
    echo "❌ posts_2023_02_25: $COUNT1 docs (expected 1)"
    PASS=false
fi

# Check posts_2024_01_01
COUNT2=$(kubectl exec opensearch-target -- curl -s "http://localhost:9200/posts_2024_01_01/_count" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['count'])" 2>/dev/null || echo "0")
if [ "$COUNT2" = "3" ]; then
    echo "✅ posts_2024_01_01: $COUNT2 docs (expected 3)"
else
    echo "❌ posts_2024_01_01: $COUNT2 docs (expected 3)"
    PASS=false
fi

# Check that unpack was skipped
SKIP_COUNT=$(kubectl logs rfs-fuse-test 2>/dev/null | grep -c "skipping unpack" || echo "0")
if [ "$SKIP_COUNT" -ge 1 ]; then
    echo "✅ Unpack skipped for $SKIP_COUNT shard(s) (FUSE mount detected)"
else
    echo "❌ Unpack was NOT skipped — hasLuceneFiles() check may not be working"
    PASS=false
fi

echo ""
if [ "$PASS" = true ]; then
    echo "🎉 ALL TESTS PASSED"
    exit 0
else
    echo "💥 SOME TESTS FAILED"
    echo ""
    echo "=== RFS Logs ==="
    kubectl logs rfs-fuse-test 2>&1 | tail -30
    exit 1
fi
