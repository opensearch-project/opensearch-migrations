# syntax=docker/dockerfile:1

# This Dockerfile expects the context directory to be the TrafficCapture directory and will utilize its gradle configuration.
# To build this image from within this directory, use command: docker build -t kafka-puller -f Dockerfile ../../

FROM openjdk:17-jdk-slim AS builder

COPY . .

RUN ./gradlew build -p kafkaPrinter

FROM openjdk:17-jdk-slim

ARG KAFKA_BOOTSTRAP_SERVERS
ENV KAFKA_BOOTSTRAP_SERVERS ${KAFKA_BOOTSTRAP_SERVERS}

ARG KAFKA_TOPIC_NAME
ENV KAFKA_TOPIC_NAME ${KAFKA_TOPIC_NAME}

RUN apt-get update && apt-get install -y netcat
RUN apt-get install -y expect

COPY --from=builder kafkaPrinter/build/libs/kafkaPrinter.jar .
COPY --from=builder kafkaPrinter/extracted ./extracted
COPY kafkaPrinter/docker/run_app.sh .

CMD ./run_app.sh ${KAFKA_BOOTSTRAP_SERVERS} ${KAFKA_TOPIC_NAME}
#CMD ./run_app.sh http://host.docker.internal:9200