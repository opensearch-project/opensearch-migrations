FROM migrations/elasticsearch_client_test_console:latest

COPY lib /root/lib

RUN pip3 install -r /root/lib/osiMigrationLib/requirements.txt
RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws

WORKDIR /root/kafka-tools
# Get kafka distribution and unpack to 'kafka'
RUN wget -qO- https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz | tar --transform 's!^[^/]*!kafka!' -xvz
RUN wget -O kafka/libs/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v2.0.3/aws-msk-iam-auth-2.0.3-all.jar
WORKDIR /root

# Add Traffic Replayer jars for running KafkaPrinter from this container
COPY build/jars /root/kafka-tools/replayer-jars
RUN printf "#!/bin/sh\njava -cp `echo /root/kafka-tools/replayer-jars/*.jar | tr \   :` \"\$@\" " > /root/kafka-tools/runJavaWithClasspath.sh
RUN chmod +x /root/kafka-tools/runJavaWithClasspath.sh

COPY humanReadableLogs.py /root/
COPY showFetchMigrationCommand.sh /root/
COPY osiMigration.py /root/
COPY osiPipelineTemplate.yaml /root/
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools
COPY kafkaExport.sh /root/kafka-tools
COPY humanReadableLogs.py /root/
COPY setupIntegTests.sh /root/

RUN chmod ug+x /root/humanReadableLogs.py
RUN chmod ug+x /root/showFetchMigrationCommand.sh
RUN chmod ug+x /root/osiMigration.py
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh


CMD tail -f /dev/null
