# syntax=docker/dockerfile:1

#FROM openjdk:17-jdk-slim AS builder

#RUN apt-get update && apt-get install -y git

# Be weary that this layer can be cached and not capture recent commits. Run docker build --no-cache to avoid this
# TODO: add grabbing from repo again
#RUN git clone https://github.com/opensearch-project/opensearch-migrations.git


#WORKDIR /opensearch-migrations/TrafficCapture/
#RUN ./gradlew trafficCaptureProxyServer:build

#RUN tar -xf /trafficCaptureProxyServer/build/distributions/trafficCaptureProxyServer.tar



FROM openjdk:17-jdk-slim

RUN apt-get update && apt-get install -y netcat && apt-get install -y git
RUN apt-get install -y expect
RUN apt-get install -y curl

#COPY --from=builder /opensearch-migrations/TrafficCapture/trafficCaptureProxyServer/build/libs/*.jar .
#COPY --from=builder /opensearch-migrations/TrafficCapture/trafficCaptureProxyServer/build/distributions/ /classes/
#TODO replace the line below with cloning repo
COPY . /TrafficCapture/

#WORKDIR /opensearch-migrations/TrafficCapture/trafficCaptureProxyServer/build/distributions/classes/
#TODO fix path below after adding cloning repo line
WORKDIR /TrafficCapture/
RUN ./gradlew trafficCaptureProxyServerTest:build

ARG BIND_PORT
ENV BIND_PORT ${BIND_PORT}

ARG DOMAIN_NAME
ENV DOMAIN_NAME ${DOMAIN_NAME}

#ENV CP = $(ls)

#COPY ./trafficCaptureProxyServer/run_app.sh .

#CMD /bin/sh
CMD ./run_jmeter.sh $DOMAIN_NAME $BIND_PORT

#CMD ./run_app.sh http://host.docker.internal:9200