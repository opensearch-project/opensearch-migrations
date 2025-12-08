# Building Images Locally with Minikube

This guide walks through building container images for local Minikube development using the built-in registry addon.

## Prerequisites

- Minikube installed
- Docker CLI installed
- Gradle installed

## Quick Start

### 1. Start Minikube with Registry

```bash
cd deployment/k8s
./minikubeLocal.sh --start
```

This automatically:
- Starts minikube with insecure registry support
- Enables the registry addon
- Mounts the project directory

### 2. Build and Push Images

```bash
cd deployment/k8s
./buildDockerImagesMini.sh
```

This will:
- Build all Docker images using Gradle
- Automatically detect the minikube registry port
- Tag and push images to the minikube registry

### 3. Verify Images

Check that images were pushed:
```bash
REGISTRY_PORT=$(docker port minikube 5000 | cut -d: -f2)
curl http://localhost:$REGISTRY_PORT/v2/_catalog
```

## Using Images in Minikube

Images pushed to the minikube registry are available inside the cluster at `localhost:5000`. For example:
```yaml
image: localhost:5000/migrations/migration_console:latest
```

## Syncing to ECR (Optional)

To push images to AWS ECR after building:
```bash
./buildDockerImagesMini.sh --sync-ecr
```

## Cleanup

```bash
./minikubeLocal.sh --delete
```

## Alternative: BuildKit Approach

For a setup that mirrors the aws-bootstrap.sh build process more closely (using BuildKit in a pod), see [README.md](README.md).
