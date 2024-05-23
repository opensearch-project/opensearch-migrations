FROM docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2 AS base

# Configure Elastic
ENV ELASTIC_SEARCH_CONFIG_FILE=/usr/share/elasticsearch/config/elasticsearch.yml
# Prevents ES from complaining about nodes count
RUN echo "discovery.type: single-node" >> $ELASTIC_SEARCH_CONFIG_FILE
ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/

# Install the S3 Repo Plugin
RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install repository-s3

# Install the AWS CLI for testing purposes
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && \
    unzip awscliv2.zip && \
    ./aws/install

RUN mkdir /snapshots && chown elasticsearch /snapshots

# Install our custom entrypoint script
COPY ./container-start.sh /usr/share/elasticsearch/container-start.sh

CMD /usr/share/elasticsearch/container-start.sh
