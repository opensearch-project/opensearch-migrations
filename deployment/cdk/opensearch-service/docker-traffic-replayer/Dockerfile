# syntax=docker/dockerfile:1

FROM openjdk:17-jdk-slim AS builder

RUN apt-get update && apt-get install -y git

RUN git clone https://github.com/lewijacn/opensearch-migrations.git && cd opensearch-migrations && git checkout add-gradle-task

WORKDIR /opensearch-migrations/TrafficReplayer
RUN ./gradlew uberJar


FROM openjdk:17-jdk-slim

ARG TARGET_CLUSTER_ENDPOINT
ENV TARGET_CLUSTER_ENDPOINT ${TARGET_CLUSTER_ENDPOINT}

RUN apt-get update && apt-get install -y netcat
RUN apt-get install -y expect

COPY --from=builder /opensearch-migrations/TrafficReplayer/build/libs/*.jar .
COPY ./run_app.sh .

CMD ./run_app.sh ${TARGET_CLUSTER_ENDPOINT}
#CMD ./run_app.sh http://host.docker.internal:9200