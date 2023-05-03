# syntax=docker/dockerfile:1

FROM openjdk:17-jdk-slim AS builder

RUN apt-get update && apt-get install -y git

# Be weary that this layer can be cached and not capture recent commits. Run docker build --no-cache to avoid this
RUN git clone https://github.com/opensearch-project/opensearch-migrations.git

WORKDIR /opensearch-migrations/TrafficCapture

RUN ./gradlew assemble -p trafficReplayer
RUN ./gradlew uberJar -p trafficReplayer


FROM openjdk:17-jdk-slim

ARG TARGET_CLUSTER_ENDPOINT
ENV TARGET_CLUSTER_ENDPOINT ${TARGET_CLUSTER_ENDPOINT}

WORKDIR ./replay-app

RUN apt-get update && apt-get install -y netcat
RUN apt-get install -y expect

COPY --from=builder /opensearch-migrations/TrafficCapture/trafficReplayer/build/libs/*.jar .
COPY ./run_app.sh .

CMD ./run_app.sh ${TARGET_CLUSTER_ENDPOINT}
#CMD ./run_app.sh http://host.docker.internal:9200