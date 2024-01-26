FROM ubuntu:jammy

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev openjdk-11-jre-headless wget gcc libc-dev git curl vim jq unzip less && \
    pip3 install urllib3==1.25.11 opensearch-benchmark==1.1.0 awscurl tqdm
# TODO upon the next release of opensearch-benchmark the awscli package should be installed by pip3, with the expected boto3 version upgrade resolving the current conflicts between opensearch-benchmark and awscli
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && unzip awscliv2.zip && ./aws/install && rm -rf aws awscliv2.zip
RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws
COPY runTestBenchmarks.sh /root/
COPY humanReadableLogs.py /root/
COPY catIndices.sh /root/
COPY showFetchMigrationCommand.sh /root/
COPY setupIntegTests.sh /root/
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools
RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/humanReadableLogs.py
RUN chmod ug+x /root/catIndices.sh
RUN chmod ug+x /root/showFetchMigrationCommand.sh
WORKDIR /root/kafka-tools
# Get kafka distribution and unpack to 'kafka'
RUN wget -qO- https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz | tar --transform 's!^[^/]*!kafka!' -xvz
RUN wget -O kafka/libs/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v1.1.9/aws-msk-iam-auth-1.1.9-all.jar
WORKDIR /root

CMD tail -f /dev/null
