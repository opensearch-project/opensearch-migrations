# syntax=docker/dockerfile:1

# This file expects the context directory to be the TrafficCapture directory and will utilize its gradle configuration.
# To build this image from within this directory, use command: docker build -t traffic-replayer -f Dockerfile ../../

FROM openjdk:17-jdk-slim AS builder

COPY . .

RUN ./gradlew assemble -p trafficReplayer
RUN ./gradlew uberJar -p trafficReplayer


FROM openjdk:17-jdk-slim

ARG TARGET_CLUSTER_ENDPOINT
ENV TARGET_CLUSTER_ENDPOINT ${TARGET_CLUSTER_ENDPOINT}

WORKDIR ./replay-app

RUN apt-get update && apt-get install -y netcat
RUN apt-get install -y expect

COPY --from=builder trafficReplayer/build/libs/*.jar .
COPY trafficReplayer/docker/run_app.sh .

CMD ./run_app.sh ${TARGET_CLUSTER_ENDPOINT}
#CMD ./run_app.sh http://host.docker.internal:9200