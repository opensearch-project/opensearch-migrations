FROM ubuntu:jammy
ARG EXPERIMENTAL

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3.9 \
    python3-pip \
    python3-dev \
    openjdk-11-jre-headless \
    wget \
    gcc \
    libc-dev \
    git \
    curl \
    vim \
    jq \
    unzip \
    less
RUN pip3 install urllib3 opensearch-benchmark==1.2.0 awscurl tqdm pexpect StrEnum
# AWS CLI v2 is not available on PYPI: https://github.com/aws/aws-cli/issues/4947
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && unzip awscliv2.zip && ./aws/install && rm -rf aws awscliv2.zip

# Experimental libraries needed for API
#RUN if [ "$EXPERIMENTAL" = "true" ]; then \
    #pip3 install \
        #django==5.0.1 \
        #djangorestframework==3.14.0 \
        #django-extensions==3.2.3 \
        #werkzeug==3.0.1; \
    #fi

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
COPY migrationUtils.py /root/
COPY startFetchMigration.py /root/
COPY startLiveCaptureMigration.py /root/
COPY stopFetchMigration.py /root/
COPY stopLiveCaptureMigration.py /root/
COPY getFetchMigrationStatus.py /root/
COPY getLiveCaptureMigrationStatus.py /root/
COPY simpleDocumentGenerator.py /root/
COPY catIndices.sh /root/
COPY setupIntegTests.sh /root/
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools
COPY kafkaExport.sh /root/kafka-tools
RUN ln -s /usr/bin/python3 /usr/bin/python
RUN chmod ug+x /root/runTestBenchmarks.sh
RUN chmod ug+x /root/humanReadableLogs.py
RUN chmod ug+x /root/migrationUtils.py
RUN chmod ug+x /root/startFetchMigration.py
RUN chmod ug+x /root/startLiveCaptureMigration.py
RUN chmod ug+x /root/stopFetchMigration.py
RUN chmod ug+x /root/stopLiveCaptureMigration.py
RUN chmod ug+x /root/getFetchMigrationStatus.py
RUN chmod ug+x /root/getLiveCaptureMigrationStatus.py
RUN chmod ug+x /root/simpleDocumentGenerator.py
RUN chmod ug+x /root/catIndices.sh
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh

# Experimental Django API
COPY console /console
RUN pip3 install -r /console/requirements.txt


COPY init.sh /init.sh
RUN chmod +x /init.sh
ENTRYPOINT ["/init.sh"]
