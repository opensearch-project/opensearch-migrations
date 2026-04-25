# Building Images Locally
This guide walks through the local image-build options for this repo. Image-build orchestration lives under [backends](backends), with two supported modes:

- `k8sHostedBuildkit.sh`: run BuildKit and the registry in Kubernetes
- `dockerHostedBuildkit.sh`: run BuildKit and the registry as Docker containers on the host

The deployment scripts source the backend they need directly. This README describes the Docker-hosted path; for the Kubernetes-hosted path, see [README-K8s.md](README-K8s.md).

Using a registry instead of Docker's local image store lets the same built images be consumed by local Kubernetes clusters and by other tooling that can reach the registry endpoint. It also keeps the path open for pushing to remote registries such as AWS ECR.

## Using Docker (no Kubernetes/Minikube required to build)

This is the same model used by `deployment/k8s/kindTesting.sh`, which sources `buildImages/backends/dockerHostedBuildkit.sh`. The `kind` workflow defaults to `localhost:5002` for the host-facing registry port so it can coexist with Minikube's `localhost:5001`.

### Create a docker network
A docker network is created so that the docker registry and buildkit containers can communicate with each other via their container name e.g. `http://docker-registry:5000`. This step should only be needed once, unless the network gets deleted.
```shell
docker network create local-migrations-network
```

### Create a docker registry container and volume
This container will act as a docker registry with its own volume for storing images. It will restart whenever docker is restarted, and should ideally be run once and forgotten about.
```shell
docker run -d --name docker-registry --network local-migrations-network -p 5002:5000 -v registry-data:/var/lib/registry --restart=always registry:2
```

### Create a buildkit container
This container will run a buildkit daemon for building the images within this container. It will restart whenever docker is restarted, and should ideally be run once and forgotten about.
**Note**: This command references the `buildkitd.toml` file in this directory to allow an insecure local registry.
```shell
docker run -d --privileged --name buildkitd --network local-migrations-network -p 1234:1234 -v ./buildkitd.toml:/etc/buildkit/buildkitd.toml --restart=always moby/buildkit:latest --addr tcp://0.0.0.0:1234 --config /etc/buildkit/buildkitd.toml
```

### Create a docker buildx builder that uses the buildkit container
This builder can be specified with any `docker buildx` command, and will then use the buildkit container for building images
```shell
docker buildx create --name local-remote-builder --driver remote tcp://localhost:1234
```

### Build images to registry with gradle command
The following Gradle command builds images to the docker registry created above:
```shell
./gradlew buildImagesToRegistry -PregistryEndpoint=localhost:5002
```

This aggregate task now uses `docker buildx bake` for the BuildKit-managed
images, so independent Docker image builds run concurrently while preserving
the required dependency ordering between images that build on top of each
other.

Or customized to use a specific registry endpoint
```shell
./gradlew buildImagesToRegistry -PregistryEndpoint=123456789012.dkr.ecr.us-east-2.amazonaws.com/my-ecr-repo
```

## Using Minikube or EKS (using the Kubernetes-hosted BuildKit backend)

See [README-K8s.md](README-K8s.md).
