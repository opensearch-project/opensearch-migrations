FROM ubuntu:jammy

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev openjdk-11-jre-headless wget gcc libc-dev git curl vim jq unzip less && \
    pip3 install urllib3 opensearch-benchmark==1.2.0 awscurl tqdm awscli
RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws

WORKDIR /root/kafka-tools
# Get kafka distribution and unpack to 'kafka'
RUN wget -qO- https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz | tar --transform 's!^[^/]*!kafka!' -xvz
RUN wget -O kafka/libs/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v1.1.9/aws-msk-iam-auth-1.1.9-all.jar
WORKDIR /root

# Add Traffic Replayer jars for running KafkaPrinter from this container
COPY build/jars /root/kafka-tools/replayer-jars
RUN printf "#!/bin/sh\njava -cp `echo /root/kafka-tools/replayer-jars/*.jar | tr \   :` \"\$@\" " > /root/kafka-tools/runJavaWithClasspath.sh
RUN chmod +x /root/kafka-tools/runJavaWithClasspath.sh

COPY runTestBenchmarks.sh /root/
COPY humanReadableLogs.py /root/
COPY simpleDocumentGenerator.py /root/
COPY catIndices.sh /root/
COPY showFetchMigrationCommand.sh /root/
COPY setupIntegTests.sh /root/
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools
COPY kafkaExport.sh /root/kafka-tools
RUN ln -s /usr/bin/python3 /usr/bin/python
RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/humanReadableLogs.py
RUN chmod ug+x /root/simpleDocumentGenerator.py
RUN chmod ug+x /root/catIndices.sh
RUN chmod ug+x /root/showFetchMigrationCommand.sh
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh

CMD tail -f /dev/null
