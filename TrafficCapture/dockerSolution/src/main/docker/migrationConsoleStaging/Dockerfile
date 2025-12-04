ARG BASE_IMAGE=migrations/migration_console_base:latest

FROM ${BASE_IMAGE}

WORKDIR /root

# Add Traffic Replayer jars for running KafkaPrinter from this container
COPY staging/kafkaCommandLineFormatter/lib /root/kafka-tools/
ENV CLASSPATH=/root/kafka-tools/kafkaCommandLineFormatter.jar

# Copy Java applications
COPY staging/CreateSnapshot /root/createSnapshot
COPY staging/MetadataMigration /root/metadataMigration

# Copy VERSION file
COPY VERSION /root/VERSION

CMD ["/root/start-console.sh"]
