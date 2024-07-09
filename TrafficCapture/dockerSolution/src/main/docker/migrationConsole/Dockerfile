FROM migrations/elasticsearch_client_test_console:latest

RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws

WORKDIR /root/kafka-tools
# Get kafka distribution and unpack to 'kafka'
RUN wget -qO- https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz | tar --transform 's!^[^/]*!kafka!' -xvz
RUN wget -O kafka/libs/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v2.0.3/aws-msk-iam-auth-2.0.3-all.jar
WORKDIR /root

# Add Traffic Replayer jars for running KafkaPrinter from this container
COPY staging/trafficReplayer/lib /root/kafka-tools/replayer-jars
RUN printf "#!/bin/sh\njava -cp `echo /root/kafka-tools/replayer-jars/*.jar | tr \   :` \"\$@\" " > /root/kafka-tools/runJavaWithClasspath.sh
RUN chmod +x /root/kafka-tools/runJavaWithClasspath.sh

COPY staging/CreateSnapshot /root/createSnapshot
COPY staging/MetadataMigration /root/metadataMigration

COPY osiPipelineTemplate.yaml /root/
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools

COPY humanReadableLogs.py /root/
RUN chmod ug+x /root/humanReadableLogs.py

COPY showFetchMigrationCommand.sh /root/
RUN chmod ug+x /root/showFetchMigrationCommand.sh

COPY kafkaExport.sh /root/kafka-tools
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh

COPY loadServicesFromParameterStore.sh /root/
RUN chmod ug+x /root/loadServicesFromParameterStore.sh

RUN apt-get install -y pkg-config libhdf5-dev

COPY lib /root/lib
WORKDIR /root/lib/console_link
RUN pipenv install --system --deploy --ignore-pipfile
WORKDIR /root/lib/integ_test
RUN pipenv install --system --deploy --ignore-pipfile


COPY console_api /root/console_api
WORKDIR /root/console_api
RUN pipenv install --system --deploy --ignore-pipfile

WORKDIR /root

# Ensure bash completion is installed
RUN apt-get install -y bash-completion

 CMD /root/loadServicesFromParameterStore.sh && tail -f /dev/null

# Experimental console API, uncomment to use in addition to uncomment port mapping in docker-compose.yml
# CMD /root/loadServicesFromParameterStore.sh && python3 /root/console_api/manage.py runserver_plus 0.0.0.0:8000 --cert-file cert.crt
