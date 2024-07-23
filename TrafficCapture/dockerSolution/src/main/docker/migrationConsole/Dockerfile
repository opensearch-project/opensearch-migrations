FROM migrations/elasticsearch_client_test_console:latest

RUN mkdir /root/kafka-tools
RUN mkdir /root/kafka-tools/aws

WORKDIR /root/kafka-tools
# Get kafka distribution and unpack to 'kafka'
RUN wget -qO- https://archive.apache.org/dist/kafka/3.6.0/kafka_2.13-3.6.0.tgz | tar --transform 's!^[^/]*!kafka!' -xvz
RUN wget -q -O kafka/libs/msk-iam-auth.jar https://github.com/aws/aws-msk-iam-auth/releases/download/v2.0.3/aws-msk-iam-auth-2.0.3-all.jar
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

# Install HDF5 Manually for Opensearch Benchmark compatibility
ARG PYTHON_VERSION=3.11
ARG HDF5_VERSION=1.14.4
ARG YAPPI_VERSION=1.6.0
RUN dnf -y install pkg-config \
        python${PYTHON_VERSION} \
        git \
        gcc \
        gcc-c++ \
        make \
        zlib-devel \
        openssl-devel \
        diffutils \
        hostname \
        tar \
        wget && \
    dnf clean all && \
    rm -rf /var/cache/dnf
RUN wget -q https://github.com/HDFGroup/hdf5/archive/refs/tags/hdf5_${HDF5_VERSION}.tar.gz -O /tmp/hdf5.tar.gz && \
    mkdir /tmp/hdf5 && \
    tar -xzf /tmp/hdf5.tar.gz -C /tmp/hdf5 --strip-components=1 && \
    rm /tmp/hdf5.tar.gz
WORKDIR /tmp/hdf5
RUN CFLAGS="-w" ./configure --prefix=/usr/local --disable-tests --disable-tools && \
    make -j$(nproc) && \
    make install -j$(nproc) && \
    rm -rf /tmp/hdf5
ENV HDF5_DIR=/usr/local
RUN python${PYTHON_VERSION} -m pip install --no-cache-dir h5py wheel
RUN wget -q https://github.com/sumerc/yappi/archive/refs/tags/${YAPPI_VERSION}.tar.gz -O /tmp/yappi.tar.gz && \
    mkdir /tmp/yappi && \
    tar -xzf /tmp/yappi.tar.gz -C /tmp/yappi --strip-components=1 && \
    cd /tmp/yappi && \
    python${PYTHON_VERSION} -m pip install . && \
    python${PYTHON_VERSION} -m pip install --no-cache-dir yappi && \
    rm -rf /tmp/yappi /tmp/yappi.tar.gz
WORKDIR /root
COPY lib /root/lib
WORKDIR /root/lib/console_link
RUN pipenv install --system --deploy --ignore-pipfile --python /usr/bin/python3.11
WORKDIR /root/lib/integ_test
RUN pipenv install --system --deploy --ignore-pipfile --python /usr/bin/python3.11


COPY console_api /root/console_api
WORKDIR /root/console_api
RUN pipenv install --system --deploy --ignore-pipfile --python /usr/bin/python3.11

WORKDIR /root

# Ensure bash completion is installed
RUN dnf install -y bash-completion

CMD /root/loadServicesFromParameterStore.sh && tail -f /dev/null

# Experimental console API, uncomment to use in addition to uncomment port mapping in docker-compose.yml
#CMD /root/loadServicesFromParameterStore.sh && python3 /root/console_api/manage.py runserver_plus 0.0.0.0:8000 --cert-file cert.crt
