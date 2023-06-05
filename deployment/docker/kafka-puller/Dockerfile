# syntax=docker/dockerfile:1

FROM openjdk:17-jdk-slim AS cloner

RUN apt-get update && apt-get install -y git

# Be aware that this layer can be cached and not capture recent commits. Run docker build --no-cache to avoid this
RUN git clone https://github.com/opensearch-project/opensearch-migrations.git

FROM openjdk:17-jdk-slim

ARG KAFKA_BOOTSTRAP_SERVERS
ENV KAFKA_BOOTSTRAP_SERVERS ${KAFKA_BOOTSTRAP_SERVERS}

ARG KAFKA_TOPIC_NAME
ENV KAFKA_TOPIC_NAME ${KAFKA_TOPIC_NAME}

RUN apt-get update && apt-get install -y netcat
RUN apt-get install -y expect

COPY --from=cloner /opensearch-migrations/TrafficCapture/KafkaPrinter/ ./KafkaPrinter/
WORKDIR /KafkaPrinter/
RUN ./gradlew build

COPY run_app.sh .

CMD ./run_app.sh ${KAFKA_BOOTSTRAP_SERVERS} ${KAFKA_TOPIC_NAME}
#CMD ./run_app.sh http://host.docker.internal:9200