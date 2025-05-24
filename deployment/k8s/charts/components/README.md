
docker network create local-migrations-network
docker run -d --name docker-registry --network local-migrations-network -p 5000:5000 -v registry-data:/var/lib/docker-registry-data --restart=always registry:2
docker run -d --privileged --name buildkitd --network local-migrations-network -p 1234:1234 -v ./buildkitd.toml:/etc/buildkit/buildkitd.toml --restart=always moby/buildkit:latest --addr tcp://0.0.0.0:1234 --config /etc/buildkit/buildkitd.toml

docker buildx create --name local-remote-builder --driver remote tcp://localhost:1234