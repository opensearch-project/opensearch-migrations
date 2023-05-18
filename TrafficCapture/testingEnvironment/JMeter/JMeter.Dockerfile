# syntax=docker/dockerfile:1

FROM openjdk:17-jdk-slim AS cloner

RUN apt-get update && apt-get install -y git

# Be aware that this layer can be cached and not capture recent commits. Run docker build --no-cache to avoid this
RUN git clone https://github.com/opensearch-project/opensearch-migrations.git

FROM openjdk:17-jdk-slim

RUN apt-get update && apt-get install -y netcat
RUN apt-get install -y expect

COPY --from=cloner /opensearch-migrations/TrafficCapture/ ./TrafficCapture/

WORKDIR /TrafficCapture/
RUN ./gradlew trafficCaptureProxyServerTest:build

ARG BIND_PORT
ENV BIND_PORT ${BIND_PORT}

ARG DOMAIN_NAME
ENV DOMAIN_NAME ${DOMAIN_NAME}

COPY run_jmeter.sh .

CMD ./run_jmeter.sh $DOMAIN_NAME $BIND_PORT