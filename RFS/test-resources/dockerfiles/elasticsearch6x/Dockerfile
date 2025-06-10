# Elasticsearch OSS Dockerfile for legacy versions using Debian base
FROM openjdk:8-jdk-slim

ARG ELASTIC_VERSION
ENV ES_HOME=/usr/share/elasticsearch
ENV TMPDIR=/tmp

# Install required tools and extract Elasticsearch
RUN apt-get update && apt-get install -y curl bash \
 && mkdir -p ${ES_HOME} \
 && curl -fsSL https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-${ELASTIC_VERSION}.tar.gz \
    | tar -xz -C ${ES_HOME} --strip-components=1 \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Add user and permissions
RUN useradd -ms /bin/bash elasticsearch \
 && mkdir -p /usr/share/elasticsearch/data \
 && chown -R elasticsearch:elasticsearch ${ES_HOME} /usr/share/elasticsearch/data

USER elasticsearch

EXPOSE 9200 9300
VOLUME ["/usr/share/elasticsearch/data"]
WORKDIR ${ES_HOME}
CMD ["bin/elasticsearch"]