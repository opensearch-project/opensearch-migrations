FROM docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2 AS base

# Configure Elastic
ENV ELASTIC_SEARCH_CONFIG_FILE=/usr/share/elasticsearch/config/elasticsearch.yml
# Prevents ES from complaining about nodes count
RUN echo "discovery.type: single-node" >> $ELASTIC_SEARCH_CONFIG_FILE
ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/

# TODO: These yum packages are currently included in the base image and propagated to the final image for adhoc testing
# with OSB, but should be placed in only the generateDatasetStage once we unify RFS and TC gradle setups and allow the Migration Console
# to have this responsibility. Task here: https://opensearch.atlassian.net/browse/MIGRATIONS-1628
RUN cd /etc/yum.repos.d/ && \
    sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* && \
    sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
RUN yum install -y gcc python3.9 python39-devel vim git less
# Specified [h5py==3.10.0] as 3.11.0 version was causing issues with ARM64 builds
# Error: Building h5py requires pkg-config unless the HDF5 path is explicitly specified using the environment variable HDF5_DIR
RUN pip3 install opensearch-benchmark==1.5.0 h5py==3.10.0


FROM base AS generateDatasetStage

ARG DATASET="skip_dataset"
COPY generateDataset.sh /root
RUN chmod ug+x /root/generateDataset.sh

RUN /root/generateDataset.sh ${DATASET}


FROM base

# Install the S3 Repo Plugin
RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install repository-s3

# Install the AWS CLI for testing purposes
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && \
    unzip awscliv2.zip && \
    ./aws/install

RUN mkdir /snapshots && chown elasticsearch /snapshots

COPY --from=generateDatasetStage /usr/share/elasticsearch/data/nodes /usr/share/elasticsearch/data/nodes
RUN chown -R elasticsearch /usr/share/elasticsearch/data

# Install our custom entrypoint script
COPY ./container-start.sh /usr/share/elasticsearch/container-start.sh

CMD /usr/share/elasticsearch/container-start.sh