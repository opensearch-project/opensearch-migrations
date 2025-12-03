# Building Images Locally with Minikube

This guide walks through setting up BuildKit and a local Docker registry in Minikube to build and store container images for this project. This approach provides consistent DNS resolution, proper networking, and uses the same BuildKit configuration as what's provided to users that need to build images from the aws-bootstrap script.

Pros of this approach:
- The gradle package in this project builds java images (currently only 2) with Jib, which are able to be optimized more, especially to minimize developer rebuild times.
- This lets one test the ability to build images very similar to how a user would do with the aws-bootstrap.sh flag `--build-images true`.
- Users can override the image locations and pull policies to use a local repository and pull images Always to make continuous testing easier.
Cons:
- This is more to configure.
- BuildKit builds and registry pushes/pulls are slower than regular direct docker builds. 

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
INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"
minikube start \
  --insecure-registry="${INSECURE_REGISTRY_CIDR}"
```

### 2. Deploy BuildKit using the helm chart

This deploys the BuildKit pod and service (but skips the build job since we'll be running Gradle locally):
```bash
helm install buildkit ../deployment/k8s/charts/components/buildImages \
  --create-namespace \
  -n buildkit \
  --set skipBuildJob=true \
  --set namespace=buildkit
```

### 3. Deploy a local Docker registry

The aws-bootstrap mechanism doesn't need to worry about a registry since it uses ECR/external registries. For local development, we need to deploy one separately.  It's also possible to use a registry that ships with minikube, though this guide doesn't explore that.

Deploy it:
```bash
kubectl apply -f docker-registry.yaml -n buildkit
```

### 4. Verify pods are running
```bash
kubectl get pods -n buildkit

echo "Wait for both pods to be Running"
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
echo "Port forward BuildKit & Docker Registry (run in background)"
nohup kubectl port-forward -n buildkit svc/buildkitd 1234:1234 --address 0.0.0.0 > /tmp/buildkit-forward.log 2>&1 &
nohup kubectl port-forward -n buildkit svc/docker-registry 5001:5000 --address 0.0.0.0 > /tmp/registry-forward.log 2>&1 &
```

**Important**: Keep these port-forward processes running while building images. If they terminate, your builds will fail with connection errors.

### 6. Verify connectivity

Test that both services are accessible from your host:
```bash
echo "Test registry (should return empty repositories list)"
curl http://localhost:5001/v2/_catalog
echo "^^ Expected output: {"repositories":[]}"

echo "Test BuildKit (will return HTTP/0.9 error - this is expected for gRPC services)"
curl http://localhost:1234
echo "^^ Expected output: curl: (1) Received HTTP/0.9 when not allowed"
```

If either command times out or fails to connect, the port-forward may not be running. Check with:
```bash
ps aux | grep port-forward
```

### 7. Create Docker buildx builder

Create a builder that connects to the BuildKit service in Minikube:
```bash
echo "Remove any existing builder with this name"
docker buildx rm local-remote-builder 2>/dev/null || true

echo "Create the builder pointing to localhost:1234 (port-forwarded to Minikube BuildKit)"
docker buildx create --name local-remote-builder --driver remote tcp://localhost:1234

echo "Set it as the active builder"
docker buildx use local-remote-builder

echo "Bootstrap and verify connection"
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
../gradlew buildImagesToRegistry
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

### 10. Using the registry with your local docker engine

Make sure that docker engine's settings include.
```
{
  "insecure-registries": [
    "localhost:5001",
    "host.docker.internal:5001"
  ]
}
```

To verify those settings, run `docker info`.  The following should be listed.
```
Insecure Registries:
  host.docker.internal:5001
  localhost:5001
  ::1/128
  127.0.0.0/8
```

To pull/run images, run 
```bash
docker pull host.docker.internal:5001/migrations/migration_console:latest
docker run -it --rm host.docker.internal:5001/migrations/migration_console:latest /bin/bash
```

## Cleaning Up

### Stop port forwards but keep everything running
```bash
echo "Kill port forward processes"
pkill -f "port-forward.*buildkit"
```

### Remove registry but keep BuildKit
```bash
kubectl delete -f docker-registry.yaml
```

### Complete cleanup (removes everything)
```bash
echo "Remove port forwards"
pkill -f "port-forward.*buildkit"

echo "Remove registry"
kubectl delete -f docker-registry.yaml

echo "Uninstall helm chart"
helm uninstall buildkit -n buildkit

echo "Delete namespace"
kubectl delete namespace buildkit

echo "Remove buildx builder"
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
