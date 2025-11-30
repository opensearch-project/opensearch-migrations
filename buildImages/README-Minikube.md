# Building Images Locally with Minikube

This guide walks through setting up BuildKit and a local Docker registry in Minikube to build and store container images for this project. This approach provides consistent DNS resolution, proper networking, and uses the same BuildKit configuration as what's provided to customer's that need to build images during a deployment.

See [README.md](README.md) for instructions that do NOT require minikube or any Kubernetes environment. 

## Prerequisites

- Minikube is running & kubectl is configured to use it
- Docker CLI installed (for buildx)
- Gradle installed
- Helm 3 installed

## Setup Instructions

### 1. Start Minikube

If not already running:
```bash
minikube start
```

### 2. Deploy BuildKit using the helm chart

This deploys the BuildKit pod and service (but skips the build job since we'll be running Gradle locally):
```bash
helm install buildkit ../deployment/k8s/charts/components/buildImages \
  --create-namespace \
  -n buildkit \
  --set skipBuildJob=true \
  --set namespace=buildkit \
  --set serviceAccountName=default
```

### 3. Deploy a local Docker registry

The production chart doesn't include a registry (since it uses ECR/external registries). For local development, we need to deploy one separately.

Deploy it:
```bash
kubectl apply -f docker-registry.yaml -n buildkit
```

### 4. Verify pods are running
```bash
kubectl get pods -n buildkit

# Wait for both pods to be Running
kubectl wait --for=condition=ready pod -l app=buildkitd -n buildkit --timeout=120s
kubectl wait --for=condition=ready pod -l app=docker-registry -n buildkit --timeout=60s
```

Expected output:
```
NAME                               READY   STATUS    RESTARTS   AGE
buildkitd                          1/1     Running   0          30s
docker-registry-xxxxxxxxxx-xxxxx   1/1     Running   0          20s
```

### 5. Set up port forwarding

Port forward both services to your local machine. You can run these in separate terminals or background them:
```bash
# Port forward BuildKit (run in background)
nohup kubectl port-forward -n buildkit svc/buildkitd 1234:1234 > /tmp/buildkit-forward.log 2>&1 &

# Port forward Docker registry (run in background)
nohup kubectl port-forward -n buildkit svc/docker-registry 5001:5000 > /tmp/registry-forward.log 2>&1 &
```

**Important**: Keep these port-forward processes running while building images. If they terminate, your builds will fail with connection errors.

### 6. Verify connectivity

Test that both services are accessible from your host:
```bash
# Test registry (should return empty repositories list)
curl http://localhost:5001/v2/_catalog
# Expected output: {"repositories":[]}

# Test BuildKit (will return HTTP/0.9 error - this is expected for gRPC services)
curl http://localhost:1234
# Expected output: curl: (1) Received HTTP/0.9 when not allowed
```

If either command times out or fails to connect, the port-forward may not be running. Check with:
```bash
ps aux | grep port-forward
```

### 7. Create Docker buildx builder

Create a builder that connects to the BuildKit service in Minikube:
```bash
# Remove any existing builder with this name
docker buildx rm local-remote-builder 2>/dev/null || true

# Create the builder pointing to localhost:1234 (port-forwarded to Minikube BuildKit)
docker buildx create --name local-remote-builder --driver remote tcp://localhost:1234

# Set it as the active builder
docker buildx use local-remote-builder

# Bootstrap and verify connection
docker buildx inspect --bootstrap
```

You should see output similar to:
```
Name:          local-remote-builder
Driver:        remote
Status:        running
Endpoint:      tcp://localhost:1234
BuildKit version: v0.22.0
```

The key indicators of success:
- Status: **running**
- Endpoint: **tcp://localhost:1234**
- "Handling connection for 1234" appears in your terminal (from port-forward)

### 8. Build images

Now you can build images using Gradle from your host machine:
```bash
./gradlew buildImagesToRegistry
```

This will:
- Use Jib to build Java-based images and push to `localhost:5001` (your local registry)
- Use BuildKit (running in Minikube) to build Dockerfile-based images and push to `docker-registry:5000` (the Kubernetes service name)

**Note**: The first build may take several minutes as base images are pulled and layers are built.

### 9. Verify images were pushed

Check that images were successfully pushed to your local registry:
```bash
curl http://localhost:5001/v2/_catalog
```

You should see a list of repositories like:
```json
{"repositories":["migrations/traffic_replayer","migrations/capture_proxy_base","migrations/migration_console"]}
```

## Cleaning Up

### Stop port forwards but keep everything running
```bash
# Kill port forward processes
pkill -f "port-forward.*buildkit"
```

### Remove registry but keep BuildKit
```bash
kubectl delete -f docker-registry.yaml
```

### Complete cleanup (removes everything)
```bash
# Remove port forwards
pkill -f "port-forward.*buildkit"

# Uninstall helm chart
helm uninstall buildkit -n buildkit

# Remove registry
kubectl delete -f docker-registry.yaml

# Delete namespace
kubectl delete namespace buildkit

# Remove buildx builder
docker buildx rm local-remote-builder
```

## Using with Remote Registries

To build and push to a remote registry (e.g., AWS ECR) instead of the local registry:
```bash
# Login to your remote registry first
aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-west-2.amazonaws.com

# Build with custom registry endpoint and architecture
./gradlew buildImagesToRegistry \
  -PregistryEndpoint=123456789012.dkr.ecr.us-west-2.amazonaws.com/my-repo \
  -PimageArch=amd64
```

This is how the production build job works - it uses the same BuildKit setup but pushes to ECR instead of a local registry.

## Comparison: Local Development vs Production

| Aspect | Local Development | Production |
|--------|------------------|------------|
| BuildKit | Runs in Minikube (via helm chart) | Runs in EKS/K8s (via helm chart) |
| Registry | Local registry in Minikube | AWS ECR (or other external registry) |
| Build trigger | Manual `./gradlew` on host | Kubernetes Job in cluster |
| Gradle execution | On developer's machine | Inside build job container |
| Port forwarding | Required | Not needed |
| Use case | Fast iteration, local testing | CI/CD, deployments |

## Advanced: Persistent Registry Storage

The current setup uses `emptyDir` for registry storage, which means images are lost when the registry pod restarts. For persistent storage:
```yaml
# In docker-registry.yaml, replace the emptyDir volume with a PVC:
volumes:
  - name: registry-data
    persistentVolumeClaim:
      claimName: registry-pvc
---
# Add a PVC
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: registry-pvc
  namespace: buildkit
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 20Gi
```

This is optional for local development but recommended if you want to avoid rebuilding images after restarting Minikube.