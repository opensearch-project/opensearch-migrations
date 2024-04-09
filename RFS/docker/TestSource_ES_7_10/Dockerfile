FROM docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2

RUN cd /etc/yum.repos.d/ && \
    sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* && \
    sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-* && \
    yum install -y python3.9 vim git && \
    pip3 install opensearch-benchmark

# Install the S3 Repo Plugin
RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install repository-s3

# Install the AWS CLI for testing purposes
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && \
    unzip awscliv2.zip && \
    ./aws/install

ARG EXISTING_DATA="no-data"

RUN mkdir /snapshots && chown elasticsearch /snapshots
COPY --chown=elasticsearch ./test-resources/${EXISTING_DATA} /usr/share/elasticsearch/data

# Install our custom entrypoint script
COPY ./container-start.sh /usr/share/elasticsearch/container-start.sh

# Configure Elastic
ENV ELASTIC_SEARCH_CONFIG_FILE=/usr/share/elasticsearch/config/elasticsearch.yml
# Prevents ES from complaining about nodes coun
RUN echo "discovery.type: single-node" >> $ELASTIC_SEARCH_CONFIG_FILE
ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/

CMD /usr/share/elasticsearch/container-start.sh