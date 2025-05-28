# Building Images Locally
This guide walks through setting up a local docker registry container that can be used to store images for this project, as opposed to the local Docker daemon storage system. The advantage of this approach is that these images can be used anywhere locally that can reference the local docker registry endpoint, including within a Kubernetes Minikube environment.  Additionally, a buildkit container can be setup to enable building the images within this container.

### Create a docker network
A docker network is created so that the docker registry and buildkit containers can communicate with each other via their container name e.g. `http://docker-registry:5000`
```shell
docker network create local-migrations-network
```

### Create a docker registry container and volume
This container will act as a docker registry with its own volume for storing images. It will restart whenever docker is restarted, and should ideally be run once and forgotten about.
```shell
docker run -d --name docker-registry --network local-migrations-network -p 5000:5000 -v registry-data:/var/lib/docker-registry-data --restart=always registry:2
```

### Create a buildkit container
This container will run a buildkit daemon for building the images within this container. It will restart whenever docker is restarted, and should ideally be run once and forgotten about.
**Note**: This command references the `buildkitd.toml` file in this directory to allow an insecure registry for our previous docker registry container
```shell
docker run -d --privileged --name buildkitd --network local-migrations-network -p 1234:1234 -v ./buildkitd.toml:/etc/buildkit/buildkitd.toml --restart=always moby/buildkit:latest --addr tcp://0.0.0.0:1234 --config /etc/buildkit/buildkitd.toml
```

### Create a docker buildx builder that uses the buildkit container
This builder can be specified with any `docker buildx` command, and will then use the buildkit container for building images
```shell
docker buildx create --name local-remote-builder --driver remote tcp://localhost:1234
```

### Build images to registry with gradle command
The following gradle command can be used after setting up the previous steps to build images to the docker registry created
```shell
./gradlew buildImagesToRegistry
```