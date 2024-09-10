FROM docker.elastic.co/elasticsearch/elasticsearch:5.6.16 AS base

# Configure Elastic
ENV ELASTIC_SEARCH_CONFIG_FILE=/usr/share/elasticsearch/config/elasticsearch.yml
RUN echo "discovery.type: single-node" >> $ELASTIC_SEARCH_CONFIG_FILE
RUN echo "xpack.security.enabled: false" >> $ELASTIC_SEARCH_CONFIG_FILE
RUN echo "bootstrap.system_call_filter: false" >> $ELASTIC_SEARCH_CONFIG_FILE
ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/

# Make our snapshot directory
USER root
RUN mkdir /snapshots && chown elasticsearch /snapshots
USER elasticsearch

# We do not install the S3 Repo plugin here, because it is not compatible with modern
# IAM Roles.  Specifically, it does not support the AWS_SESSION_TOKEN environment variable.
# We will instead take snapshots into a mounted local volume.

# Additionally, we will rely on the base image's default entrypoint command to start the
# Elasticsearch service.