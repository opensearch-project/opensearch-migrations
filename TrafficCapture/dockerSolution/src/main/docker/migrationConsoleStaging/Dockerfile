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

# Copy Node.js built components (config processor and workflow templates)
COPY nodeStaging/configProcessor /root/configProcessor

# Copy test workflow scripts and resources with same structure as configProcessor
COPY lib/console_link/tests/workflow-tests/resources/scripts /root/testConfigProcessor
RUN chmod +x /root/testConfigProcessor/*.sh
RUN chmod +x /root/configProcessor/*.sh

# Set up workflow schema
ENV WORKFLOW_SCHEMA_FILENAME=/root/.workflowUser.schema.json
ENV CONFIG_PROCESSOR_DIR=/root/configProcessor
ENV NODEJS=node-22
RUN "$NODEJS" "$CONFIG_PROCESSOR_DIR" showSchema > "$WORKFLOW_SCHEMA_FILENAME"
RUN echo "# yaml-language-server: \$schema=$WORKFLOW_SCHEMA_FILENAME" > "${CONFIG_PROCESSOR_DIR}/sample.yaml" && \
    "$NODEJS" "$CONFIG_PROCESSOR_DIR" makeSample >> "${CONFIG_PROCESSOR_DIR}/sample.yaml"

# To use test workflows, flip the comments
#ENV CONFIG_PROCESSOR_DIR=/root/testConfigProcessor

# Here are the new workflows that are dormant but can be installed by users on the console
COPY nodeStaging/workflowTemplates /root/newWorkflowTemplates

CMD ["/root/start-console.sh"]
