FROM ubuntu:jammy

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev openjdk-11-jre-headless wget gcc libc-dev git curl vim jq && \
    pip3 install urllib3==1.25.11 opensearch-benchmark==1.1.0 awscurl tqdm
RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws
COPY runTestBenchmarks.sh /root/
COPY humanReadableLogs.py /root/
COPY catIndices.sh /root/
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools
RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/humanReadableLogs.py
RUN chmod ug+x /root/catIndices.sh
WORKDIR /root/kafka-tools
RUN curl https://archive.apache.org/dist/kafka/3.5.0/kafka_2.13-3.5.0.tgz -o kafka-3-5.tgz
# Unpackage kafka distribution and name top level 'kafka'
RUN tar xzf kafka-3-5.tgz --transform 's!^[^/]*!kafka!'
RUN rm kafka-3-5.tgz
RUN wget -O aws/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v1.1.9/aws-msk-iam-auth-1.1.9-all.jar
# This is a bit opinionated and should be made cloud agnostic when this Dockerfile becomes more dynamic
ENV CLASSPATH=/root/kafka-tools/aws/msk-iam-auth.jar
WORKDIR /root

CMD tail -f /dev/null